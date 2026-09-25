import { EventEmitter } from "node:events";

import { afterEach, describe, expect, it, vi } from "vitest";

const taskkills = vi.hoisted(() => [] as string[][]);

vi.mock("node:child_process", async () => {
  const actual = await vi.importActual<typeof import("node:child_process")>("node:child_process");
  return {
    ...actual,
    spawn: vi.fn((command: string, args: string[]) => {
      taskkills.push([command, ...args]);
      return new EventEmitter();
    }),
  };
});

const { CodexAppServerClient } = await import("../src/codex/app-server-client.js");
const { startFakeAppServer } = await import("./support/fake-app-server.js");

afterEach(() => {
  taskkills.length = 0;
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

describe("Codex App Server process cleanup", () => {
  it("kills the Windows shell and its Codex children when the App Server ignores stdin close", async () => {
    vi.spyOn(process, "platform", "get").mockReturnValue("win32");
    const server = startFakeAppServer();
    const client = new CodexAppServerClient({ spawnAppServer: server.spawn });
    await client.start();
    const release = server.hangOnShutdown();

    await client.close();

    expect(taskkills).toEqual([["taskkill", "/pid", "1000", "/T", "/F"]]);
    release();
  });
});
