import { randomUUID } from "node:crypto";

import {
  bridgeHost,
  bridgePort,
  isLoopbackBridgeHost,
  requestBridge,
} from "./phone/bridge-client.js";
import { errorMessage } from "./shared/errors.js";
import { isMainModule } from "./shared/is-main-module.js";
import { isDebugTimingEnabled, pollIntervalSetting } from "./config/env.js";
import { logCompanionPhase } from "./shared/timing.js";
import { CodexAppServerClient } from "./codex/app-server-client.js";
import { delay } from "./shared/delay.js";
import { maintainCompanionHeartbeat } from "./worker/heartbeat.js";
import { prewarmCodexClient } from "./worker/prewarm.js";
import { currentCodexTurn } from "./worker/active-turn.js";
import { processPendingRequest } from "./worker/request-runner.js";
import { BRIDGE_POLL_TIMEOUT_MS, processPendingSteer } from "./worker/steer.js";

const DEFAULT_POLL_INTERVAL_MS = 1_000;
export async function runAssistantCompanion(
  codexClient = new CodexAppServerClient(),
): Promise<void> {
  const pollIntervalMs = parsePollInterval(pollIntervalSetting());
  let stopping = false;
  let pendingRun: Promise<void> | null = null;
  const stop = () => {
    stopping = true;
    const active = currentCodexTurn();
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
        const activeTurn = currentCodexTurn();
        if (!pendingRun && !activeTurn) {
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
        } else if (activeTurn) {
          await processPendingSteer(activeTurn);
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
