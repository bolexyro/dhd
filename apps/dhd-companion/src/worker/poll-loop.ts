import { randomUUID } from "node:crypto";
import { performance } from "node:perf_hooks";

import { isDebugTimingEnabled } from "../config/env.js";
import { requestBridge } from "../phone/bridge-client.js";
import { errorMessage } from "../shared/errors.js";
import { logCompanionPhase } from "../shared/timing.js";
import { currentCodexTurn, type CodexTurnClient } from "./active-turn.js";
import type { CodexWarmup } from "./prewarm.js";
import { processPendingRequest } from "./request-runner.js";
import { BRIDGE_POLL_TIMEOUT_MS, processPendingSteer } from "./steer.js";

const DEFAULT_POLL_INTERVAL_MS = 1_000;

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

export class PhonePoller {
  private pendingRun: Promise<void> | null = null;

  constructor(
    private readonly codexClient: CodexTurnClient,
    private readonly warmup: CodexWarmup,
  ) {}

  async pollOnce(): Promise<void> {
    const pollStartedAt = performance.now();
    if (isDebugTimingEnabled()) logCompanionPhase("poll:start");
    try {
      const activeTurn = currentCodexTurn();
      if (!this.pendingRun && !activeTurn) {
        await this.pollPendingRequest(pollStartedAt);
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
  }

  async waitForPendingRun(): Promise<void> {
    if (this.pendingRun) await this.pendingRun;
  }

  private async pollPendingRequest(pollStartedAt: number): Promise<void> {
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
      this.warmup.schedule("codex-app-open-warmup");
    }
    if (pending.ok === true && pending.available === true) {
      logCompanionPhase(
        "poll:request_detected",
        `durationMs=${Math.round(performance.now() - pollStartedAt)}`,
      );
      this.pendingRun = processPendingRequest(pending, this.codexClient)
        .catch((error) => {
          console.error(
            `[phone-assistant-companion] phone request runner failed: ${errorMessage(error)}`,
          );
        })
        .finally(() => {
          this.pendingRun = null;
        });
    } else if (pending.ok === false) {
      console.error(
        `[phone-assistant-companion] phone bridge rejected poll: ${String(pending.message ?? "unknown error")}`,
      );
    }
  }
}
