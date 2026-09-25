import type { AddressInfo } from "node:net";

import { afterEach, describe, expect, it, vi } from "vitest";

const requestBridgeMock = vi.hoisted(() => vi.fn());
const discoverPhonesMock = vi.hoisted(() => vi.fn());
const requestPairingApprovalMock = vi.hoisted(() => vi.fn());
const writeFileMock = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
const mkdirMock = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));

vi.mock("node:fs/promises", async () => {
  const actual = await vi.importActual<typeof import("node:fs/promises")>("node:fs/promises");
  return { ...actual, writeFile: writeFileMock, mkdir: mkdirMock };
});

vi.mock("../src/phone/pairing.js", async () => {
  const actual = await vi.importActual<typeof import("../src/phone/pairing.js")>("../src/phone/pairing.js");
  return { ...actual, discoverPhones: discoverPhonesMock, requestPairingApproval: requestPairingApprovalMock };
});

vi.mock("../src/phone/bridge-client.js", async () => {
  const actual = await vi.importActual<typeof import("../src/phone/bridge-client.js")>(
    "../src/phone/bridge-client.js"
  );
  return { ...actual, requestBridge: requestBridgeMock };
});

const { companionDashboard } = await import("../src/dashboard/server/dashboard.js");
const { createCompanionWebServer } = await import("../src/dashboard/server/routes.js");

const openServers: ReturnType<typeof createCompanionWebServer>[] = [];

afterEach(async () => {
  requestBridgeMock.mockReset();
  discoverPhonesMock.mockReset();
  requestPairingApprovalMock.mockReset();
  await Promise.all(
    openServers.splice(0).map(
      (server) =>
        new Promise<void>((resolve) => {
          if (!server.listening) {
            resolve();
            return;
          }
          server.close(() => resolve());
        })
    )
  );
});

async function openWebServer(): Promise<string> {
  const server = createCompanionWebServer(companionDashboard);
  openServers.push(server);
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const address = server.address() as AddressInfo;
  return `http://127.0.0.1:${address.port}`;
}

function connectedStatus() {
  return {
    type: "status",
    ok: true,
    state: "idle",
    active: false,
    companionConnected: true,
  };
}

