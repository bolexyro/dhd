import type { AddressInfo } from "node:net";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const requestBridgeMock = vi.hoisted(() => vi.fn());
const discoverPhonesMock = vi.hoisted(() => vi.fn());

vi.mock("../src/pairing.js", async () => {
  const actual = await vi.importActual<typeof import("../src/pairing.js")>("../src/pairing.js");
  return { ...actual, discoverPhones: discoverPhonesMock };
});

vi.mock("../src/phone-assistant-bridge.js", async () => {
  const actual = await vi.importActual<typeof import("../src/phone-assistant-bridge.js")>(
    "../src/phone-assistant-bridge.js",
  );
  return { ...actual, requestBridge: requestBridgeMock };
});

const { createCompanionWebServer } = await import("../src/companion-web/server.js");

const corsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "Content-Type",
};

const noCacheHeaders = {
  "cache-control": "no-cache, no-store, must-revalidate",
  pragma: "no-cache",
  expires: "0",
};

let server: ReturnType<typeof createCompanionWebServer>;
let baseUrl: string;

beforeEach(async () => {
  server = createCompanionWebServer();
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterEach(async () => {
  requestBridgeMock.mockReset();
  discoverPhonesMock.mockReset();
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

function headersOf(response: Response, names: string[]): Record<string, string | null> {
  return Object.fromEntries(names.map((name) => [name, response.headers.get(name)]));
}

function expectCors(response: Response): void {
  expect(headersOf(response, Object.keys(corsHeaders))).toEqual(corsHeaders);
}

describe("dashboard route contract", () => {
  it("answers preflight requests without a body", async () => {
    const response = await fetch(`${baseUrl}/api/check`, { method: "OPTIONS" });

    expect(response.status).toBe(204);
    expect(await response.text()).toBe("");
    expectCors(response);
  });

  it("serves the state snapshot as uncached JSON", async () => {
    const response = await fetch(`${baseUrl}/api/state`);

    expect(response.status).toBe(200);
    expect(headersOf(response, ["content-type", "cache-control"])).toEqual({
      "content-type": "application/json",
      "cache-control": "no-store",
    });
    expectCors(response);
    const state = await response.json();
    expect(Object.keys(state)).toEqual(
      expect.arrayContaining(["processStatus", "bridgeStatus", "settings", "logs", "toolCalls"]),
    );
    expect(Object.keys(state.settings)).toEqual(
      expect.arrayContaining(["host", "port", "tokenConfigured", "pairingConfigured"]),
    );
  });

  it("opens the event stream with the current state as its first frame", async () => {
    const controller = new AbortController();
    const response = await fetch(`${baseUrl}/api/events`, { signal: controller.signal });

    expect(response.status).toBe(200);
    expect(headersOf(response, ["content-type", "cache-control", "connection"])).toEqual({
      "content-type": "text/event-stream",
      "cache-control": "no-cache",
      connection: "keep-alive",
    });
    expectCors(response);
    const reader = response.body!.getReader();
    const { value } = await reader.read();
    const frame = new TextDecoder().decode(value);
    expect(frame.startsWith("data: {")).toBe(true);
    expect(frame.endsWith("\n\n")).toBe(true);
    expect(JSON.parse(frame.slice("data: ".length))).toMatchObject({ settings: expect.any(Object) });
    controller.abort();
    await reader.cancel().catch(() => undefined);
  });

  it.each([
    "/api/tool-calls/missing/images/0",
    "/api/tool-calls/missing/images/0?source=debug",
    "/api/tool-calls/%E0%A4%A/images/0",
  ])("returns a plain 404 for an unknown tool image %s", async (path) => {
    const response = await fetch(`${baseUrl}${path}`);

    expect(response.status).toBe(404);
    expect(response.headers.get("content-type")).toBe("text/plain");
    expect(await response.text()).toBe("Image not found");
  });

  it("lists discovered phones without pairing secrets", async () => {
    discoverPhonesMock.mockResolvedValue([
      {
        deviceId: "phone-1",
        deviceName: "Pixel",
        model: "Pixel 9",
        host: "192.168.1.2",
        addresses: ["192.168.1.2"],
        port: 8765,
        pairingNonce: "nonce",
      },
      { deviceId: "phone-2", deviceName: "Galaxy", host: "192.168.1.3", addresses: [], port: 8765, pairingNonce: "n" },
    ]);

    const response = await fetch(`${baseUrl}/api/discover`, { method: "POST" });

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(await response.text()).toBe(
      JSON.stringify({
        phones: [
          { deviceId: "phone-1", deviceName: "Pixel", model: "Pixel 9" },
          { deviceId: "phone-2", deviceName: "Galaxy" },
        ],
      }),
    );
  });

  it("reports discovery failures as 500 with a message", async () => {
    discoverPhonesMock.mockRejectedValue(new Error("Phone discovery failed: EACCES"));

    const response = await fetch(`${baseUrl}/api/discover`, { method: "POST" });

    expect(response.status).toBe(500);
    expect(await response.json()).toEqual({ message: "Phone discovery failed: EACCES" });
  });

  it.each([
    ["{not json", "Invalid JSON body."],
    [JSON.stringify({}), "A discovered phone must be selected."],
    [JSON.stringify({ deviceId: "  " }), "A discovered phone must be selected."],
  ])("rejects pair-device body %s with 400", async (body, message) => {
    const response = await fetch(`${baseUrl}/api/pair-device`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
    });

    expect(response.status).toBe(400);
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(await response.json()).toEqual({ message });
  });

  it("reports the link check result as JSON", async () => {
    requestBridgeMock.mockResolvedValue({ type: "status", ok: true, state: "idle", active: false });

    const response = await fetch(`${baseUrl}/api/check`, { method: "POST" });

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(await response.json()).toMatchObject({ ok: false, phone: { state: "idle", active: false } });
  });

  it.each(["/api/clear-logs", "/api/clear-tool-calls"])("returns the cleared state from %s", async (path) => {
    const response = await fetch(`${baseUrl}${path}`, { method: "POST" });

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("application/json");
    const state = await response.json();
    expect(state[path === "/api/clear-logs" ? "logs" : "toolCalls"]).toEqual([]);
  });

  it.each([
    ["/", "text/html; charset=utf-8", "<!doctype html>"],
    ["/index.html", "text/html; charset=utf-8", "<!doctype html>"],
    ["/styles.css", "text/css; charset=utf-8", ""],
    ["/favicon.png", "image/png", ""],
  ])("serves static asset %s", async (path, contentType, prefix) => {
    const response = await fetch(`${baseUrl}${path}`);

    expect(response.status).toBe(200);
    expect(headersOf(response, ["content-type", ...Object.keys(noCacheHeaders)])).toEqual({
      "content-type": contentType,
      ...noCacheHeaders,
    });
    expect((await response.text()).toLowerCase().startsWith(prefix)).toBe(true);
  });

  it.each(["/renderer.js", "/renderer.ts", "/api.js", "/api.ts"])(
    "serves browser module %s as JavaScript",
    async (path) => {
      const response = await fetch(`${baseUrl}${path}`);

      expect(response.status).toBe(200);
      expect(headersOf(response, ["content-type", ...Object.keys(noCacheHeaders)])).toEqual({
        "content-type": "application/javascript; charset=utf-8",
        ...noCacheHeaders,
      });
      const source = await response.text();
      expect(source).not.toMatch(/^import type/m);
      expect(source).not.toContain(": Promise<");
    },
  );

  it.each([
    ["GET", "/missing"],
    ["POST", "/api/state"],
    ["GET", "/api/discover"],
    ["GET", "/renderer"],
  ])("returns a plain 404 for %s %s", async (method, path) => {
    const response = await fetch(`${baseUrl}${path}`, { method });

    expect(response.status).toBe(404);
    expect(response.headers.get("content-type")).toBe("text/plain");
    expect(await response.text()).toBe("Not Found");
    expectCors(response);
  });
});
