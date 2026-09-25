import { EventEmitter } from "node:events";
import { mkdtempSync, readFileSync, statSync } from "node:fs";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { PassThrough } from "node:stream";

import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

class FakeWorker extends EventEmitter {
  readonly stdout = new PassThrough();
  readonly stderr = new PassThrough();
  readonly signals: Array<NodeJS.Signals | undefined> = [];
  killed = false;

  kill(signal?: NodeJS.Signals): boolean {
    this.signals.push(signal);
    this.killed = true;
    setImmediate(() => this.emit("exit", null, signal ?? "SIGTERM"));
    return true;
  }
}

const workers = vi.hoisted(() => ({
  spawned: [] as Array<{ command: string; args: string[]; options: Record<string, unknown>; child: unknown }>,
}));
const requestBridgeMock = vi.hoisted(() => vi.fn());
const discoverPhonesMock = vi.hoisted(() => vi.fn());
const requestPairingApprovalMock = vi.hoisted(() => vi.fn());

vi.mock("node:child_process", async () => {
  const actual = await vi.importActual<typeof import("node:child_process")>("node:child_process");
  return {
    ...actual,
    spawn: vi.fn((command: string, args: string[], options: Record<string, unknown>) => {
      const child = new FakeWorker();
      workers.spawned.push({ command, args, options, child });
      return child;
    }),
  };
});

vi.mock("../src/phone/pairing.js", async () => {
  const actual = await vi.importActual<typeof import("../src/phone/pairing.js")>("../src/phone/pairing.js");
  return { ...actual, discoverPhones: discoverPhonesMock, requestPairingApproval: requestPairingApprovalMock };
});

vi.mock("../src/phone/bridge-client.js", async () => {
  const actual = await vi.importActual<typeof import("../src/phone/bridge-client.js")>(
    "../src/phone/bridge-client.js",
  );
  return { ...actual, requestBridge: requestBridgeMock };
});

const home = mkdtempSync(join(tmpdir(), "dhd-dashboard-home-"));
const projectRoot = resolve(import.meta.dirname, "..");

let baseUrl: string;
let server: import("node:http").Server;

async function state(): Promise<Record<string, any>> {
  return (await fetch(`${baseUrl}/api/state`)).json() as Promise<Record<string, any>>;
}

function worker(index: number): FakeWorker {
  return workers.spawned[index].child as FakeWorker;
}

beforeAll(async () => {
  vi.stubEnv("HOME", home);
  vi.stubEnv("USERPROFILE", home);
  vi.stubEnv("PHONE_ASSISTANT_BRIDGE_HOST", "");
  vi.stubEnv("PHONE_ASSISTANT_BRIDGE_PORT", undefined);
  vi.stubEnv("PHONE_ASSISTANT_BRIDGE_TOKEN", "");
  vi.spyOn(console, "log").mockImplementation(() => undefined);
  const { startCompanionWebServer } = await import("../src/companion-web/server.js");
  server = await startCompanionWebServer(0, "127.0.0.1");
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolveClose) => server.close(() => resolveClose()));
  vi.unstubAllEnvs();
});

