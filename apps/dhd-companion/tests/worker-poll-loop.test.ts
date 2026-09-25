import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

type BridgeResponse = Record<string, unknown>;
type BridgeResponder = (request: Record<string, unknown>) => BridgeResponse | Promise<BridgeResponse>;

const bridge = vi.hoisted(() => ({
  responders: new Map<string, BridgeResponder>(),
  calls: [] as Array<{ request: Record<string, unknown>; options: unknown }>,
}));

vi.mock("../src/phone/bridge-client.js", async () => {
  const actual = await vi.importActual<typeof import("../src/phone/bridge-client.js")>(
    "../src/phone/bridge-client.js",
  );
  return {
    ...actual,
    requestBridge: vi.fn(async (request: Record<string, unknown>, options?: unknown) => {
      bridge.calls.push({ request, options });
      const responder = bridge.responders.get(String(request.type));
      return responder ? responder(request) : { ok: true };
    }),
  };
});

const { runAssistantCompanion } = await import("../src/worker/main.js");
const { CodexAppServerClient } = await import("../src/codex/app-server-client.js");
const { startFakeAppServer } = await import("./support/fake-app-server.js");

let errorLog: string[];

beforeEach(() => {
  bridge.responders.clear();
  bridge.calls.length = 0;
  errorLog = [];
  vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
    errorLog.push(args.map(String).join(" "));
  });
  vi.stubEnv("PHONE_ASSISTANT_POLL_MS", "250");
  vi.stubEnv("PHONE_ASSISTANT_BRIDGE_HOST", "");
});

afterEach(() => {
  vi.unstubAllEnvs();
});

const typesSent = () => bridge.calls.map(({ request }) => String(request.type));

describe("companion worker poll loop", () => {
  it("prewarms Codex, runs a claimed request, polls steers while it runs, and stops on SIGTERM", async () => {
    const server = startFakeAppServer();
    const client = new CodexAppServerClient({ spawnAppServer: server.spawn });
    let pendingPolls = 0;
    bridge.responders.set("pending_request", () => {
      pendingPolls += 1;
      return pendingPolls === 1
        ? { ok: true, available: true, sessionId: "session-1" }
        : { ok: true, available: false };
    });
    bridge.responders.set("claim_request", () => ({ ok: true, request: "Order iced tea" }));
    bridge.responders.set("pending_steer", () => ({ ok: true, active: true, available: false }));
    server.handle("turn/start", () => ({ turn: { id: "turn-1" } }));

    const worker = runAssistantCompanion(client);
    await vi.waitFor(() => expect(typesSent()).toContain("pending_steer"), { timeout: 5_000 });
    server.notify("turn/completed", { turn: { status: "completed" } });
    await vi.waitFor(() => expect(typesSent()).toContain("complete_session"), { timeout: 5_000 });
    const pollsAfterCompletion = pendingPolls;
    await vi.waitFor(() => expect(pendingPolls).toBeGreaterThan(pollsAfterCompletion), { timeout: 5_000 });
    process.emit("SIGTERM");
    await worker;

    const sent = typesSent();
    expect(sent[0]).toBe("heartbeat");
    expect(sent.indexOf("pending_request")).toBeLessThan(sent.indexOf("claim_request"));
    expect(sent.indexOf("claim_request")).toBeLessThan(sent.indexOf("pending_steer"));
    expect(sent.lastIndexOf("pending_steer")).toBeLessThan(sent.indexOf("complete_session"));
    expect(sent.lastIndexOf("pending_request")).toBeGreaterThan(sent.indexOf("complete_session"));
    expect(new Set(sent)).toEqual(
      new Set(["heartbeat", "pending_request", "claim_request", "pending_steer", "complete_session"]),
    );
    const optionsFor = (type: string) =>
      bridge.calls.find(({ request }) => request.type === type)?.options;
    expect(optionsFor("heartbeat")).toEqual({ timeoutMs: 4_000 });
    expect(optionsFor("pending_request")).toEqual({ timeoutMs: 5_000 });
    expect(optionsFor("pending_steer")).toEqual({ timeoutMs: 5_000 });
    expect(server.methods().slice(0, 4)).toEqual(["initialize", "thread/start", "thread/name/set", "turn/start"]);
    expect(server.requests("initialize")).toHaveLength(1);
    expect(server.running).toBe(false);
    expect(errorLog).toEqual(
      expect.arrayContaining([
        "[phone-assistant-companion] waiting for a request typed in the Android app",
        "[phone-assistant-companion] phone bridge target 127.0.0.1:8765",
        "[phone-assistant-companion] loopback mode: adb forward tcp:8765 tcp:8765 works when PHONE_ASSISTANT_BRIDGE_TOKEN matches the paired phone",
        "[phone-assistant-companion] a logged-in Codex CLI must be available on this companion host",
      ]),
    );
  });

  it("keeps polling after bridge failures and logs a rejected poll", async () => {
    const server = startFakeAppServer();
    const client = new CodexAppServerClient({ spawnAppServer: server.spawn });
    let pendingPolls = 0;
    bridge.responders.set("pending_request", () => {
      pendingPolls += 1;
      if (pendingPolls === 1) throw new Error("connect ECONNREFUSED");
      return { ok: false, message: "unauthorized" };
    });
    bridge.responders.set("heartbeat", () => ({ ok: false }));

    const worker = runAssistantCompanion(client);
    await vi.waitFor(() => expect(pendingPolls).toBeGreaterThanOrEqual(2), { timeout: 5_000 });
    process.emit("SIGTERM");
    await worker;

    expect(errorLog).toEqual(
      expect.arrayContaining([
        "[phone-assistant-companion] connect ECONNREFUSED",
        "[phone-assistant-companion] phone bridge rejected poll: unauthorized",
        "[phone-assistant-companion] phone bridge heartbeat unavailable: The phone bridge rejected the companion heartbeat.",
      ]),
    );
  });

  it("shuts down when the dashboard IPC channel disconnects", async () => {
    const server = startFakeAppServer();
    const client = new CodexAppServerClient({ spawnAppServer: server.spawn });
    let pendingPolls = 0;
    bridge.responders.set("pending_request", () => {
      pendingPolls += 1;
      return { ok: true, available: false };
    });

    const worker = runAssistantCompanion(client);
    await vi.waitFor(() => expect(pendingPolls).toBeGreaterThanOrEqual(1), { timeout: 5_000 });
    process.emit("disconnect");

    await expect(worker).resolves.toBeUndefined();
    expect(server.running).toBe(false);
  });

  it("rejects an invalid poll interval before contacting the phone", async () => {
    vi.stubEnv("PHONE_ASSISTANT_POLL_MS", "10");

    await expect(runAssistantCompanion()).rejects.toThrow(
      "PHONE_ASSISTANT_POLL_MS must be between 250 and 60000.",
    );
    expect(bridge.calls).toEqual([]);
  });
});
