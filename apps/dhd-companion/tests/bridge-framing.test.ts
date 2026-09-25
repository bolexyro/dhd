import { EventEmitter } from "node:events";

import { afterEach, describe, expect, it, vi } from "vitest";

class FakeSocket extends EventEmitter {
  readonly written: string[] = [];
  destroyed = false;
  inactivityTimeoutMs: number | undefined;

  write(data: string): boolean {
    this.written.push(data);
    return true;
  }

  destroy(): this {
    this.destroyed = true;
    return this;
  }

  setTimeout(timeoutMs: number, onTimeout?: () => void): this {
    this.inactivityTimeoutMs = timeoutMs;
    if (onTimeout) this.once("timeout", onTimeout);
    return this;
  }

  receive(...chunks: Array<string | Buffer>): void {
    for (const chunk of chunks) this.emit("data", typeof chunk === "string" ? Buffer.from(chunk) : chunk);
  }
}

const sockets = vi.hoisted(() => ({ created: [] as Array<{ options: unknown; socket: unknown }> }));

vi.mock("node:net", () => ({
  default: {
    createConnection: vi.fn((options: unknown) => {
      const socket = new FakeSocket();
      sockets.created.push({ options, socket });
      return socket;
    }),
  },
}));

const { requestBridge } = await import("../src/phone/bridge-client.js");

afterEach(() => {
  vi.useRealTimers();
  sockets.created.length = 0;
});

function connect(
  options: Parameters<typeof requestBridge>[1] = {},
): { result: ReturnType<typeof requestBridge>; socket: FakeSocket } {
  const result = requestBridge(
    { type: "status", requestId: "request-1" },
    { host: "127.0.0.1", port: 8765, token: "paired-token", ...options },
  );
  const socket = sockets.created.at(-1)?.socket as FakeSocket;
  socket.emit("connect");
  return { result, socket };
}

