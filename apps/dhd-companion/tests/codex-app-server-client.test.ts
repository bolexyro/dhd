import { writeFileSync } from "node:fs";
import { join } from "node:path";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { CodexAppServerClient } from "../src/codex/app-server-client.js";
import { setCompanionEventSink, type CompanionEvent } from "../src/shared/companion-events.js";
import {
  JsonRpcFailure,
  startFakeAppServer,
  type FakeAppServer,
} from "./support/fake-app-server.js";

let server: FakeAppServer;
let client: CodexAppServerClient;
let events: CompanionEvent[];
let errorLog: string[];

beforeEach(() => {
  vi.stubEnv("PHONE_ASSISTANT_CODEX_BIN", "");
  vi.stubEnv("PHONE_ASSISTANT_CODEX_MODEL", "");
  vi.stubEnv("PHONE_ASSISTANT_CODEX_REASONING_EFFORT", "");
  vi.stubEnv("PHONE_ASSISTANT_ENABLE_CODE_MODE_HOST", "");
  server = startFakeAppServer();
  client = new CodexAppServerClient({ spawnAppServer: server.spawn });
  events = [];
  setCompanionEventSink((event) => events.push(event));
  errorLog = [];
  vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
    errorLog.push(args.map(String).join(" "));
  });
});

afterEach(async () => {
  setCompanionEventSink(() => undefined);
  await client.close();
  vi.unstubAllEnvs();
});

function completeTurnWith(...notifications: Array<[string, Record<string, unknown>]>): void {
  server.handle("turn/start", () => {
    queueMicrotask(() => {
      for (const [method, params] of notifications) server.notify(method, params);
    });
    return { turn: { id: "turn-1" } };
  });
}

const completed: [string, Record<string, unknown>] = [
  "turn/completed",
  { turn: { id: "turn-1", status: "completed" } },
];

async function waitUntilSteerable(): Promise<void> {
  await vi.waitFor(() => expect(client.canSteer).toBe(true));
}

describe("Codex App Server process", () => {
  it("spawns the App Server over stdio with the isolated DHD configuration", async () => {
    vi.stubEnv("PHONE_ASSISTANT_CODEX_BIN", "codex");
    writeFileSync(
      join(server.codexHome, "config.toml"),
      ["[mcp_servers.zeta]", "  [mcp_servers.alpha.env]", "[mcp_servers.beta]", "[other]"].join("\n"),
    );

    await client.start();

    expect(server.spawns).toHaveLength(1);
    const [{ command, args, options }] = server.spawns;
    const expectedArgs = [
      "app-server",
      "--listen",
      "stdio://",
      ...[
        "mcp_servers={}",
        "features.apps=false",
        "features.browser_use=false",
        "features.computer_use=false",
        "features.goals=false",
        "features.hooks=false",
        "features.image_generation=false",
        "features.in_app_browser=false",
        "features.memories=false",
        "features.multi_agent=false",
        "features.plugins=false",
        "features.remote_plugin=false",
        "features.shell_snapshot=false",
        "features.shell_tool=false",
        "features.skill_mcp_dependency_install=false",
        "features.skill_search=false",
        "features.tool_suggest=false",
        "features.unified_exec=false",
        "features.view_image=false",
        "features.workspace_dependencies=false",
        "mcp_servers.alpha.enabled=false",
        "mcp_servers.beta.enabled=false",
        "mcp_servers.zeta.enabled=false",
      ].flatMap((override) => ["-c", override]),
      "--enable",
      "code_mode_host",
    ];
    expect(command).toBe(process.platform === "win32" ? `codex ${expectedArgs.join(" ")}` : "codex");
    expect(args).toEqual(process.platform === "win32" ? [] : expectedArgs);
    expect(options).toMatchObject({
      stdio: ["pipe", "pipe", "pipe"],
      cwd: server.runtimeCwd,
      shell: process.platform === "win32",
      windowsHide: true,
    });
    expect(options.env?.CODEX_HOME).toBe(server.codexHome);
  });

  it("disables the Code Mode host only for an explicit false", async () => {
    vi.stubEnv("PHONE_ASSISTANT_ENABLE_CODE_MODE_HOST", " FALSE ");

    await client.start();

    const invocation = process.platform === "win32"
      ? server.spawns[0].command
      : server.spawns[0].args.join(" ");
    expect(invocation.endsWith("--disable code_mode_host")).toBe(true);
  });

  it("uses a configured Codex binary", async () => {
    vi.stubEnv("PHONE_ASSISTANT_CODEX_BIN", "  /opt/codex/bin/codex  ");

    await client.start();

    if (process.platform === "win32") {
      expect(server.spawns[0].command).toMatch(/^\/opt\/codex\/bin\/codex app-server /);
    } else {
      expect(server.spawns[0].command).toBe("/opt/codex/bin/codex");
    }
  });

  it("initializes once with the DHD client identity and reuses the connection", async () => {
    await client.start();
    await client.start();

    expect(server.spawns).toHaveLength(1);
    expect(server.received.map((line) => JSON.stringify(line))).toEqual([
      JSON.stringify({
        method: "initialize",
        id: 1,
        params: {
          clientInfo: { name: "dhd-phone-assistant", title: "DHD phone assistant", version: "0.1.0" },
          capabilities: { experimentalApi: true },
        },
      }),
      JSON.stringify({ method: "initialized", params: {} }),
    ]);
  });

  it("shares one startup across concurrent callers", async () => {
    await Promise.all([client.start(), client.start()]);

    expect(server.spawns).toHaveLength(1);
    expect(server.requests("initialize")).toHaveLength(1);
  });

  it("forwards App Server stderr to the companion log", async () => {
    await client.start();
    server.writeStderr("  model warning  \n");

    await vi.waitFor(() => expect(errorLog).toContain("[codex-app-server] model warning"));
  });
});

