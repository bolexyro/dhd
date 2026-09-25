import { randomUUID } from "node:crypto";

import {
  bridgeHost,
  bridgePort,
  isLoopbackBridgeHost,
  requestBridge,
} from "./phone/bridge-client.js";
import type { BridgeMessage } from "./phone/protocol.js";
import { errorMessage } from "./shared/errors.js";
import { isMainModule } from "./shared/is-main-module.js";
import { isDebugTimingEnabled, pollIntervalSetting } from "./config/env.js";
import { PhaseTimer, logCompanionPhase } from "./shared/timing.js";
import { CodexAppServerClient } from "./codex/app-server-client.js";
import { delay } from "./shared/delay.js";
import {
  AgentMessageStreamer,
  MAX_AGENT_FEEDBACK_CHARS,
  streamedAgentMessageId,
} from "./worker/agent-message-streamer.js";
import { maintainCompanionHeartbeat } from "./worker/heartbeat.js";
import { prewarmCodexClient } from "./worker/prewarm.js";

const DEFAULT_POLL_INTERVAL_MS = 1_000;
const BRIDGE_POLL_TIMEOUT_MS = 5_000;
const DEFAULT_COMPLETION_MESSAGE = "Your DHD task is ready to review.";

export interface ActiveCodexTurn {
  sessionId: string;
  client: CodexAppServerClient;
}

/**
 * The phone bridge is pull-based: the desktop companion polls the phone over
 * the adb-forwarded socket. Keep the active App Server client here so those
 * polls can deliver steering input to the same in-flight turn.
 */
let activeCodexTurn: ActiveCodexTurn | null = null;

export async function runAssistantCompanion(
  codexClient = new CodexAppServerClient(),
): Promise<void> {
  const pollIntervalMs = parsePollInterval(pollIntervalSetting());
  let stopping = false;
  let pendingRun: Promise<void> | null = null;
  const stop = () => {
    stopping = true;
    const active = activeCodexTurn;
    if (active) {
      void active.client.interrupt().catch((error) => {
        console.error(
          `[phone-assistant-companion] could not interrupt on shutdown: ${errorMessage(error)}`,
        );
      });
    }
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);

  let codexWarmup: Promise<boolean> | null = null;
  const scheduleCodexWarmup = (scope: string): void => {
    // Codex startup can take longer than the phone presence lease. Keep the
    // bridge poll loop alive while warming the App Server in the background.
    if (codexWarmup) return;
    const operation = prewarmCodexClient(codexClient, scope);
    codexWarmup = operation;
    void operation.then(
      () => {
        if (codexWarmup === operation) codexWarmup = null;
      },
      (error) => {
        console.error(
          `[phone-assistant-companion] Codex warmup runner failed: ${errorMessage(error)}`,
        );
        if (codexWarmup === operation) codexWarmup = null;
      },
    );
  };

  console.error(
    "[phone-assistant-companion] waiting for a request typed in the Android app",
  );
  console.error(
    `[phone-assistant-companion] phone bridge target ${bridgeHost}:${bridgePort}`,
  );
  if (isLoopbackBridgeHost(bridgeHost)) {
    console.error(
      "[phone-assistant-companion] loopback mode: adb forward tcp:8765 tcp:8765 is still supported",
    );
  } else {
    console.error(
      "[phone-assistant-companion] wireless mode: phone and laptop must share Wi-Fi and PHONE_ASSISTANT_BRIDGE_TOKEN must match DHD settings",
    );
  }
  console.error(
    "[phone-assistant-companion] a logged-in Codex CLI must be available on this companion host",
  );

  const heartbeatPromise = maintainCompanionHeartbeat(() => stopping);
  try {
    scheduleCodexWarmup("codex-prewarm");
    while (!stopping) {
      const pollStartedAt = performance.now();
      if (isDebugTimingEnabled()) logCompanionPhase("poll:start");
      try {
        if (!pendingRun && !activeCodexTurn) {
          const pending = await requestBridge(
            { type: "pending_request", requestId: randomUUID() },
            { timeoutMs: BRIDGE_POLL_TIMEOUT_MS },
          );
          if (isDebugTimingEnabled()) {
            logCompanionPhase(
              "poll:complete",
              `durationMs=${Math.round(performance.now() - pollStartedAt)} available=${pending.available === true}`,
            );
          }
          if (pending.warmupRequested === true) {
            logCompanionPhase("codex:warmup_requested");
            scheduleCodexWarmup("codex-app-open-warmup");
          }
          if (pending.ok === true && pending.available === true) {
            logCompanionPhase(
              "poll:request_detected",
              `durationMs=${Math.round(performance.now() - pollStartedAt)}`,
            );
            pendingRun = processPendingRequest(pending, codexClient)
              .catch((error) => {
                console.error(
                  `[phone-assistant-companion] phone request runner failed: ${errorMessage(error)}`,
                );
              })
              .finally(() => {
                pendingRun = null;
              });
          } else if (pending.ok === false) {
            console.error(
              `[phone-assistant-companion] phone bridge rejected poll: ${String(pending.message ?? "unknown error")}`,
            );
          }
        } else if (activeCodexTurn) {
          await processPendingSteer(activeCodexTurn);
        }
      } catch (error) {
        logCompanionPhase(
          "poll:error",
          `durationMs=${Math.round(performance.now() - pollStartedAt)}`,
        );
        // The phone may be disconnected or the bridge may not be running yet.
        // Keep polling so reconnecting the device does not require a restart.
        console.error(
          `[phone-assistant-companion] ${errorMessage(error)}`,
        );
      }
      if (!stopping) await delay(pollIntervalMs);
    }
  } finally {
    stopping = true;
    if (pendingRun) await pendingRun;
    await heartbeatPromise;
    await codexClient.close();
  }
}

