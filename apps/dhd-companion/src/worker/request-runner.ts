import { randomUUID } from "node:crypto";

import { requestBridge } from "../phone/bridge-client.js";
import type { BridgeMessage } from "../phone/protocol.js";
import { errorMessage } from "../shared/errors.js";
import { PhaseTimer } from "../shared/timing.js";
import { beginCodexTurn, endCodexTurn, type CodexTurnClient } from "./active-turn.js";
import {
  AgentMessageStreamer,
  MAX_AGENT_FEEDBACK_CHARS,
  streamedAgentMessageId,
} from "./agent-message-streamer.js";

const DEFAULT_COMPLETION_MESSAGE = "Your DHD task is ready to review.";

export async function processPendingRequest(
  pending: BridgeMessage,
  codexClient: CodexTurnClient,
): Promise<void> {
  const sessionId =
    typeof pending.sessionId === "string" ? pending.sessionId : "";
  if (!sessionId) {
    console.error(
      "[phone-assistant-companion] pending request did not include a session id",
    );
    return;
  }
  const timing = new PhaseTimer(`phone-request:${sessionId}`);
  timing.log("claim:start");
  let claimed: BridgeMessage;
  try {
    claimed = await requestBridge({
      type: "claim_request",
      requestId: randomUUID(),
      sessionId,
    });
    timing.log("claim:complete", `ok=${claimed.ok === true}`);
  } catch (error) {
    timing.log("claim:error");
    throw error;
  }
  if (claimed.ok !== true) {
    // Another companion instance may have claimed it between polling and the
    // claim call. This is expected and is safe to ignore.
    if (claimed.code !== "REQUEST_NOT_AVAILABLE") {
      console.error(
        `[phone-assistant-companion] could not claim request: ${String(claimed.message ?? "unknown error")}`,
      );
    }
    return;
  }

  const request = typeof claimed.request === "string" ? claimed.request : "";
  const isContinuation = claimed.continuation === true;
  if (!request && !isContinuation) {
    console.error(
      "[phone-assistant-companion] claimed request was empty; releasing it",
    );
    await releaseRequest(sessionId);
    return;
  }

  console.error(
    `[phone-assistant-companion] claimed ${sessionId}: ${isContinuation ? "continuation" : request}`,
  );
  beginCodexTurn({ sessionId, client: codexClient });
  const agentMessageStreamer = new AgentMessageStreamer(
    sessionId,
    streamedAgentMessageId(sessionId),
  );
  try {
    const conversationId =
      typeof claimed.conversationId === "string"
        ? claimed.conversationId
        : undefined;
    const existingThreadId =
      typeof claimed.codexThreadId === "string"
        ? claimed.codexThreadId
        : undefined;
    const threadTitle =
      typeof claimed.title === "string" ? claimed.title : request;
    const reasoningEffort =
      typeof claimed.reasoningEffort === "string"
        ? claimed.reasoningEffort
        : undefined;
    const fastMode = claimed.fastMode === true;
    const result = await codexClient.runTurn(
      request,
      existingThreadId,
      threadTitle,
      timing,
      reasoningEffort,
      fastMode,
      (update) => agentMessageStreamer.push(update),
      async (threadId) => {
        // Bind a newly created thread before the first turn can finish. If
        // the user stops mid-task, the interrupted turn still leaves enough
        // durable identity for Continue to resume the same Codex context.
        if (!conversationId || (existingThreadId && threadId === existingThreadId)) {
          return;
        }
        const bound = await requestBridge({
          type: "bind_codex_thread",
          requestId: randomUUID(),
          conversationId,
          codexThreadId: threadId,
        });
        if (bound.ok !== true) {
          throw new Error(
            `The phone did not bind Codex thread ${threadId}: ${String(bound.message ?? "unknown error")}`,
          );
        }
      },
      isContinuation,
    );
    if (result.phoneToolFailures.length > 0) {
      const failedTools = [
        ...new Set(result.phoneToolFailures.map((failure) => failure.tool)),
      ].join(", ");
      timing.log(
        "phone-tool:reported_failure",
        `count=${result.phoneToolFailures.length} tools=${failedTools || "unknown"}`,
      );
      console.error(
        `[phone-assistant-companion] ${result.phoneToolFailures.length} phone tool call(s) reported an error; preserving the Codex response and conversation context`,
      );
    }
    console.error(
      `[phone-assistant-companion] Codex turn reached terminal status; closing phone session` +
        `${result.text ? `; final assistant message: ${result.text.slice(0, 500)}` : ""}`,
    );
    const feedback = normalizeAgentFeedback(result.text);
    const completed = await requestBridge({
      type: "complete_session",
      requestId: randomUUID(),
      sessionId,
      message: feedback || DEFAULT_COMPLETION_MESSAGE,
      ...(feedback ? { feedback } : {}),
      ...(agentMessageStreamer.hasUpdates
        ? { agentMessageId: agentMessageStreamer.messageId }
        : {}),
    });
    if (completed.ok !== true) {
      console.error(
        `[phone-assistant-companion] could not mark the phone session complete: ${String(completed.message ?? "unknown error")}`,
      );
    }
  } catch (error) {
    console.error(
      `[phone-assistant-companion] Codex turn failed: ${errorMessage(error)}`,
    );
    try {
      await requestBridge({
        type: "fail_session",
        requestId: randomUUID(),
        sessionId,
        reason: errorMessage(error),
      });
    } catch (failureError) {
      console.error(
        `[phone-assistant-companion] could not mark the phone session failed: ${errorMessage(failureError)}`,
      );
    }
  } finally {
    endCodexTurn(sessionId);
  }
}

async function releaseRequest(sessionId: string): Promise<void> {
  try {
    await requestBridge({
      type: "release_request",
      requestId: randomUUID(),
      sessionId,
    });
  } catch (error) {
    console.error(
      `[phone-assistant-companion] could not release request: ${errorMessage(error)}`,
    );
  }
}

function normalizeAgentFeedback(text: string): string {
  return text.replace(/\r\n?/g, "\n").trim().slice(0, MAX_AGENT_FEEDBACK_CHARS);
}