describe("JSON-RPC responses", () => {
  it("rejects with the App Server error message", async () => {
    server.handle("thread/start", () => new JsonRpcFailure({ code: -32600, message: "thread quota exceeded" }));

    await expect(client.runTurn("open the store")).rejects.toThrow("thread quota exceeded");
  });

  it("falls back to a request-numbered error message", async () => {
    server.handle("thread/start", () => new JsonRpcFailure({ code: -32600 }));

    const rejection = expect(client.runTurn("open the store")).rejects.toThrow(
      /^Codex App Server request \d+ failed\.$/,
    );
    const threadStart = await server.nextRequest("thread/start");
    await rejection;
    expect(threadStart.id).toBe(2);
  });

  it("ignores non-JSON stdout and responses for unknown ids", async () => {
    await client.start();
    server.writeRaw("warming caches\n\n");
    server.writeLine({ id: 999, result: {} });
    completeTurnWith(completed);

    await expect(client.runTurn("open the store")).resolves.toMatchObject({ threadId: "thread-1" });
    expect(errorLog).toContain("[codex-app-server] ignored non-JSON stdout: warming caches");
  });

  it("restarts the App Server after it exits during a turn", async () => {
    server.handle("turn/start", () => {
      setImmediate(() => server.exit(1));
      return { turn: { id: "turn-1" } };
    });

    await expect(client.runTurn("open the store")).rejects.toThrow(
      "Codex App Server exited before completing the turn (code=1, signal=?).",
    );

    completeTurnWith(completed);
    await expect(client.runTurn("try again")).resolves.toMatchObject({ threadId: "thread-1" });
    expect(server.spawns).toHaveLength(2);
    expect(server.requests("initialize")).toHaveLength(2);
  });

  it("ignores a stale close from a replaced App Server child", async () => {
    completeTurnWith(["turn/completed", { turn: { id: "turn-1", status: "failed" } }]);
    const turnStart = server.nextRequest("turn/start");
    const firstTurn = client.runTurn("first request");
    await turnStart;
    const releaseOldChild = server.hangOnShutdown();
    await expect(firstTurn).rejects.toThrow("Codex App Server turn failed.");

    completeTurnWith();
    const secondTurn = client.runTurn("second request");
    await server.nextRequest("turn/start");
    releaseOldChild();
    await new Promise((resolve) => setTimeout(resolve, 20));
    server.notify(...completed);

    await expect(secondTurn).resolves.toMatchObject({ threadId: "thread-1" });
    expect(server.spawns).toHaveLength(2);
  });
});

