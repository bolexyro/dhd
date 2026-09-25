import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type {
  CompanionTokenUsageEvent,
  CompanionToolCallEvent
} from "../src/companion-events.js";
import {
  CodexAppServerClient,
  extractCompanionTokenUsageEvent,
  handleDynamicToolCall,
  shouldInterruptForPhoneStop,
} from "../src/assistant-companion.js";
import { startFakeAppServer, type FakeAppServer } from "./support/fake-app-server.js";

let server: FakeAppServer;
let client: CodexAppServerClient;

beforeEach(() => {
  server = startFakeAppServer();
  client = new CodexAppServerClient({ spawnAppServer: server.spawn });
});

afterEach(async () => {
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

describe("Codex App Server agent-message extraction", () => {
  it("extracts the latest per-turn token usage without cumulative thread totals", () => {
    const event = extractCompanionTokenUsageEvent(
      {
        method: "thread/tokenUsage/updated",
        params: {
          threadId: "thread-usage",
          turnId: "turn-usage",
          tokenUsage: {
            last: {
              inputTokens: 1200,
              cachedInputTokens: 800,
              outputTokens: 240,
              reasoningOutputTokens: 90,
              totalTokens: 1440,
            },
            total: {
              inputTokens: 9000,
              cachedInputTokens: 6400,
              outputTokens: 1200,
              reasoningOutputTokens: 500,
              totalTokens: 10200,
            },
            modelContextWindow: 258400,
          },
        },
      },
      1234,
    );

    expect(event).toEqual<CompanionTokenUsageEvent>({
      type: "dhd_token_usage",
      threadId: "thread-usage",
      turnId: "turn-usage",
      usage: {
        inputTokens: 1200,
        cachedInputTokens: 800,
        outputTokens: 240,
        reasoningOutputTokens: 90,
        totalTokens: 1440,
      },
      modelContextWindow: 258400,
      timestamp: 1234,
    });
    expect(event).not.toHaveProperty("threadTokenUsage");
  });

  it("uses the final answer instead of concatenating commentary from the same turn", async () => {
    const streamed: Array<{ itemId: string; text: string }> = [];
    completeTurnWith(
      ["item/started", { item: { id: "commentary-1", type: "agentMessage", phase: "commentary" } }],
      ["item/agentMessage/delta", { itemId: "commentary-1", delta: "I’ll configure the benchmark first. " }],
      [
        "item/completed",
        {
          item: {
            id: "commentary-1",
            type: "agentMessage",
            phase: "commentary",
            text: "I’ll configure the benchmark first.",
          },
        },
      ],
      ["item/started", { item: { id: "final-1", type: "agentMessage", phase: "final_answer" } }],
      [
        "item/agentMessage/delta",
        { itemId: "final-1", delta: "The run failed on round 4; I requested your attention." },
      ],
      [
        "item/completed",
        {
          item: {
            id: "final-1",
            type: "agentMessage",
            phase: "final_answer",
            text: "The run failed on round 4; I requested your attention.",
          },
        },
      ],
      ["turn/completed", { turn: { status: "completed" } }],
    );

    const result = await client.runTurn(
      "run the benchmark",
      undefined,
      undefined,
      undefined,
      undefined,
      false,
      (update) => streamed.push(update),
    );

    expect(result).toEqual({
      text: "The run failed on round 4; I requested your attention.",
      threadId: "thread-1",
      phoneToolFailures: [],
    });
    expect(streamed).toEqual([
      { itemId: "final-1", text: "The run failed on round 4; I requested your attention." },
      { itemId: "final-1", text: "The run failed on round 4; I requested your attention." },
    ]);
  });

  it("retains a failed dynamic phone tool when the App Server turn completes", async () => {
    server.handle("turn/start", () => {
      queueMicrotask(async () => {
        await server.sendRequest("tool-1", "item/tool/call", {
          tool: "unsupported_phone_tool",
          arguments: {},
        });
        server.notify("turn/completed", { turn: { status: "completed" } });
      });
      return { turn: { id: "turn-1" } };
    });

    await expect(client.runTurn("use the phone")).resolves.toMatchObject({
      threadId: "thread-1",
      phoneToolFailures: [{
        tool: "unsupported_phone_tool",
        message: "Unsupported dynamic phone tool: unsupported_phone_tool",
      }],
    });
  });

  it("does not interpret a pending attention request as a phone stop", () => {
    expect(shouldInterruptForPhoneStop({ active: false, attentionPending: true })).toBe(false);
    expect(shouldInterruptForPhoneStop({ active: false })).toBe(true);
    expect(shouldInterruptForPhoneStop({ active: true, attentionPending: true })).toBe(false);
  });

  it("emits a complete diagnostic event with normalized arguments and images", async () => {
    const events: CompanionToolCallEvent[] = [];
    const imageData = Buffer.from("test-image").toString("base64");
    const response = await handleDynamicToolCall(
      { tool: "dhd_observe", arguments: "{}" },
      {
        emit: (event) => events.push(event),
        invoke: async (_name, input) => {
          expect(input).toEqual({});
          return {
            content: [
              { type: "text", text: '{"ok":true}' },
              { type: "image", data: imageData, mimeType: "image/png" },
            ],
            structuredContent: { ok: true },
          };
        },
      },
    );

    expect(response).toEqual({
      contentItems: [
        { type: "inputText", text: '{"ok":true}' },
        { type: "inputImage", imageUrl: `data:image/png;base64,${imageData}` },
      ],
      success: true,
    });
    expect(events).toHaveLength(2);
    expect(events[0]).toMatchObject({
      type: "dhd_tool_call",
      phase: "started",
      tool: "dhd_observe",
      arguments: {},
    });
    expect(events[1]).toMatchObject({
      type: "dhd_tool_call",
      phase: "completed",
      callId: events[0].callId,
      tool: "dhd_observe",
      result: {
        structuredContent: { ok: true },
        content: [
          { type: "text", text: '{"ok":true}' },
          { type: "image", data: imageData, mimeType: "image/png" },
        ],
      },
    });
  });

  it("emits raw invalid arguments and thrown tool errors without changing the error path", async () => {
    const events: CompanionToolCallEvent[] = [];
    await expect(
      handleDynamicToolCall(
        { tool: "dhd_observe", arguments: "not-json" },
        {
          emit: (event) => events.push(event),
          invoke: async (_name, input) => {
            expect(input).toEqual({ __invalidArguments: "not-json" });
            throw new Error("bridge unavailable");
          },
        },
      ),
    ).rejects.toThrow("bridge unavailable");

    expect(events).toHaveLength(2);
    expect(events[0]).toMatchObject({
      phase: "started",
      rawArguments: "not-json",
      arguments: { __invalidArguments: "not-json" },
    });
    expect(events[1]).toMatchObject({
      phase: "completed",
      callId: events[0].callId,
      error: "bridge unavailable",
    });
  });

  it("initializes once and reuses a loaded thread across turns", async () => {
    const timingLogs: string[] = [];
    vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
      const line = args.map(String).join(" ");
      if (line.includes("[dhd-timing]")) timingLogs.push(line);
    });
    let threadStarts = 0;
    server.handle("thread/start", () => {
      threadStarts += 1;
      return { thread: { id: threadStarts === 1 ? "thread-loaded" : "thread-new" } };
    });
    let turnStarts = 0;
    server.handle("turn/start", () => {
      turnStarts += 1;
      const turnId = `turn-${turnStarts}`;
      queueMicrotask(() => {
        server.notify("turn/started", { turn: { id: turnId } });
        server.notify("item/started", { item: { id: "user-message", type: "userMessage" } });
        server.notify("turn/completed", { turn: { status: "completed" } });
      });
      return { turn: { id: "turn-response" } };
    });

    await expect(client.runTurn("hi")).resolves.toMatchObject({ threadId: "thread-loaded" });
    await expect(client.runTurn("second request", "thread-loaded")).resolves.toMatchObject({
      threadId: "thread-loaded",
    });
    await expect(client.runTurn("rotated request")).resolves.toMatchObject({ threadId: "thread-new" });

    expect(server.methods()).toEqual([
      "initialize",
      "thread/start",
      "turn/start",
      "turn/start",
      "thread/unsubscribe",
      "thread/start",
      "turn/start",
    ]);
    expect(server.requests("thread/unsubscribe").map((line) => line.params)).toEqual([
      { threadId: "thread-loaded" },
    ]);
    expect(server.requests("turn/start").map((line) => line.params?.input)).toEqual([
      [{ type: "text", text: "hi" }],
      [{ type: "text", text: "second request" }],
      [{ type: "text", text: "rotated request" }],
    ]);
    expect(server.requests("turn/start").map((line) => line.params?.serviceTier)).toEqual([
      "default",
      "default",
      "default",
    ]);
    expect(timingLogs.some((line) => line.includes("phase=turn/started"))).toBe(true);
    expect(timingLogs.some((line) => line.includes("phase=userMessage"))).toBe(true);
  });
});