describe("phone bridge NDJSON framing", () => {
  it("connects to the requested target and writes one request line", async () => {
    const result = requestBridge(
      { type: "status", requestId: "request-1" },
      { host: "192.168.1.2", port: 9000, token: "  paired-token  " },
    );
    const socket = sockets.created[0].socket as FakeSocket;
    socket.emit("connect");
    socket.receive('{"type":"status","ok":true}\n');

    await expect(result).resolves.toEqual({ type: "status", ok: true });
    expect(sockets.created[0].options).toEqual({ host: "192.168.1.2", port: 9000 });
    expect(socket.written).toEqual(['{"type":"status","requestId":"request-1","authToken":"paired-token"}\n']);
    expect(socket.destroyed).toBe(true);
  });

  it("rejects a target without a token before connecting, including loopback", async () => {
    await expect(
      requestBridge({ type: "status", requestId: "request-1" }, { host: "192.168.1.2", port: 9000, token: " " }),
    ).rejects.toThrow("PHONE_ASSISTANT_BRIDGE_TOKEN is required to reach the phone at 192.168.1.2.");
    await expect(
      requestBridge({ type: "status", requestId: "request-2" }, { host: "127.0.0.1", port: 8765, token: "" }),
    ).rejects.toThrow("PHONE_ASSISTANT_BRIDGE_TOKEN is required to reach the phone at 127.0.0.1.");
    expect(sockets.created).toEqual([]);
  });

  it("reads several lines from one chunk and resolves on the first terminal line", async () => {
    const { result, socket } = connect();
    socket.receive('{"type":"accepted"}\n\n{"type":"completed","ok":true,"n":1}\n{"type":"completed","n":2}\n');

    await expect(result).resolves.toEqual({ type: "completed", ok: true, n: 1 });
  });

  it("joins a line split across chunks", async () => {
    const { result, socket } = connect();
    socket.receive('{"type":"obser', 'vation","ok":true}', "\n");

    await expect(result).resolves.toEqual({ type: "observation", ok: true });
  });

  it("keeps a multibyte character that is split across chunks", async () => {
    const { result, socket } = connect();
    const line = Buffer.from('{"type":"completed","ok":true,"text":"caf\u00e9 \ud83d\ude42"}\n', "utf8");
    const emojiStart = line.indexOf(Buffer.from("\ud83d\ude42", "utf8"));
    socket.receive(line.subarray(0, emojiStart + 2), line.subarray(emojiStart + 2));

    await expect(result).resolves.toEqual({ type: "completed", ok: true, text: "caf\u00e9 \ud83d\ude42" });
  });

  it("ignores unknown non-terminal message types until a terminal line arrives", async () => {
    const { result, socket } = connect();
    let settled = false;
    void result.then(() => { settled = true; }, () => { settled = true; });
    socket.receive('{"type":"progress","step":1}\n', '{"ok":true}\n', '"just a string"\n');
    await Promise.resolve();
    expect(settled).toBe(false);

    socket.receive('{"type":"stopped","ok":true}\n');
    await expect(result).resolves.toEqual({ type: "stopped", ok: true });
  });

  it("rejects invalid JSON lines", async () => {
    const { result, socket } = connect();
    socket.receive("{not json}\n");

    await expect(result).rejects.toThrow("The phone assistant bridge returned invalid JSON.");
    expect(socket.destroyed).toBe(true);
  });

  it("rejects responses larger than 16 MiB", async () => {
    const { result, socket } = connect();
    socket.receive(Buffer.alloc(8 * 1024 * 1024, 0x20), Buffer.alloc(8 * 1024 * 1024 + 1, 0x20));

    await expect(result).rejects.toThrow("The phone assistant bridge response is too large.");
    expect(socket.destroyed).toBe(true);
  });

  it("rejects when the phone closes before a terminal line", async () => {
    const { result, socket } = connect();
    socket.receive('{"type":"completed","ok":true}');
    socket.emit("close");

    await expect(result).rejects.toThrow("The phone assistant bridge closed before completing the request.");
  });

  it("reports connection errors with the target address", async () => {
    const { result, socket } = connect();
    socket.emit("error", new Error("connect ECONNREFUSED"));

    await expect(result).rejects.toThrow(
      "Could not connect to the phone assistant bridge at 127.0.0.1:8765: connect ECONNREFUSED",
    );
  });

  it("keeps the timeout after acceptance unless the request may wait for the user", async () => {
    vi.useFakeTimers();
    const { result, socket } = connect({ timeoutMs: 100 });
    const rejection = expect(result).rejects.toThrow("Timed out waiting for the phone assistant bridge.");
    socket.receive('{"type":"accepted"}\n');

    await vi.advanceTimersByTimeAsync(100);
    await rejection;
    expect(socket.inactivityTimeoutMs).toBe(100);
  });

  it("bounds an accepted request by the post-acceptance timeout", async () => {
    vi.useFakeTimers();
    const { result, socket } = connect({ timeoutMs: 100, acceptedTimeoutMs: 1_000 });
    let settled = false;
    const rejection = expect(result).rejects.toThrow("Timed out waiting for the phone assistant bridge.");
    void result.then(() => { settled = true; }, () => { settled = true; });
    socket.receive('{"type":"accepted"}\n');

    await vi.advanceTimersByTimeAsync(999);
    expect(settled).toBe(false);
    expect(socket.inactivityTimeoutMs).toBe(1_000);
    await vi.advanceTimersByTimeAsync(1);
    await rejection;
  });

  it("logs unexpected message types without extending the timeout", async () => {
    vi.useFakeTimers();
    const errors: string[] = [];
    vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
      errors.push(args.map(String).join(" "));
    });
    const { result, socket } = connect({ timeoutMs: 100 });
    const rejection = expect(result).rejects.toThrow("Timed out waiting for the phone assistant bridge.");
    socket.receive('{"type":"progress","step":1}\n', '{"ok":true}\n');

    await vi.advanceTimersByTimeAsync(100);
    await rejection;
    expect(errors).toEqual([
      "[phone-assistant-bridge] ignored unexpected bridge message type: progress",
      "[phone-assistant-bridge] ignored unexpected bridge message type: (missing)",
    ]);
  });

  it("times out on socket inactivity", async () => {
    const { result, socket } = connect({ timeoutMs: 60_000 });
    socket.emit("timeout");

    await expect(result).rejects.toThrow("Timed out waiting for the phone assistant bridge.");
  });

  it("disables timeouts entirely for a zero timeout", async () => {
    const { result, socket } = connect({ timeoutMs: 0 });

    expect(socket.inactivityTimeoutMs).toBeUndefined();
    socket.receive('{"type":"heartbeat","ok":true}\n');
    await expect(result).resolves.toEqual({ type: "heartbeat", ok: true });
  });
});
