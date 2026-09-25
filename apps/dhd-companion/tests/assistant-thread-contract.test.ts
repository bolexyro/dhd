import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { CodexAppServerClient } from "../src/assistant-companion.js";
import { JsonRpcFailure, startFakeAppServer, type FakeAppServer } from "./support/fake-app-server.js";

let server: FakeAppServer;
let client: CodexAppServerClient;

beforeEach(() => {
  server = startFakeAppServer();
  client = new CodexAppServerClient({ spawnAppServer: server.spawn });
  server.handle("turn/start", () => {
    queueMicrotask(() => server.notify("turn/completed", { turn: { status: "completed" } }));
    return { turn: { id: "turn-fresh" } };
  });
});

afterEach(async () => {
  await client.close();
  vi.unstubAllEnvs();
});

describe("DHD App Server thread contract", () => {
  it("resumes a stored thread on a new client", async () => {
    server.handle("thread/resume", () => ({ thread: { id: "legacy-thread" } }));

    await expect(
      client.runTurn("use the phone", "legacy-thread", undefined, undefined, "xhigh", true),
    ).resolves.toMatchObject({ threadId: "legacy-thread" });

    expect(server.methods()).toEqual(["initialize", "thread/resume", "turn/start"]);
    const resume = server.requests("thread/resume")[0].params ?? {};
    expect(resume.threadId).toBe("legacy-thread");
    const dynamicTools = resume.dynamicTools as Array<Record<string, unknown>>;
    expect(dynamicTools.map((tool) => tool.name)).toEqual([
      "dhd_list_allowed_apps",
      "dhd_browse_app",
      "dhd_set_app_display_layout",
      "dhd_list_displays",
      "dhd_close_display",
      "dhd_get_foreground_app",
      "dhd_observe",
      "dhd_open_app",
      "dhd_execute",
      "dhd_execute_sequence",
      "dhd_request_attention"
    ]);
    const turnStart = server.requests("turn/start")[0].params ?? {};
    expect(turnStart.effort).toBe("xhigh");
    expect(turnStart.serviceTier).toBe("priority");
  });

  it("starts a fresh thread only after stored-thread resume fails", async () => {
    server.handle("thread/resume", () => new JsonRpcFailure({ message: "thread was deleted" }));
    server.handle("thread/start", () => ({ thread: { id: "fresh-thread" } }));
    const readyThreadIds: string[] = [];

    await expect(
      client.runTurn(
        "use the phone",
        "deleted-thread",
        undefined,
        undefined,
        undefined,
        false,
        undefined,
        async (threadId) => {
          readyThreadIds.push(threadId);
        },
      ),
    ).resolves.toMatchObject({
      threadId: "fresh-thread",
    });

    expect(server.methods()).toEqual([
      "initialize",
      "thread/resume",
      "thread/start",
      "turn/start"
    ]);
    expect(readyThreadIds).toEqual(["fresh-thread"]);
  });

  it("starts a continuation turn with hidden continue input", async () => {
    server.handle("thread/resume", () => ({ thread: { id: "stopped-thread" } }));

    await expect(
      client.runTurn(
        "",
        "stopped-thread",
        undefined,
        undefined,
        "xhigh",
        false,
        undefined,
        undefined,
        true,
      ),
    ).resolves.toMatchObject({
      threadId: "stopped-thread",
    });

    expect(server.requests("turn/start")[0].params?.input).toEqual([
      { type: "text", text: "continue" },
    ]);
  });
});