describe("Codex App Server turn steering", () => {
  it("sends steer input to the active turn and preserves its expected turn id", async () => {
    const turn = client.runTurn("open the store");
    await vi.waitFor(() => expect(client.canSteer).toBe(true));

    await client.steer("  Actually stop after verifying the current screen.  ");
    server.notify("turn/completed", { turn: { status: "completed" } });
    await turn;

    expect(server.requests("turn/steer").map((line) => line.params)).toEqual([{
      threadId: "thread-1",
      input: [{ type: "text", text: "Actually stop after verifying the current screen." }],
      expectedTurnId: "turn-1",
    }]);
  });

  it("rejects a steer accepted for a different turn", async () => {
    server.handle("turn/steer", () => ({ turnId: "turn-other" }));
    const turn = client.runTurn("open the store");
    await vi.waitFor(() => expect(client.canSteer).toBe(true));

    await expect(client.steer("Continue")).rejects.toThrow(
      "Codex accepted the steer for unexpected turn turn-other.",
    );
    server.notify("turn/completed", { turn: { status: "completed" } });
    await turn;
  });

  it("rejects an empty steer instruction", async () => {
    await expect(client.steer("   ")).rejects.toThrow("A steer instruction is required.");
  });

  it("rejects steering when the App Server turn is no longer active", async () => {
    await expect(client.steer("Continue")).rejects.toThrow("no active turn");
  });
});
