import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

type BridgeResponse = Record<string, unknown>;
type BridgeResponder = (request: Record<string, unknown>) => BridgeResponse | Promise<BridgeResponse>;

const bridge = vi.hoisted(() => ({
  responders: new Map<string, BridgeResponder>(),
  calls: [] as Array<{ request: Record<string, unknown>; options: unknown }>,
}));

vi.mock("../src/phone/bridge-client.js", async () => {
  const actual = await vi.importActual<typeof import("../src/phone/bridge-client.js")>(
    "../src/phone/bridge-client.js",
  );
  return {
    ...actual,
    requestBridge: vi.fn(async (request: Record<string, unknown>, options?: unknown) => {
      bridge.calls.push({ request, options });
      const responder = bridge.responders.get(String(request.type));
      if (!responder) throw new Error(`Unexpected bridge request ${String(request.type)}`);
      return responder(request);
    }),
  };
});

const { processPendingRequest, processPendingSteer } = await import("../src/assistant-companion.js");

type RunTurnArgs = [
  phoneRequest: string,
  existingThreadId: string | undefined,
  threadTitle: string | undefined,
  timing: unknown,
  reasoningEffort: string | undefined,
  fastMode: boolean,
  onAgentMessageDelta: (update: { itemId: string; text: string }) => void,
  onThreadReady: (threadId: string) => Promise<void>,
  isContinuation: boolean,
];

interface FakeCodexOptions {
  turn?: (...args: RunTurnArgs) => Promise<{ text: string; threadId: string; phoneToolFailures: unknown[] }>;
  isTurnInFlight?: boolean;
  canSteer?: boolean;
  steer?: (text: string) => Promise<void>;
}

function fakeCodex(options: FakeCodexOptions = {}) {
  return {
    isTurnInFlight: options.isTurnInFlight ?? true,
    canSteer: options.canSteer ?? true,
    runTurn: vi.fn(
      options.turn ??
        (async (..._args: RunTurnArgs) => ({ text: "Done.", threadId: "thread-1", phoneToolFailures: [] })),
    ),
    interrupt: vi.fn(async () => undefined),
    steer: vi.fn(options.steer ?? (async () => undefined)),
  };
}

function respond(type: string, response: BridgeResponse | BridgeResponder): void {
  bridge.responders.set(type, typeof response === "function" ? response : () => response);
}

function sentRequests(): string[] {
  return bridge.calls.map(({ request }) => JSON.stringify({ ...request, requestId: "<id>" }));
}

let errorLog: string[];