export async function processPendingRequest(
  pending: BridgeMessage,
  codexClient: CodexAppServerClient,
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
  activeCodexTurn = { sessionId, client: codexClient };
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
    if (activeCodexTurn?.sessionId === sessionId) activeCodexTurn = null;
  }
}

export async function processPendingSteer(active: ActiveCodexTurn): Promise<void> {
  const pending = await requestBridge(
    {
      type: "pending_steer",
      requestId: randomUUID(),
      sessionId: active.sessionId,
    },
    { timeoutMs: BRIDGE_POLL_TIMEOUT_MS },
  );
  if (pending.ok !== true) {
    if (pending.message) {
      console.error(
        `[phone-assistant-companion] phone bridge rejected steer poll: ${String(pending.message)}`,
      );
    }
    return;
  }

  // A phone-side Stop changes the coordinator state before the next poll. In
  // that case interrupt Codex as well so the desktop turn cannot continue
  // operating the phone after the user has stopped it.
  if (shouldInterruptForPhoneStop(pending) && active.client.isTurnInFlight) {
    await active.client.interrupt().catch((error) => {
      console.error(
        `[phone-assistant-companion] could not interrupt stopped phone session: ${errorMessage(error)}`,
      );
    });
    return;
  }
  // The App Server may still be completing turn/start. Leave a queued steer
  // untouched until its thread and turn ids are available for turn/steer.
  if (!active.client.canSteer) return;
  if (pending.available !== true) return;

  const steerId = typeof pending.steerId === "string" ? pending.steerId : "";
  if (!steerId) {
    console.error(
      "[phone-assistant-companion] pending steer did not include a steer id",
    );
    return;
  }
  const claimed = await requestBridge({
    type: "claim_steer",
    requestId: randomUUID(),
    sessionId: active.sessionId,
    steerId,
  });
  if (claimed.ok !== true) {
    if (claimed.code !== "STEER_NOT_AVAILABLE") {
      console.error(
        `[phone-assistant-companion] could not claim steer ${steerId}: ${String(claimed.message ?? "unknown error")}`,
      );
    }
    return;
  }

  const text = typeof claimed.text === "string" ? claimed.text.trim() : "";
  if (!text) {
    await releaseSteer(steerId, active.sessionId);
    return;
  }

  try {
    await active.client.steer(text);
    const completed = await requestBridge({
      type: "complete_steer",
      requestId: randomUUID(),
      sessionId: active.sessionId,
      steerId,
    });
    if (completed.ok !== true) {
      console.error(
        `[phone-assistant-companion] could not mark steer ${steerId} delivered: ${String(completed.message ?? "unknown error")}`,
      );
    }
    console.error(
      `[phone-assistant-companion] delivered steer ${steerId} to the active Codex turn`,
    );
  } catch (error) {
    console.error(
      `[phone-assistant-companion] Codex steer failed: ${errorMessage(error)}`,
    );
    await releaseSteer(steerId, active.sessionId);
  }
}

async function releaseSteer(steerId: string, sessionId: string): Promise<void> {
  try {
    await requestBridge({
      type: "release_steer",
      requestId: randomUUID(),
      sessionId,
      steerId,
    });
  } catch (error) {
    console.error(
      `[phone-assistant-companion] could not release steer ${steerId}: ${errorMessage(error)}`,
    );
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

/** Attention waiting is active-session state, not a phone-side Stop. */
export function shouldInterruptForPhoneStop(pending: BridgeMessage): boolean {
  return pending.attentionPending !== true && pending.active === false;
}

function normalizeAgentFeedback(text: string): string {
  return text.replace(/\r\n?/g, "\n").trim().slice(0, MAX_AGENT_FEEDBACK_CHARS);
}

export function parsePollInterval(value: string | undefined): number {
  if (!value?.trim()) return DEFAULT_POLL_INTERVAL_MS;
  if (!/^\d+$/.test(value.trim()))
    throw new Error("PHONE_ASSISTANT_POLL_MS must be a positive integer.");
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 250 || parsed > 60_000) {
    throw new Error("PHONE_ASSISTANT_POLL_MS must be between 250 and 60000.");
  }
  return parsed;
}

if (isMainModule("assistant-companion")) {
  runAssistantCompanion().catch((error: unknown) => {
    console.error(
      `[phone-assistant-companion] ${errorMessage(error)}`,
    );
    process.exitCode = 1;
  });
}
