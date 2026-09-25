import { afterEach, describe, expect, it, vi } from "vitest";

const requestBridgeMock = vi.hoisted(() => vi.fn(async () => ({ type: "completed", ok: true })));

vi.mock("../src/phone/bridge-client.js", async () => {
  const actual = await vi.importActual<typeof import("../src/phone/bridge-client.js")>(
    "../src/phone/bridge-client.js",
  );
  return { ...actual, requestBridge: requestBridgeMock };
});

const { GUARD_REGIONS_FEATURE_FLAG, invokeDhdTool } = await import("../src/dhd-tools.js");

const originalGuardRegionsFlag = process.env[GUARD_REGIONS_FEATURE_FLAG];

afterEach(() => {
  requestBridgeMock.mockClear();
  if (originalGuardRegionsFlag === undefined) delete process.env[GUARD_REGIONS_FEATURE_FLAG];
  else process.env[GUARD_REGIONS_FEATURE_FLAG] = originalGuardRegionsFlag;
});

const displayRef = "dsp_0123456789abcd";
const actionMetadata = {
  purpose: "Tap the search field",
  targetDescription: "Search field",
  observationId: "obs-1",
};
const stepMetadata = {
  purpose: "Advance the flow",
  targetDescription: "Next control",
};
const guardRegion = { left: 1, top: 2, right: 30, bottom: 40 };

type ToolCase = [label: string, tool: string, input: unknown];

const labelledActions = (metadata: Record<string, unknown>): Array<[string, Record<string, unknown>]> => [
  ["tap", { type: "tap", x: 10, y: 20, metadata }],
  ["type", { type: "type", text: "iced tea", metadata }],
  ["swipe", { type: "swipe", startX: 180, startY: 600, endX: 180, endY: 200, metadata }],
  ["swipe with duration", { type: "swipe", startX: 180, startY: 600, endX: 180, endY: 200, durationMs: 350, metadata }],
  ["back", { type: "back", metadata }],
  ["keypress", { type: "keypress", key: "ENTER", metadata }],
  ["wait", { type: "wait", durationMs: 500, metadata }],
];

const executeActions = (metadata: Record<string, unknown>) =>
  labelledActions(metadata).map(([, action]) => action);

const standardCases: ToolCase[] = [
  ["default", "dhd_list_allowed_apps", {}],
  ["include all", "dhd_list_allowed_apps", { includeAll: true }],
  ["trimmed query", "dhd_browse_app", { query: "  Spotify  " }],
  ["full size", "dhd_set_app_display_layout", { packageName: "com.example.store", layout: "full_size" }],
  ["standard", "dhd_set_app_display_layout", { packageName: "com.example.store", layout: "standard" }],
  ["all", "dhd_list_displays", {}],
  ["by ref", "dhd_close_display", { displayRef }],
  ["default display", "dhd_get_foreground_app", {}],
  ["explicit display", "dhd_get_foreground_app", { displayRef }],
  ["bare", "dhd_observe", {}],
  [
    "described",
    "dhd_observe",
    { purpose: "Inspect", targetDescription: "Home screen", displayRef },
  ],
  [
    "default display",
    "dhd_open_app",
    { packageName: "com.example.store", metadata: { purpose: "Open", targetDescription: "Store" } },
  ],
  [
    "explicit display",
    "dhd_open_app",
    { displayRef, packageName: "com.example.store", metadata: { purpose: "Open", targetDescription: "Store" } },
  ],
  ...labelledActions(actionMetadata).map(([label, action]): ToolCase => [label, "dhd_execute", { action }]),
  ["explicit display", "dhd_execute", { displayRef, action: executeActions(actionMetadata)[0] }],
  [
    "every action variant",
    "dhd_execute_sequence",
    { observationId: "obs-1", displayRef, actions: executeActions(stepMetadata) },
  ],
  ["default display", "dhd_request_attention", { reason: "Unlock the phone" }],
  ["explicit display", "dhd_request_attention", { reason: "Unlock the phone", displayRef }],
];

const guardRegionCases: ToolCase[] = [
  ["tap without regions", "dhd_execute", { action: executeActions(actionMetadata)[0] }],
  [
    "tap with regions",
    "dhd_execute",
    { action: { type: "tap", x: 10, y: 20, metadata: { ...actionMetadata, guardRegions: [guardRegion] } } },
  ],
  [
    "sequence with regions",
    "dhd_execute_sequence",
    {
      observationId: "obs-1",
      actions: [
        { type: "tap", x: 10, y: 20, metadata: { ...stepMetadata, guardRegions: [guardRegion] } },
        { type: "back", metadata: stepMetadata },
      ],
    },
  ],
  [
    "open app ignores the flag",
    "dhd_open_app",
    { packageName: "com.example.store", metadata: { purpose: "Open", targetDescription: "Store" } },
  ],
];

async function recordBridgeCalls(cases: ToolCase[]): Promise<string> {
  const lines: string[] = [];
  for (const [label, tool, input] of cases) {
    requestBridgeMock.mockClear();
    const result = await invokeDhdTool(tool, input);
    expect(result.isError, `${tool} ${label}`).toBeUndefined();
    expect(requestBridgeMock).toHaveBeenCalledTimes(1);
    const [request, options] = requestBridgeMock.mock.calls[0] as unknown as [
      Record<string, unknown>,
      unknown,
    ];
    expect(request.requestId).toEqual(expect.any(String));
    lines.push(
      `${tool} (${label})`,
      `  request ${JSON.stringify({ ...request, requestId: "<request-id>" })}`,
      `  options ${JSON.stringify(options)}`,
    );
  }
  return `${lines.join("\n")}\n`;
}

describe("DHD tool bridge payloads", () => {
  it("sends byte-identical bridge requests for every tool and action variant", async () => {
    delete process.env[GUARD_REGIONS_FEATURE_FLAG];

    await expect(await recordBridgeCalls(standardCases)).toMatchFileSnapshot(
      "./__snapshots__/bridge-payloads.guard-regions-off.txt",
    );
  });

  it("sends guard regions only on execution requests when the flag is enabled", async () => {
    process.env[GUARD_REGIONS_FEATURE_FLAG] = "true";

    await expect(await recordBridgeCalls(guardRegionCases)).toMatchFileSnapshot(
      "./__snapshots__/bridge-payloads.guard-regions-on.txt",
    );
  });

  it("never reaches the bridge for invalid input", async () => {
    const result = await invokeDhdTool("dhd_browse_app", { query: " " });

    expect(result.isError).toBe(true);
    expect(requestBridgeMock).not.toHaveBeenCalled();
    expect(JSON.parse((result.content[0] as { text: string }).text)).toMatchObject({
      ok: false,
      message: expect.stringContaining("Invalid phone assistant input"),
    });
  });

  it("rejects unknown tool names", async () => {
    await expect(invokeDhdTool("dhd_unknown", {})).rejects.toThrow("Unknown DHD tool: dhd_unknown");
  });
});