describe("App Server requests", () => {
  const cases: Array<[string, Record<string, unknown>, Record<string, unknown>]> = [
    ["item/commandExecution/requestApproval", {}, { result: { decision: "decline" } }],
    ["item/fileChange/requestApproval", {}, { result: { decision: "decline" } }],
    [
      "item/tool/requestUserInput",
      { questions: [{ id: "q1" }, { id: "" }, {}, "q3", { id: "q4" }] },
      { result: { answers: { q1: { answers: [] }, q4: { answers: [] } } } },
    ],
    ["item/tool/requestUserInput", {}, { result: { answers: {} } }],
    [
      "item/permissions/requestApproval",
      {},
      { result: { permissions: { network: null, fileSystem: null }, scope: "turn" } },
    ],
    ["mcpServer/elicitation/request", {}, { result: { action: "decline", content: null } }],
    [
      "account/chatgptAuthTokens/refresh",
      {},
      { error: { code: -32001, message: "The phone companion does not manage ChatGPT auth token refresh." } },
    ],
    [
      "attestation/generate",
      {},
      { error: { code: -32001, message: "The phone companion does not provide upstream attestation." } },
    ],
    [
      "custom/unknown",
      {},
      { error: { code: -32601, message: "Unsupported App Server request: custom/unknown" } },
    ],
    [
      "item/tool/call",
      { tool: "other_tool", arguments: {} },
      {
        result: {
          contentItems: [
            { type: "inputText", text: JSON.stringify({ ok: false, message: "Unsupported dynamic phone tool: other_tool" }) },
          ],
          success: false,
        },
      },
    ],
    [
      "item/tool/call",
      { arguments: {} },
      {
        result: {
          contentItems: [
            { type: "inputText", text: JSON.stringify({ ok: false, message: "Unsupported dynamic phone tool: (missing tool name)" }) },
          ],
          success: false,
        },
      },
    ],
  ];

  it.each(cases)("answers %s with the pinned response shape", async (method, params, expected) => {
    await client.start();

    const response = await server.sendRequest("server-1", method, params);

    expect(JSON.stringify(response)).toBe(JSON.stringify({ id: "server-1", ...expected }));
  });

  it("resolves a namespaced DHD tool and reports invalid input as a failed tool result", async () => {
    await client.start();

    const response = await server.sendRequest(7, "item/tool/call", {
      tool: "functions.dhd_browse_app",
      arguments: JSON.stringify({ query: " " }),
    });

    expect(response.id).toBe(7);
    const result = response.result as { success: boolean; contentItems: Array<{ type: string; text: string }> };
    expect(result.success).toBe(false);
    expect(result.contentItems).toHaveLength(1);
    expect(JSON.parse(result.contentItems[0].text)).toMatchObject({
      ok: false,
      message: expect.stringContaining("Invalid phone assistant input"),
    });
    expect(events.map((event) => [event.type, "phase" in event ? event.phase : undefined])).toEqual([
      ["dhd_tool_call", "started"],
      ["dhd_tool_call", "completed"],
    ]);
  });

  it("does not crash when a server request arrives after the App Server has exited", async () => {
    await client.start();
    server.exit(0);
    await vi.waitFor(() => expect(server.running).toBe(false));
    await new Promise((resolve) => setImmediate(resolve));

    server.writeLine({ id: "late-request", method: "unsupported/server/request", params: {} });

    await vi.waitFor(() =>
      expect(errorLog).toContain(
        "[codex-app-server] could not send server-request error: Codex App Server is not running.",
      ),
    );
  });
});