describe("companion phone-link checks", () => {
  it("shares one in-flight probe across simultaneous callers", async () => {
    let calls = 0;
    requestBridgeMock.mockImplementation(async () => {
      calls += 1;
      await new Promise((resolve) => setTimeout(resolve, 25));
      return connectedStatus();
    });

    const baseUrl = await openWebServer();
    const [first, second] = await Promise.all([
      fetch(`${baseUrl}/api/check`, { method: "POST" }),
      fetch(`${baseUrl}/api/check`, { method: "POST" }),
    ]);

    expect(first.ok).toBe(true);
    expect(second.ok).toBe(true);
    expect(await first.json()).toMatchObject({ ok: true });
    expect(await second.json()).toMatchObject({ ok: true });
    expect(calls).toBe(1);
  });

  it("retries a dropped status probe on a fresh bridge request", async () => {
    requestBridgeMock
      .mockRejectedValueOnce(new Error("Could not connect to the phone assistant bridge."))
      .mockResolvedValueOnce(connectedStatus());

    const baseUrl = await openWebServer();
    const response = await fetch(`${baseUrl}/api/check`, { method: "POST" });

    expect(response.ok).toBe(true);
    expect(await response.json()).toMatchObject({ ok: true });
    expect(requestBridgeMock).toHaveBeenCalledTimes(2);
  });

  it("confirms a missed check before marking a connected phone offline", async () => {
    let now = 1_000_000;
    const dateNow = vi.spyOn(Date, "now").mockImplementation(() => now);
    try {
      const baseUrl = await openWebServer();
      requestBridgeMock.mockResolvedValueOnce(connectedStatus());

      await fetch(`${baseUrl}/api/check`, { method: "POST" });
      expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "connected" });

      requestBridgeMock.mockRejectedValue(new Error("temporary network miss"));
      now += 1_000;
      await fetch(`${baseUrl}/api/check`, { method: "POST" });
      expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "checking" });

      now += 1_000;
      await fetch(`${baseUrl}/api/check`, { method: "POST" });
      expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "checking" });

      now += 30_000;
      await fetch(`${baseUrl}/api/check`, { method: "POST" });
      expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "offline" });

      requestBridgeMock.mockResolvedValueOnce(connectedStatus());
      await fetch(`${baseUrl}/api/check`, { method: "POST" });
      expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "connected" });
    } finally {
      dateNow.mockRestore();
    }
  });

  it("reuses an existing phone pairing without asking for approval again", async () => {
    const baseUrl = await openWebServer();
    discoverPhonesMock.mockResolvedValue([{
      deviceId: "phone-1",
      deviceName: "Test phone",
      host: "192.168.1.2",
      addresses: ["192.168.1.2"],
      port: 8765,
    }]);
    requestPairingApprovalMock.mockResolvedValue({
      deviceId: "phone-1", host: "192.168.1.2", port: 8765, token: "saved-token",
    });
    requestBridgeMock.mockResolvedValue(connectedStatus());

    const first = await fetch(`${baseUrl}/api/pair-device`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "phone-1" }),
    });
    expect(first.ok).toBe(true);
    expect(requestPairingApprovalMock).toHaveBeenCalledTimes(1);

    const second = await fetch(`${baseUrl}/api/pair-device`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "phone-1" }),
    });
    expect(second.ok).toBe(true);
    expect(await second.json()).toMatchObject({ bridgeStatus: "connected" });
    expect(requestPairingApprovalMock).toHaveBeenCalledTimes(1);

    requestBridgeMock.mockRejectedValue(new Error("temporary network miss"));
    await fetch(`${baseUrl}/api/check`, { method: "POST" });
    requestBridgeMock.mockResolvedValue(connectedStatus());
    const reconnect = await fetch(`${baseUrl}/api/pair-device`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "phone-1" }),
    });
    expect(reconnect.ok).toBe(true);
    expect(await reconnect.json()).toMatchObject({ bridgeStatus: "connected" });
    expect(requestPairingApprovalMock).toHaveBeenCalledTimes(1);

    const replace = await fetch(`${baseUrl}/api/pair-device`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "phone-1", replacePairing: true }),
    });
    expect(replace.ok).toBe(true);
    expect(requestPairingApprovalMock).toHaveBeenCalledTimes(2);

    let signalProbeStarted!: () => void;
    const probeStarted = new Promise<void>((resolve) => { signalProbeStarted = resolve; });
    let rejectOldProbe!: (reason?: unknown) => void;
    const oldProbe = new Promise<never>((_resolve, reject) => { rejectOldProbe = reject; });
    requestBridgeMock
      .mockImplementationOnce(() => { signalProbeStarted(); return oldProbe; })
      .mockResolvedValueOnce(connectedStatus())
      .mockRejectedValueOnce(new Error("old probe failed"));

    const oldCheck = fetch(`${baseUrl}/api/check`, { method: "POST" });
    await probeStarted;
    const newerReconnect = await fetch(`${baseUrl}/api/pair-device`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "phone-1" }),
    });
    expect(newerReconnect.ok).toBe(true);
    rejectOldProbe(new Error("old probe failed"));
    expect(await (await oldCheck).json()).toMatchObject({ ok: true });
    expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "connected" });
  });

  it("waits for the phone to confirm worker presence before showing connected", async () => {
    const baseUrl = await openWebServer();
    requestBridgeMock.mockResolvedValue({ ...connectedStatus(), companionConnected: false });

    const waiting = await fetch(`${baseUrl}/api/check`, { method: "POST" });
    expect(await waiting.json()).toMatchObject({ ok: false });
    expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({
      bridgeStatus: "checking",
      phone: { companionConnected: false },
    });

    await fetch(`${baseUrl}/api/check`, { method: "POST" });
    await fetch(`${baseUrl}/api/check`, { method: "POST" });
    expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "offline" });

    requestBridgeMock.mockResolvedValueOnce(connectedStatus());
    await fetch(`${baseUrl}/api/check`, { method: "POST" });
    expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "connected" });
  });

  it("does not claim a connection when the phone omits worker presence", async () => {
    const baseUrl = await openWebServer();
    const { companionConnected: _omitted, ...statusWithoutPresence } = connectedStatus();
    requestBridgeMock.mockResolvedValue(statusWithoutPresence);

    await fetch(`${baseUrl}/api/check`, { method: "POST" });
    expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({
      bridgeStatus: "checking",
      phone: { state: "idle" },
    });
    await fetch(`${baseUrl}/api/check`, { method: "POST" });
    await fetch(`${baseUrl}/api/check`, { method: "POST" });
    expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "offline" });
  });

  it("treats an older reconnect as superseded when a newer one already reached the same phone", async () => {
    const baseUrl = await openWebServer();
    discoverPhonesMock.mockResolvedValue([{
      deviceId: "phone-race", deviceName: "Test phone", host: "192.168.1.2",
      addresses: ["192.168.1.2"], port: 8765,
    }]);
    requestPairingApprovalMock.mockResolvedValue({
      deviceId: "phone-race", host: "192.168.1.2", port: 8765, token: "saved-token",
    });
    requestBridgeMock.mockResolvedValue(connectedStatus());
    const initial = await fetch(`${baseUrl}/api/pair-device`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: "phone-race", replacePairing: true }),
    });
    expect(initial.ok).toBe(true);

    requestBridgeMock.mockRejectedValue(new Error("missed check"));
    await fetch(`${baseUrl}/api/check`, { method: "POST" });
    expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({ bridgeStatus: "checking" });

    discoverPhonesMock.mockResolvedValue([{
      deviceId: "phone-race", deviceName: "Test phone", host: "192.168.1.3",
      addresses: ["192.168.1.3"], port: 8765,
    }]);
    let signalFirstProbe!: () => void;
    const firstProbeStarted = new Promise<void>((resolve) => { signalFirstProbe = resolve; });
    let resolveFirstProbe!: (status: ReturnType<typeof connectedStatus>) => void;
    const firstProbe = new Promise<ReturnType<typeof connectedStatus>>((resolve) => { resolveFirstProbe = resolve; });
    requestBridgeMock.mockReset();
    requestBridgeMock
      .mockImplementationOnce(() => { signalFirstProbe(); return firstProbe; })
      .mockResolvedValueOnce(connectedStatus());

    const pairBody = JSON.stringify({ deviceId: "phone-race" });
    const oldReconnect = fetch(`${baseUrl}/api/pair-device`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: pairBody,
    });
    await firstProbeStarted;
    const newerReconnect = await fetch(`${baseUrl}/api/pair-device`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: pairBody,
    });
    expect(newerReconnect.ok).toBe(true);
    resolveFirstProbe(connectedStatus());
    expect((await oldReconnect).ok).toBe(true);
    expect(await (await fetch(`${baseUrl}/api/state`)).json()).toMatchObject({
      bridgeStatus: "connected",
      settings: { host: "192.168.1.3" },
    });
    expect(requestPairingApprovalMock).toHaveBeenCalledTimes(1);
  });
});
