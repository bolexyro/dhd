import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it, vi } from "vitest";

import { loadConnection } from "../src/dashboard/server/settings-store.js";
import { phoneSnapshot } from "../src/dashboard/server/status-check.js";
import { ToolCallStore, decodeImage, toJsonValue } from "../src/dashboard/server/tool-call-store.js";

afterEach(() => {
  vi.unstubAllEnvs();
});

function settingsFile(contents?: unknown): string {
  const path = join(mkdtempSync(join(tmpdir(), "dhd-settings-")), "companion-connection.json");
  if (contents !== undefined) {
    writeFileSync(path, typeof contents === "string" ? contents : JSON.stringify(contents));
  }
  return path;
}

function stubBridgeEnv(host?: string, port?: string, token?: string): void {
  vi.stubEnv("PHONE_ASSISTANT_BRIDGE_HOST", host ?? "");
  vi.stubEnv("PHONE_ASSISTANT_BRIDGE_PORT", port);
  vi.stubEnv("PHONE_ASSISTANT_BRIDGE_TOKEN", token ?? "");
}

describe("dashboard connection settings", () => {
  it("uses loopback defaults without a settings file or environment", async () => {
    stubBridgeEnv();

    await expect(loadConnection(settingsFile())).resolves.toEqual({
      host: "127.0.0.1",
      port: 8765,
      token: "",
    });
  });

  it("uses the environment for an unpaired configuration", async () => {
    stubBridgeEnv(" 10.0.0.5 ", "9100", " env-token ");

    await expect(loadConnection(settingsFile())).resolves.toEqual({
      host: "10.0.0.5",
      port: 9100,
      token: "env-token",
    });
  });

  it("falls back to the default port for an invalid environment port", async () => {
    stubBridgeEnv(undefined, "not-a-port");

    await expect(loadConnection(settingsFile())).resolves.toMatchObject({ port: 8765 });
  });

  it("uses stored unpaired settings when the environment is empty", async () => {
    stubBridgeEnv();

    await expect(
      loadConnection(settingsFile({ host: " 10.0.0.2 ", port: 9000, token: " stored-token " })),
    ).resolves.toEqual({ host: "10.0.0.2", port: 9000, token: "stored-token" });
  });

  it("lets the environment override stored unpaired settings", async () => {
    stubBridgeEnv("10.0.0.9", "9200", "env-token");

    await expect(
      loadConnection(settingsFile({ host: "10.0.0.2", port: 9000, token: "stored-token" })),
    ).resolves.toEqual({ host: "10.0.0.9", port: 9200, token: "env-token" });
  });

  it("lets a saved pairing override the environment", async () => {
    stubBridgeEnv("10.0.0.9", "9200", "env-token");

    await expect(
      loadConnection(
        settingsFile({ host: " 10.0.0.3 ", port: 9001, token: " paired-token ", deviceId: " phone-1 " }),
      ),
    ).resolves.toEqual({ host: "10.0.0.3", port: 9001, token: "paired-token", deviceId: "phone-1" });
  });

  it("falls back to loopback and an empty token for an incomplete saved pairing", async () => {
    stubBridgeEnv("10.0.0.9", undefined, "env-token");

    await expect(loadConnection(settingsFile({ deviceId: "phone-1" }))).resolves.toEqual({
      host: "127.0.0.1",
      port: 8765,
      token: "",
      deviceId: "phone-1",
    });
  });

  it.each([0, 65_536, 1.5, "9000"])("rejects stored port %j", async (port) => {
    stubBridgeEnv();

    await expect(loadConnection(settingsFile({ host: "10.0.0.2", port }))).resolves.toMatchObject({
      port: 8765,
    });
  });

  it("keeps a blank stored device id without treating it as a pairing", async () => {
    stubBridgeEnv("10.0.0.9");

    await expect(loadConnection(settingsFile({ host: "10.0.0.2", deviceId: "  " }))).resolves.toEqual({
      host: "10.0.0.9",
      port: 8765,
      token: "",
      deviceId: "",
    });
  });

  it("ignores an unreadable settings file", async () => {
    stubBridgeEnv();

    await expect(loadConnection(settingsFile("{not json"))).resolves.toEqual({
      host: "127.0.0.1",
      port: 8765,
      token: "",
    });
  });
});