describe("App Server notifications", () => {
  it("emits per-turn token usage with the active model and service tier", async () => {
    const usage = {
      threadId: "thread-1",
      turnId: "turn-1",
      tokenUsage: {
        last: {
          inputTokens: 10,
          cachedInputTokens: 4,
          outputTokens: 3,
          reasoningOutputTokens: 1,
          totalTokens: 13,
        },
        modelContextWindow: null,
      },
    };
    completeTurnWith(["thread/tokenUsage/updated", usage], completed);

    await client.runTurn("open the store", undefined, undefined, undefined, undefined, true);

    expect(events.filter((event) => event.type === "dhd_token_usage")).toEqual([
      {
        type: "dhd_token_usage",
        threadId: "thread-1",
        turnId: "turn-1",
        usage: usage.tokenUsage.last,
        modelContextWindow: null,
        timestamp: expect.any(Number),
        model: "gpt-6-luna",
        serviceTier: "priority",
      },
    ]);
  });

  it("resets the plan at turn start and emits plan updates for the active turn only", async () => {
    const turn = client.runTurn("open the store");
    await waitUntilSteerable();
    server.notify("turn/plan/updated", {
      turnId: "other-turn",
      plan: [{ step: "Ignored", status: "pending" }],
    });
    server.notify("turn/plan/updated", {
      turnId: "turn-1",
      explanation: "Checking out",
      plan: [
        { step: "Open the store", status: "completed" },
        { step: "Search", status: "inProgress" },
        { step: "Pay", status: "pending" },
      ],
    });
    server.notify(...completed);
    await turn;

    expect(events.filter((event) => event.type === "dhd_plan")).toEqual([
      { type: "dhd_plan", phase: "reset" },
      {
        type: "dhd_plan",
        phase: "updated",
        threadId: "thread-1",
        turnId: "turn-1",
        explanation: "Checking out",
        steps: [
          { step: "Open the store", status: "completed" },
          { step: "Search", status: "in_progress" },
          { step: "Pay", status: "pending" },
        ],
        timestamp: expect.any(Number),
      },
    ]);
  });

  it.each([
    [{ turn: { status: "failed", error: { message: "model overloaded" } } }, "model overloaded"],
    [{ turn: { status: "failed" } }, "Codex App Server turn failed."],
    [{ turn: { status: "interrupted" } }, "Codex App Server turn was interrupted."],
    [{ turn: { status: "paused" } }, "Codex App Server turn ended with unexpected status: paused."],
    [{ turn: {} }, "Codex App Server turn ended with unexpected status: unknown."],
  ])("rejects turn/completed %j", async (params, message) => {
    completeTurnWith(["turn/completed", params]);

    await expect(client.runTurn("open the store")).rejects.toThrow(message);
  });

  it.each([
    ["turn/failed", { error: { message: "rate limited" } }, "rate limited"],
    ["turn/failed", {}, "Codex App Server turn failed."],
    ["error", { error: { message: "stream disconnected" } }, "stream disconnected"],
    ["error", { message: "top-level message" }, "top-level message"],
  ])("rejects the turn on %s %j", async (method, params, message) => {
    completeTurnWith([method, params]);

    await expect(client.runTurn("open the store")).rejects.toThrow(message);
  });

  it("stops the App Server after a failed turn", async () => {
    completeTurnWith(["turn/failed", {}]);

    await expect(client.runTurn("open the store")).rejects.toThrow();

    expect(server.running).toBe(false);
  });

  it("falls back to notification text when no agent message was recorded", async () => {
    completeTurnWith(["turn/completed", { turn: { status: "completed" }, text: "Done from params" }]);

    await expect(client.runTurn("open the store")).resolves.toEqual({
      text: "Done from params",
      threadId: "thread-1",
      phoneToolFailures: [],
    });
  });

  it.each([
    ["thread/closed", { threadId: "thread-1" }],
    ["thread/status/changed", { threadId: "thread-1", status: { type: "notLoaded" } }],
  ])("resumes a thread after %s instead of reusing it", async (method, params) => {
    completeTurnWith(completed);
    await client.runTurn("first request");
    server.notify(method, params);

    await client.runTurn("second request", "thread-1");

    expect(server.methods()).toEqual([
      "initialize",
      "thread/start",
      "turn/start",
      "thread/resume",
      "turn/start",
    ]);
  });

  it("keeps reusing a thread after an unrelated status change", async () => {
    completeTurnWith(completed);
    await client.runTurn("first request");
    server.notify("thread/status/changed", { threadId: "thread-1", status: { type: "active" } });
    server.notify("thread/closed", { threadId: "other-thread" });

    await client.runTurn("second request", "thread-1");

    expect(server.methods()).toEqual(["initialize", "thread/start", "turn/start", "turn/start"]);
  });
});

describe("App Server turn control", () => {
  it("interrupts the active turn with its thread and turn ids", async () => {
    const turn = client.runTurn("open the store");
    const rejection = expect(turn).rejects.toThrow("Codex App Server turn was interrupted.");
    await waitUntilSteerable();

    await client.interrupt();
    server.notify("turn/completed", { turn: { id: "turn-1", status: "interrupted" } });

    await rejection;
    expect(server.requests("turn/interrupt").map((line) => line.params)).toEqual([
      { threadId: "thread-1", turnId: "turn-1" },
    ]);
  });

  it("does nothing when interrupted without an active turn", async () => {
    await client.start();

    await client.interrupt();

    expect(server.requests("turn/interrupt")).toEqual([]);
  });

  it("names a new thread from the trimmed title and tolerates naming failures", async () => {
    server.handle("thread/name/set", () => new JsonRpcFailure({ message: "not supported" }));
    completeTurnWith(completed);

    await expect(
      client.runTurn("open the store", undefined, `  ${"t".repeat(90)}  `),
    ).resolves.toMatchObject({ threadId: "thread-1" });

    expect(server.requests("thread/name/set").map((line) => line.params)).toEqual([
      { threadId: "thread-1", name: "t".repeat(80) },
    ]);
    expect(errorLog).toContain("[codex-app-server] could not name thread: not supported");
  });

  it("starts turns with the pinned model, effort, tier, and runtime directory", async () => {
    completeTurnWith(completed);

    await client.runTurn("open the store", undefined, undefined, undefined, " XHIGH ");

    expect(server.requests("thread/start")[0].params).toEqual({
      dynamicTools: expect.any(Array),
      model: "gpt-6-luna",
      cwd: server.runtimeCwd,
    });
    expect(JSON.stringify(server.requests("turn/start")[0].params)).toBe(
      JSON.stringify({
        threadId: "thread-1",
        model: "gpt-6-luna",
        effort: "xhigh",
        serviceTier: "default",
        cwd: server.runtimeCwd,
        input: [{ type: "text", text: "open the store" }],
      }),
    );
  });
});

