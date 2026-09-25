import { randomUUID } from "node:crypto";

import { requestBridge } from "../phone/bridge-client.js";
import type { BridgeMessage } from "../phone/protocol.js";
import { errorMessage } from "../shared/errors.js";
import type { ActiveCodexTurn } from "./active-turn.js";

export const BRIDGE_POLL_TIMEOUT_MS = 5_000;

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

/** Attention waiting is active-session state, not a phone-side Stop. */
export function shouldInterruptForPhoneStop(pending: BridgeMessage): boolean {
  return pending.attentionPending !== true && pending.active === false;
}