describe("dashboard value helpers", () => {
  it.each([
    [null, null],
    ["text", "text"],
    [true, true],
    [3.5, 3.5],
    [Number.NaN, "NaN"],
    [Number.POSITIVE_INFINITY, "Infinity"],
    [undefined, "undefined"],
    [10n, "10"],
    [[1, undefined, { a: Number.NEGATIVE_INFINITY }], [1, "undefined", { a: "-Infinity" }]],
    [{ nested: { list: [null, "x"] } }, { nested: { list: [null, "x"] } }],
  ])("toJsonValue(%s)", (value, expected) => {
    expect(toJsonValue(value)).toEqual(expected);
  });

  it.each([
    ["aGVsbG8=", "hello"],
    [" aGVs\nbG8= ", "hello"],
    ["data:image/png;base64,aGVsbG8=", "hello"],
    ["DATA:image/jpeg;BASE64,aGVsbG8=", "hello"],
    ["", undefined],
    ["aGVsbG8", undefined],
    ["not base64!", undefined],
    ["data:image/png;base64,", undefined],
  ])("decodeImage(%j)", (value, expected) => {
    const decoded = decodeImage(value);
    expect(decoded?.toString("utf8")).toBe(expected);
  });

  it("keeps only safe phone status fields", () => {
    expect(phoneSnapshot({})).toEqual({ state: "unknown", active: false });
    expect(
      phoneSnapshot({
        type: "status",
        ok: true,
        state: "running",
        active: true,
        companionConnected: false,
        sessionId: "session-1",
        request: "Order tea",
        currentPurpose: "Searching",
        requestAvailable: true,
        authToken: "secret",
      }),
    ).toEqual({
      state: "running",
      active: true,
      companionConnected: false,
      sessionId: "session-1",
      request: "Order tea",
      currentPurpose: "Searching",
      requestAvailable: true,
    });
    expect(phoneSnapshot({ state: 3, active: "yes", companionConnected: "true" })).toEqual({
      state: "unknown",
      active: false,
    });
  });

  it("stores tool images outside the dashboard response", () => {
    const image = Buffer.from("image").toString("base64");
    const store = new ToolCallStore();
    const dashboardToolResponse = store.toolResponse.bind(store);

    expect(dashboardToolResponse("call-1", null)).toBeUndefined();
    expect(
      dashboardToolResponse("call-1", {
        isError: true,
        content: [
          { type: "text", text: "{}" },
          { type: "image", data: image, mimeType: "image/png" },
          { type: "image", data: image, mimeType: "text/plain" },
          { type: "image", data: "!!", mimeType: "image/png" },
          null,
        ],
        debugImages: [
          { type: "image", label: "before", data: image, mimeType: "image/png" },
          { type: "image", label: "during", data: image, mimeType: "image/png" },
          { type: "image", label: "after", data: image, mimeType: "image/jpeg" },
        ],
        structuredContent: { ok: false, value: Number.NaN },
      }),
    ).toEqual({
      images: [{ type: "image", imageUrl: "/api/tool-calls/call-1/images/1", mimeType: "image/png", index: 1 }],
      debugImages: [
        {
          type: "image",
          label: "before",
          imageUrl: "/api/tool-calls/call-1/images/0?source=debug",
          mimeType: "image/png",
          index: 0,
        },
        {
          type: "image",
          label: "after",
          imageUrl: "/api/tool-calls/call-1/images/2?source=debug",
          mimeType: "image/jpeg",
          index: 2,
        },
      ],
      isError: true,
      structuredContent: { ok: false, value: "NaN" },
    });
    expect(dashboardToolResponse("call id/2", { content: [], structuredContent: [1] })).toEqual({ images: [] });
    expect(
      dashboardToolResponse("call id/2", { content: [{ type: "image", data: image, mimeType: "image/png" }] }),
    ).toEqual({
      images: [{ type: "image", imageUrl: "/api/tool-calls/call%20id%2F2/images/0", mimeType: "image/png", index: 0 }],
    });
  });
});
