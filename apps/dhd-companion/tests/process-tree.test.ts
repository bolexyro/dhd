import { EventEmitter } from "node:events";
import type { ChildProcess } from "node:child_process";

import { afterEach, describe, expect, it, vi } from "vitest";

const spawned = vi.hoisted(() => [] as Array<{ command: string; args: string[] }>);

vi.mock("node:child_process", async () => {
  const actual = await vi.importActual<typeof import("node:child_process")>("node:child_process");
  return {
    ...actual,
    spawn: vi.fn((command: string, args: string[]) => {
      spawned.push({ command, args });
      return new EventEmitter();
    }),
  };
});

const { killProcessTree } = await import("../src/shared/process-tree.js");

function fakeChild(pid: number | undefined) {
  const signals: Array<NodeJS.Signals | undefined> = [];
  return {
    child: { pid, kill: (signal?: NodeJS.Signals) => { signals.push(signal); return true; } } as unknown as ChildProcess,
    signals,
  };
}

afterEach(() => {
  spawned.length = 0;
  vi.restoreAllMocks();
});

describe("killProcessTree", () => {
  it("signals the child directly outside Windows", () => {
    vi.spyOn(process, "platform", "get").mockReturnValue("linux");
    const { child, signals } = fakeChild(42);

    killProcessTree(child, "SIGKILL");

    expect(signals).toEqual(["SIGKILL"]);
    expect(spawned).toEqual([]);
  });

  it("kills the whole tree with taskkill on Windows", () => {
    vi.spyOn(process, "platform", "get").mockReturnValue("win32");
    const { child, signals } = fakeChild(42);

    killProcessTree(child);

    expect(spawned).toEqual([{ command: "taskkill", args: ["/pid", "42", "/T", "/F"] }]);
    expect(signals).toEqual([]);
  });
});
