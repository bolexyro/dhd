import { describe, expect, it, vi } from "vitest";

const bridge = vi.hoisted(() => ({
  BLOCKING_BRIDGE_TIMEOUT_MS: 45_000,
  requestBridge: vi.fn(async () => ({ type: "completed", ok: true }))
}));

vi.mock("../src/phone/bridge-client.js", () => bridge);

import { invokeDhdTool } from "../src/dhd-tools.js";

const metadata = {
  purpose: "Tap the button",
  targetDescription: "Primary button",
  observationId: "obs-1"
};

const openAppMetadata = {
  purpose: "Open the app",
  targetDescription: "The requested application"
};

describe("DHD bridge tool metadata", () => {
  it("sends the canonical tool name for every dynamic phone tool", async () => {
    await invokeDhdTool("dhd_list_allowed_apps", {});
    await invokeDhdTool("dhd_browse_app", { query: "Spotify" });
    await invokeDhdTool("dhd_set_app_display_layout", {
      packageName: "com.example.store",
      layout: "full_size",
    });
    await invokeDhdTool("dhd_get_foreground_app", {});
    await invokeDhdTool("dhd_observe", { purpose: "Inspect the current screen" });
    await invokeDhdTool("dhd_open_app", {
      packageName: "com.example.store",
      metadata: openAppMetadata
    });
    await invokeDhdTool("dhd_execute", {
      action: { type: "tap", x: 20, y: 30, metadata }
    });
    await invokeDhdTool("dhd_execute_sequence", {
      observationId: "obs-1",
      actions: [{
        type: "tap",
        x: 20,
        y: 30,
        metadata: {
          purpose: "Tap the button",
          targetDescription: "Primary button"
        }
      }]
    });
    await invokeDhdTool("dhd_request_attention", { reason: "Please review the phone" });

    expect(bridge.requestBridge.mock.calls.map(([request]) => request.tool)).toEqual([
      "dhd_list_allowed_apps",
      "dhd_browse_app",
      "dhd_set_app_display_layout",
      "dhd_get_foreground_app",
      "dhd_observe",
      "dhd_open_app",
      "dhd_execute",
      "dhd_execute_sequence",
      "dhd_request_attention"
    ]);

    for (const tool of [
      "dhd_get_foreground_app",
      "dhd_observe",
      "dhd_open_app",
      "dhd_execute",
      "dhd_execute_sequence",
    ]) {
      const call = bridge.requestBridge.mock.calls.find(([request]) => request.tool === tool);
      expect(call?.[1]).toEqual({
        timeoutMs: 45_000,
        acceptedTimeoutMs: 600_000,
      });
    }
    const attentionCall = bridge.requestBridge.mock.calls.find(
      ([request]) => request.tool === "dhd_request_attention",
    );
    expect(attentionCall?.[1]).toEqual({
      timeoutMs: 45_000,
      keepOpenAfterAccepted: true,
    });
  });
});