describe("dashboard worker supervision", () => {
  it("starts the worker from source with the dashboard connection over IPC", async () => {
    expect(workers.spawned).toHaveLength(1);
    const [{ command, args, options }] = workers.spawned;
    expect(command).toBe(process.execPath);
    expect(args).toEqual(["--import", "tsx", resolve(projectRoot, "src/assistant-companion.ts")]);
    expect(options).toMatchObject({
      cwd: projectRoot,
      stdio: ["ignore", "pipe", "pipe", "ipc"],
      windowsHide: true,
    });
    expect(options.env).toMatchObject({
      PHONE_ASSISTANT_BRIDGE_HOST: "127.0.0.1",
      PHONE_ASSISTANT_BRIDGE_PORT: "8765",
      PHONE_ASSISTANT_BRIDGE_TOKEN: "",
    });

    const current = await state();
    expect(current.processStatus).toBe("running");
    expect(current.logs.map((entry: Record<string, string>) => [entry.level, entry.source, entry.message])).toEqual([
      ["system", "system", "Starting companion worker for 127.0.0.1:8765."],
      ["system", "system", "Companion worker is running."],
    ]);
  });

  it("turns worker output into classified log lines", async () => {
    await fetch(`${baseUrl}/api/clear-logs`, { method: "POST" });
    worker(0).stdout.write("claimed session-1\r\nCould not reach the phone\n  \npartial");
    worker(0).stderr.write("request TIMED OUT\n");
    worker(0).stdout.end();

    await vi.waitFor(async () => expect((await state()).logs).toHaveLength(4));
    const lines = (await state()).logs.map((entry: Record<string, string>) => [entry.level, entry.source, entry.message]);
    expect(lines.filter(([, , message]: string[]) => message !== "request TIMED OUT")).toEqual([
      ["info", "companion", "claimed session-1"],
      ["error", "companion", "Could not reach the phone"],
      ["info", "companion", "partial"],
    ]);
    expect(lines).toContainEqual(["error", "companion", "request TIMED OUT"]);
  });

  it("ingests tool, token usage, and plan events sent over IPC", async () => {
    worker(0).emit("message", {
      type: "dhd_tool_call",
      phase: "started",
      callId: "call-ipc",
      tool: "dhd_observe",
      arguments: {},
      timestamp: 1,
    });
    worker(0).emit("message", { type: "dhd_plan", phase: "updated", threadId: "t", turnId: "u", steps: [], timestamp: 2 });
    worker(0).emit("message", { type: "unknown" });

    const current = await state();
    expect(current.toolCalls.map((call: Record<string, unknown>) => call.id)).toContain("call-ipc");
    expect(current.plan).toEqual({ threadId: "t", turnId: "u", steps: [], updatedAt: 2 });
  });

  it("restarts the worker one second after an unexpected exit", async () => {
    await fetch(`${baseUrl}/api/clear-logs`, { method: "POST" });
    worker(0).emit("exit", 1, null);

    const failed = await state();
    expect(failed).toMatchObject({
      processStatus: "error",
      bridgeStatus: "offline",
      lastError: "Companion worker exited with code 1.",
    });
    await vi.waitFor(() => expect(workers.spawned).toHaveLength(2), { timeout: 3_000 });
    const restarted = await state();
    expect(restarted.processStatus).toBe("running");
    expect(restarted.logs.map((entry: Record<string, string>) => entry.message)).toEqual([
      "Companion worker exited (code 1).",
      "Companion worker exited unexpectedly; restarting it.",
      "Starting companion worker for 127.0.0.1:8765.",
      "Companion worker is running.",
    ]);
  });

  it("stops the worker and restarts it on the newly paired phone", async () => {
    discoverPhonesMock.mockResolvedValue([
      { deviceId: "phone-1", deviceName: "Pixel", host: "192.168.1.2", addresses: ["192.168.1.2"], port: 9001, pairingNonce: "n" },
    ]);
    requestPairingApprovalMock.mockResolvedValue({
      deviceId: "phone-1", host: "192.168.1.2", port: 9001, token: "paired-token", addresses: ["192.168.1.2"],
    });
    requestBridgeMock.mockImplementation(async (request: { type: string }) =>
      request.type === "status"
        ? { type: "status", ok: true, state: "idle", active: false, companionConnected: true }
        : { ok: true },
    );

    const response = await fetch(`${baseUrl}/api/pair-device`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "phone-1" }),
    });

    expect(response.status).toBe(200);
    expect(worker(1).signals).toEqual([undefined]);
    expect(workers.spawned).toHaveLength(3);
    expect(workers.spawned[2].options.env).toMatchObject({
      PHONE_ASSISTANT_BRIDGE_HOST: "192.168.1.2",
      PHONE_ASSISTANT_BRIDGE_PORT: "9001",
      PHONE_ASSISTANT_BRIDGE_TOKEN: "paired-token",
    });
    const saved = join(home, ".dhd", "companion-connection.json");
    expect(readFileSync(saved, "utf8")).toBe(
      `${JSON.stringify({ host: "192.168.1.2", port: 9001, token: "paired-token", deviceId: "phone-1" }, null, 2)}\n`,
    );
    if (process.platform !== "win32") expect(statSync(saved).mode & 0o777).toBe(0o600);
    expect(requestBridgeMock.mock.calls.map(([request]) => request.type)).toContain("status");
  });
});