beforeEach(() => {
  bridge.responders.clear();
  bridge.calls.length = 0;
  errorLog = [];
  vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
    errorLog.push(args.map(String).join(" "));
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

const companionErrors = () => errorLog.filter((line) => line.startsWith("[phone-assistant-companion]"));

describe("phone request runner", () => {
  it("claims, binds the new thread, streams the final answer, and completes the session", async () => {
    respond("claim_request", {
      ok: true,
      request: "Order iced tea",
      conversationId: "conversation-1",
      title: "Iced tea",
      reasoningEffort: "xhigh",
      fastMode: true,
    });
    respond("bind_codex_thread", { ok: true });
    respond("stream_agent_message", { ok: true });
    respond("complete_session", { ok: true });
    const codex = fakeCodex({
      turn: async (...args) => {
        await args[7]("thread-9");
        args[6]({ itemId: "final-1", text: "Ordering now" });
        return { text: "  Ordered.\r\nArrives soon.  ", threadId: "thread-9", phoneToolFailures: [] };
      },
    });

    await processPendingRequest({ ok: true, available: true, sessionId: "session-1" }, codex);

    expect(sentRequests()).toEqual([
      JSON.stringify({ type: "claim_request", requestId: "<id>", sessionId: "session-1" }),
      JSON.stringify({
        type: "bind_codex_thread",
        requestId: "<id>",
        conversationId: "conversation-1",
        codexThreadId: "thread-9",
      }),
      JSON.stringify({
        type: "stream_agent_message",
        requestId: "<id>",
        sessionId: "session-1",
        messageId: "dhd-agent-session-1",
        text: "Ordering now",
      }),
      JSON.stringify({
        type: "complete_session",
        requestId: "<id>",
        sessionId: "session-1",
        message: "Ordered.\nArrives soon.",
        feedback: "Ordered.\nArrives soon.",
        agentMessageId: "dhd-agent-session-1",
      }),
    ]);
    expect(bridge.calls.map(({ options }) => options)).toEqual([
      undefined,
      undefined,
      { timeoutMs: 5_000 },
      undefined,
    ]);
    const [args] = codex.runTurn.mock.calls;
    expect(args.slice(0, 3)).toEqual(["Order iced tea", undefined, "Iced tea"]);
    expect(args.slice(4, 6)).toEqual(["xhigh", true]);
    expect(args[8]).toBe(false);
  });

  it("coalesces streamed agent messages behind the in-flight update and retries rejected text", async () => {
    respond("claim_request", { ok: true, request: "Order iced tea" });
    const streamResponses: Array<(response: BridgeResponse) => void> = [];
    respond("stream_agent_message", () => new Promise((resolve) => streamResponses.push(resolve)));
    respond("complete_session", { ok: true });
    const codex = fakeCodex({
      turn: async (...args) => {
        const stream = args[6];
        stream({ itemId: "final-1", text: "A" });
        stream({ itemId: "final-1", text: "AB" });
        stream({ itemId: "final-1", text: "   " });
        stream({ itemId: "final-1", text: "ABC" });
        await vi.waitFor(() => expect(streamResponses).toHaveLength(1));
        streamResponses[0]({ ok: true });
        await vi.waitFor(() => expect(streamResponses).toHaveLength(2));
        streamResponses[1]({ ok: false, message: "timeline busy" });
        await new Promise((resolve) => setImmediate(resolve));
        stream({ itemId: "final-1", text: "ABC" });
        await vi.waitFor(() => expect(streamResponses).toHaveLength(3));
        streamResponses[2]({ ok: true });
        return { text: "ABC", threadId: "thread-1", phoneToolFailures: [] };
      },
    });

    await processPendingRequest({ sessionId: "session-s" }, codex);

    expect(
      bridge.calls
        .filter(({ request }) => request.type === "stream_agent_message")
        .map(({ request }) => request.text),
    ).toEqual(["A", "ABC", "ABC"]);
    expect(companionErrors()).toContain(
      "[phone-assistant-companion] phone rejected streamed agent message: timeline busy",
    );
    expect(bridge.calls.at(-1)?.request).toMatchObject({ type: "complete_session", agentMessageId: "dhd-agent-session-s" });
  });

  it("truncates streamed agent messages to the phone limit", async () => {
    respond("claim_request", { ok: true, request: "Summarize" });
    respond("stream_agent_message", { ok: true });
    respond("complete_session", { ok: true });
    const codex = fakeCodex({
      turn: async (...args) => {
        args[6]({ itemId: "final-1", text: "y".repeat(4_100) });
        return { text: "Done.", threadId: "thread-1", phoneToolFailures: [] };
      },
    });

    await processPendingRequest({ sessionId: "session-u" }, codex);

    expect(bridge.calls.find(({ request }) => request.type === "stream_agent_message")?.request.text).toBe(
      "y".repeat(4_000),
    );
  });

  it("keeps the turn alive when streaming an agent message fails", async () => {
    respond("claim_request", { ok: true, request: "Order iced tea" });
    respond("stream_agent_message", () => {
      throw new Error("Timed out waiting for the phone assistant bridge.");
    });
    respond("complete_session", { ok: true });
    const codex = fakeCodex({
      turn: async (...args) => {
        args[6]({ itemId: "final-1", text: "Working" });
        await new Promise((resolve) => setImmediate(resolve));
        return { text: "Done.", threadId: "thread-1", phoneToolFailures: [] };
      },
    });

    await processPendingRequest({ sessionId: "session-t" }, codex);

    expect(companionErrors()).toContain(
      "[phone-assistant-companion] could not stream agent message: Timed out waiting for the phone assistant bridge.",
    );
    expect(bridge.calls.at(-1)?.request).toMatchObject({ type: "complete_session", agentMessageId: "dhd-agent-session-t" });
  });

  it("resumes a stored thread without rebinding it and falls back to the default completion message", async () => {
    respond("claim_request", {
      ok: true,
      request: "Check the order",
      conversationId: "conversation-1",
      codexThreadId: "thread-stored",
    });
    respond("complete_session", { ok: true });
    const codex = fakeCodex({
      turn: async (...args) => {
        await args[7]("thread-stored");
        return { text: "   ", threadId: "thread-stored", phoneToolFailures: [] };
      },
    });

    await processPendingRequest({ sessionId: "session-2" }, codex);

    expect(sentRequests()).toEqual([
      JSON.stringify({ type: "claim_request", requestId: "<id>", sessionId: "session-2" }),
      JSON.stringify({
        type: "complete_session",
        requestId: "<id>",
        sessionId: "session-2",
        message: "Your DHD task is ready to review.",
      }),
    ]);
    const [args] = codex.runTurn.mock.calls;
    expect(args.slice(0, 6)).toEqual(["Check the order", "thread-stored", "Check the order", expect.anything(), undefined, false]);
  });

  it("starts a continuation for an empty claimed request marked as continuation", async () => {
    respond("claim_request", { ok: true, request: "", continuation: true, codexThreadId: "thread-stopped" });
    respond("complete_session", { ok: true });
    const codex = fakeCodex();

    await processPendingRequest({ sessionId: "session-3" }, codex);

    const [args] = codex.runTurn.mock.calls;
    expect(args[0]).toBe("");
    expect(args[1]).toBe("thread-stopped");
    expect(args[8]).toBe(true);
  });

  it("truncates final feedback to the phone limit", async () => {
    respond("claim_request", { ok: true, request: "Summarize" });
    respond("complete_session", { ok: true });
    const codex = fakeCodex({
      turn: async () => ({ text: "x".repeat(4_100), threadId: "thread-1", phoneToolFailures: [] }),
    });

    await processPendingRequest({ sessionId: "session-4" }, codex);

    const completion = bridge.calls.at(-1)?.request ?? {};
    expect(completion.message).toBe("x".repeat(4_000));
    expect(completion.feedback).toBe("x".repeat(4_000));
    expect(completion).not.toHaveProperty("agentMessageId");
  });

  it("releases an empty claimed request without starting a turn", async () => {
    respond("claim_request", { ok: true, request: "" });
    respond("release_request", { ok: true });
    const codex = fakeCodex();

    await processPendingRequest({ sessionId: "session-5" }, codex);

    expect(sentRequests()).toEqual([
      JSON.stringify({ type: "claim_request", requestId: "<id>", sessionId: "session-5" }),
      JSON.stringify({ type: "release_request", requestId: "<id>", sessionId: "session-5" }),
    ]);
    expect(codex.runTurn).not.toHaveBeenCalled();
  });

  it("marks the session failed when the Codex turn fails", async () => {
    respond("claim_request", { ok: true, request: "Order iced tea" });
    respond("fail_session", { ok: true });
    const codex = fakeCodex({
      turn: async () => {
        throw new Error("Codex App Server turn failed.");
      },
    });

    await processPendingRequest({ sessionId: "session-6" }, codex);

    expect(sentRequests()).toEqual([
      JSON.stringify({ type: "claim_request", requestId: "<id>", sessionId: "session-6" }),
      JSON.stringify({
        type: "fail_session",
        requestId: "<id>",
        sessionId: "session-6",
        reason: "Codex App Server turn failed.",
      }),
    ]);
  });

  it("fails the session when the phone refuses to bind a new thread", async () => {
    respond("claim_request", { ok: true, request: "Order iced tea", conversationId: "conversation-1" });
    respond("bind_codex_thread", { ok: false, message: "conversation expired" });
    respond("fail_session", { ok: true });
    const codex = fakeCodex({
      turn: async (...args) => {
        await args[7]("thread-new");
        return { text: "unreachable", threadId: "thread-new", phoneToolFailures: [] };
      },
    });

    await processPendingRequest({ sessionId: "session-7" }, codex);

    expect(bridge.calls.at(-1)?.request).toMatchObject({
      type: "fail_session",
      reason: "The phone did not bind Codex thread thread-new: conversation expired",
    });
  });

  it("stays silent when another companion already claimed the request", async () => {
    respond("claim_request", { ok: false, code: "REQUEST_NOT_AVAILABLE", message: "already claimed" });
    const codex = fakeCodex();

    await processPendingRequest({ sessionId: "session-8" }, codex);

    expect(sentRequests()).toHaveLength(1);
    expect(codex.runTurn).not.toHaveBeenCalled();
    expect(companionErrors()).toEqual([]);
  });

  it("logs any other claim rejection", async () => {
    respond("claim_request", { ok: false, code: "UNAUTHORIZED", message: "bad token" });

    await processPendingRequest({ sessionId: "session-9" }, fakeCodex());

    expect(companionErrors()).toEqual(["[phone-assistant-companion] could not claim request: bad token"]);
  });

  it("ignores a pending request without a session id", async () => {
    await processPendingRequest({ ok: true, available: true }, fakeCodex());

    expect(bridge.calls).toEqual([]);
    expect(companionErrors()).toEqual([
      "[phone-assistant-companion] pending request did not include a session id",
    ]);
  });
});

describe("phone steer runner", () => {
  const active = (client: ReturnType<typeof fakeCodex>) => ({ sessionId: "session-1", client });

  it("claims a pending steer, delivers it to Codex, and completes it", async () => {
    respond("pending_steer", { ok: true, active: true, available: true, steerId: "steer-1" });
    respond("claim_steer", { ok: true, text: "  go left  " });
    respond("complete_steer", { ok: true });
    const codex = fakeCodex();

    await processPendingSteer(active(codex));

    expect(sentRequests()).toEqual([
      JSON.stringify({ type: "pending_steer", requestId: "<id>", sessionId: "session-1" }),
      JSON.stringify({ type: "claim_steer", requestId: "<id>", sessionId: "session-1", steerId: "steer-1" }),
      JSON.stringify({ type: "complete_steer", requestId: "<id>", sessionId: "session-1", steerId: "steer-1" }),
    ]);
    expect(bridge.calls[0].options).toEqual({ timeoutMs: 5_000 });
    expect(codex.steer).toHaveBeenCalledWith("go left");
  });

  it("releases a steer when Codex rejects it", async () => {
    respond("pending_steer", { ok: true, active: true, available: true, steerId: "steer-2" });
    respond("claim_steer", { ok: true, text: "go left" });
    respond("release_steer", { ok: true });
    const codex = fakeCodex({
      steer: async () => {
        throw new Error("Codex has no active turn to steer.");
      },
    });

    await processPendingSteer(active(codex));

    expect(sentRequests().at(-1)).toBe(
      JSON.stringify({ type: "release_steer", requestId: "<id>", sessionId: "session-1", steerId: "steer-2" }),
    );
  });

  it("releases an empty claimed steer without calling Codex", async () => {
    respond("pending_steer", { ok: true, active: true, available: true, steerId: "steer-3" });
    respond("claim_steer", { ok: true, text: "   " });
    respond("release_steer", { ok: true });
    const codex = fakeCodex();

    await processPendingSteer(active(codex));

    expect(bridge.calls.map(({ request }) => request.type)).toEqual([
      "pending_steer",
      "claim_steer",
      "release_steer",
    ]);
    expect(codex.steer).not.toHaveBeenCalled();
  });

  it("interrupts Codex after a phone-side Stop", async () => {
    respond("pending_steer", { ok: true, active: false });
    const codex = fakeCodex();

    await processPendingSteer(active(codex));

    expect(codex.interrupt).toHaveBeenCalledTimes(1);
    expect(bridge.calls).toHaveLength(1);
  });

  it("does not interrupt while the phone waits for user attention", async () => {
    respond("pending_steer", { ok: true, active: false, attentionPending: true, available: false });
    const codex = fakeCodex();

    await processPendingSteer(active(codex));

    expect(codex.interrupt).not.toHaveBeenCalled();
    expect(bridge.calls).toHaveLength(1);
  });

  it("leaves a queued steer untouched until the turn can be steered", async () => {
    respond("pending_steer", { ok: true, active: true, available: true, steerId: "steer-4" });
    const codex = fakeCodex({ canSteer: false });

    await processPendingSteer(active(codex));

    expect(bridge.calls).toHaveLength(1);
  });

  it("stays silent when the steer was already claimed elsewhere", async () => {
    respond("pending_steer", { ok: true, active: true, available: true, steerId: "steer-5" });
    respond("claim_steer", { ok: false, code: "STEER_NOT_AVAILABLE", message: "gone" });

    await processPendingSteer(active(fakeCodex()));

    expect(companionErrors()).toEqual([]);
  });

  it("logs any other steer claim rejection and a rejected steer poll", async () => {
    respond("pending_steer", { ok: true, active: true, available: true, steerId: "steer-6" });
    respond("claim_steer", { ok: false, code: "UNAUTHORIZED", message: "bad token" });
    await processPendingSteer(active(fakeCodex()));

    respond("pending_steer", { ok: false, message: "session closed" });
    await processPendingSteer(active(fakeCodex()));

    expect(companionErrors()).toEqual([
      "[phone-assistant-companion] could not claim steer steer-6: bad token",
      "[phone-assistant-companion] phone bridge rejected steer poll: session closed",
    ]);
  });
});
