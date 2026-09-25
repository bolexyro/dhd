import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";

import { afterEach, describe, expect, it, vi } from "vitest";

class StubbornWorker extends EventEmitter {
  readonly stdout = new PassThrough();
  readonly stderr = new PassThrough();
  readonly signals: Array<NodeJS.Signals | undefined> = [];
  readonly pid = 4242;
  killed = false;
  connected = true;
  disconnects = 0;

  kill(signal?: NodeJS.Signals): boolean {
    this.signals.push(signal);
    return true;
  }

  disconnect(): void {
    this.disconnects += 1;
    this.connected = false;
  }
}

const spawned = vi.hoisted(() => [] as Array<{ command: string; args: string[]; child: unknown }>);

vi.mock("node:child_process", async () => {
  const actual = await vi.importActual<typeof import("node:child_process")>("node:child_process");
  return {
    ...actual,
    spawn: vi.fn((command: string, args: string[]) => {
      const child = command === "taskkill" ? new EventEmitter() : new StubbornWorker();
      spawned.push({ command, args, child });
      return child;
    }),
  };
});

const { CompanionDashboard } = await import("../src/dashboard/server/dashboard.js");

afterEach(() => {
  spawned.length = 0;
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function startedWorker(): { dashboard: InstanceType<typeof CompanionDashboard>; worker: StubbornWorker } {
  const dashboard = new CompanionDashboard();
  dashboard.state.connection = { host: "127.0.0.1", port: 8765, token: "" };
  dashboard.supervisor.start();
  return { dashboard, worker: spawned[0].child as StubbornWorker };
}

describe("worker supervisor shutdown", () => {
  it("gives the worker longer than its own shutdown deadline before force killing it", async () => {
    vi.spyOn(process, "platform", "get").mockReturnValue("darwin");
    vi.useFakeTimers();
    const { dashboard, worker } = startedWorker();

    const stopped = dashboard.supervisor.stop("test");
    await vi.advanceTimersByTimeAsync(0);
    expect(worker.signals).toEqual([undefined]);

    await vi.advanceTimersByTimeAsync(10_000);
    expect(worker.signals).toEqual([undefined]);

    await vi.advanceTimersByTimeAsync(5_000);
    expect(worker.signals).toEqual([undefined, "SIGKILL"]);
    await expect(stopped).resolves.toBeDefined();
  });

  it("asks a Windows worker to stop over IPC and kills its process tree after the grace period", async () => {
    vi.spyOn(process, "platform", "get").mockReturnValue("win32");
    vi.useFakeTimers();
    const { dashboard, worker } = startedWorker();

    const stopped = dashboard.supervisor.stop("test");
    await vi.advanceTimersByTimeAsync(0);
    expect(worker.disconnects).toBe(1);
    expect(worker.signals).toEqual([]);

    await vi.advanceTimersByTimeAsync(15_000);
    expect(spawned.slice(1).map(({ command, args }) => [command, ...args])).toEqual([
      ["taskkill", "/pid", "4242", "/T", "/F"],
    ]);
    await expect(stopped).resolves.toBeDefined();
  });
});
