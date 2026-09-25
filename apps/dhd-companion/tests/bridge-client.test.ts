import { describe, expect, it, afterEach, vi } from "vitest";
import { Buffer } from "node:buffer";

const createConnectionMock = vi.hoisted(() => vi.fn());

vi.mock("node:net", () => ({
  default: {
    createConnection: createConnectionMock
  }
}));

import {
  bridgeConfigurationError,
  buildBridgePayload,
  isLoopbackBridgeHost,
  parsePort,
  requestBridge,
} from "../src/phone/bridge-client.js";
import type { BridgeRequest } from "../src/phone/protocol.js";

afterEach(() => {
  vi.useRealTimers();
  createConnectionMock.mockReset();
});

describe("phone assistant bridge configuration", () => {
  it("recognizes loopback hosts, including IPv6 loopback", () => {
    expect(isLoopbackBridgeHost("127.0.0.1")).toBe(true);
    expect(isLoopbackBridgeHost("LOCALHOST")).toBe(true);
    expect(isLoopbackBridgeHost("[::1]")).toBe(true);
    expect(isLoopbackBridgeHost("192.168.1.42")).toBe(false);
  });

  it("requires a token for every bridge target, including loopback", () => {
    expect(bridgeConfigurationError("127.0.0.1", "")).toContain("TOKEN");
    expect(bridgeConfigurationError("192.168.1.42", "  ")).toContain("TOKEN");
    expect(bridgeConfigurationError("127.0.0.1", "paired-token")).toBeNull();
    expect(bridgeConfigurationError("192.168.1.42", "paired-token")).toBeNull();
  });

  it("adds a trimmed token without changing the typed request", () => {
    const request: BridgeRequest = { type: "status", requestId: "request-1" };

    expect(buildBridgePayload(request, "  paired-token  ")).toEqual({
      type: "status",
      requestId: "request-1",
      authToken: "paired-token",
    });
    expect(buildBridgePayload(request, undefined)).toEqual(request);
  });

  it("keeps bridge ports within the TCP port range", () => {
    expect(parsePort("8765")).toBe(8765);
    expect(() => parsePort("0")).toThrow();
    expect(() => parsePort("65536")).toThrow();
  });

  it("times out when a TCP connection never reaches the phone", async () => {
    vi.useFakeTimers();
    const socket = {
      destroy: vi.fn(),
      on: vi.fn(),
      once: vi.fn(),
      setTimeout: vi.fn()
    };
    createConnectionMock.mockReturnValue(socket);

    const result = requestBridge(
      { type: "status", requestId: "request-timeout" },
      { host: "127.0.0.1", port: 8765, token: "paired-token", timeoutMs: 100 }
    );
    const rejection = expect(result).rejects.toThrow("Timed out waiting for the phone assistant bridge.");

    await vi.advanceTimersByTimeAsync(100);
    await rejection;
    expect(socket.destroy).toHaveBeenCalledTimes(1);
  });

  it("keeps an accepted user-dependent request open without removing the initial timeout", async () => {
    vi.useFakeTimers();
    const listeners = new Map<string, (...args: unknown[]) => void>();
    const socket = {
      destroy: vi.fn(),
      on: vi.fn((event: string, handler: (...args: unknown[]) => void) => {
        listeners.set(event, handler);
        return socket;
      }),
      once: vi.fn((event: string, handler: (...args: unknown[]) => void) => {
        listeners.set(event, handler);
        return socket;
      }),
      setTimeout: vi.fn(),
      write: vi.fn(),
    };
    createConnectionMock.mockReturnValue(socket);

    const result = requestBridge(
      { type: "execute_action", requestId: "request-phone-access" },
      {
        host: "127.0.0.1",
        port: 8765,
        token: "paired-token",
        timeoutMs: 100,
        keepOpenAfterAccepted: true,
      },
    );
    listeners.get("connect")?.();
    listeners.get("data")?.(Buffer.from('{"type":"accepted"}\n'));

    let settled = false;
    void result.then(
      () => { settled = true; },
      () => { settled = true; },
    );
    await vi.advanceTimersByTimeAsync(100);

    expect(settled).toBe(false);
    expect(socket.setTimeout).toHaveBeenCalledWith(100, expect.any(Function));
    expect(socket.setTimeout).toHaveBeenCalledWith(0);

    listeners.get("data")?.(Buffer.from('{"type":"completed","ok":true}\n'));
    await expect(result).resolves.toMatchObject({ type: "completed", ok: true });
  });
});