describe("turn completion rejections", () => {
  async function unhandledRejectionsDuring(run: () => Promise<void>): Promise<unknown[]> {
    const reasons: unknown[] = [];
    const record = (reason: unknown) => reasons.push(reason);
    process.on("unhandledRejection", record);
    try {
      await run();
      await new Promise((resolve) => setTimeout(resolve, 20));
    } finally {
      process.off("unhandledRejection", record);
    }
    return reasons;
  }

  it("observes the completion when turn/start fails", async () => {
    server.handle("turn/start", () => new JsonRpcFailure({ message: "turn rejected" }));

    const reasons = await unhandledRejectionsDuring(async () => {
      await expect(client.runTurn("open the store")).rejects.toThrow("turn rejected");
    });

    expect(reasons).toEqual([]);
  });

  it("observes the completion when Stop arrives before turn/start", async () => {
    server.handle("thread/start", () => undefined);
    const reasons = await unhandledRejectionsDuring(async () => {
      const turn = client.runTurn("open the store");
      const threadStart = await server.nextRequest("thread/start");
      await client.interrupt();
      server.writeLine({ id: threadStart.id, result: { thread: { id: "thread-1" } } });
      await expect(turn).rejects.toThrow("Codex App Server turn was interrupted.");
    });

    expect(reasons).toEqual([]);
    expect(server.requests("turn/start")).toEqual([]);
  });

  it("observes the completion when the App Server exits during turn/start", async () => {
    server.handle("turn/start", () => {
      server.exit(1);
      return undefined;
    });

    const reasons = await unhandledRejectionsDuring(async () => {
      await expect(client.runTurn("open the store")).rejects.toThrow(
        "Codex App Server exited before completing the turn (code=1, signal=?).",
      );
    });

    expect(reasons).toEqual([]);
  });
});

describe("terminal turn notifications", () => {
  async function startTurn(): Promise<{ turn: ReturnType<CodexAppServerClient["runTurn"]> }> {
    const turn = client.runTurn("open the store");
    await waitUntilSteerable();
    return { turn };
  }

  it("keeps the turn running through a retryable error", async () => {
    const { turn } = await startTurn();
    server.notify("error", {
      threadId: "thread-1",
      turnId: "turn-1",
      willRetry: true,
      error: { message: "stream disconnected; retrying" },
    });
    server.notify(...completed);

    await expect(turn).resolves.toMatchObject({ threadId: "thread-1" });
    await vi.waitFor(() =>
      expect(errorLog).toContain("[codex-app-server] retrying after error: stream disconnected; retrying"),
    );
  });

  it.each([
    ["turn/completed", { threadId: "thread-1", turn: { id: "turn-old", status: "failed" } }],
    ["turn/completed", { threadId: "thread-other", turn: { id: "turn-1", status: "interrupted" } }],
    ["turn/failed", { threadId: "thread-1", turnId: "turn-old", error: { message: "old turn failed" } }],
    ["error", { threadId: "thread-other", turnId: "turn-1", willRetry: false, error: { message: "other thread" } }],
  ])("ignores %s for a different turn %j", async (method, params) => {
    const { turn } = await startTurn();
    server.notify(method, params);
    server.notify("turn/completed", { threadId: "thread-1", turn: { id: "turn-1", status: "completed" } });

    await expect(turn).resolves.toMatchObject({ threadId: "thread-1" });
  });

  it("still fails the turn on a final error for the active turn", async () => {
    const { turn } = await startTurn();
    const rejection = expect(turn).rejects.toThrow("quota exhausted");
    server.notify("error", {
      threadId: "thread-1",
      turnId: "turn-1",
      willRetry: false,
      error: { message: "quota exhausted" },
    });

    await rejection;
  });
});
