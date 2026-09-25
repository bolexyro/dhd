import { randomUUID } from "node:crypto";

import type { PhoneSnapshot } from "../../companion-web/api.js";
import { requestBridge } from "../../phone/bridge-client.js";
import type { BridgeMessage } from "../../phone/protocol.js";
import { delay } from "../../shared/delay.js";
import { toError } from "../../shared/errors.js";
import type { ConnectionConfig } from "./settings-store.js";

const BRIDGE_CHECK_TIMEOUT_MS = 5_000;
const BRIDGE_CHECK_ATTEMPTS = 3;
const BRIDGE_CHECK_RETRY_DELAYS_MS = [150, 400] as const;
const BRIDGE_CHECK_DEADLINE_MESSAGE = "Timed out checking the phone assistant bridge.";

export interface StatusRequestOptions {
  deadlineAt?: number;
  attempts?: number;
  timeoutMs?: number;
}

export function bridgeOptions(timeoutMs: number, target: ConnectionConfig) {
  return {
    host: target.host,
    port: target.port,
    token: target.token || undefined,
    timeoutMs
  };
}

export function statusCheckError(result: BridgeMessage): Error {
  return new Error(
    typeof result.message === "string"
      ? result.message
      : "The phone bridge rejected the status check."
  );
}

export function remainingCheckTime(deadlineAt: number | undefined, maximumMs: number): number {
  if (deadlineAt === undefined) return maximumMs;
  const remainingMs = deadlineAt - Date.now();
  if (remainingMs <= 0) throw new Error(BRIDGE_CHECK_DEADLINE_MESSAGE);
  return Math.min(maximumMs, remainingMs);
}

async function waitForCheckRetry(milliseconds: number, deadlineAt: number | undefined): Promise<void> {
  await delay(remainingCheckTime(deadlineAt, milliseconds));
}

export function awaitBeforeCheckDeadline<T>(operation: Promise<T>, deadlineAt: number | undefined): Promise<T> {
  if (deadlineAt === undefined) return operation;
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error(BRIDGE_CHECK_DEADLINE_MESSAGE));
    }, remainingCheckTime(deadlineAt, Number.MAX_SAFE_INTEGER));
    operation.then(
      (value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

/**
 * A status request is intentionally small, but the first packet after a
 * phone/network transition can still be lost. Retry the request on a fresh
 * socket so one stale TCP attempt cannot make a healthy phone look offline.
 */
export async function requestStatusWithRetry(
  target: ConnectionConfig,
  options: StatusRequestOptions = {},
): Promise<BridgeMessage> {
  let lastError: unknown;
  const attempts = Math.max(1, options.attempts ?? BRIDGE_CHECK_ATTEMPTS);
  const timeoutMs = options.timeoutMs ?? BRIDGE_CHECK_TIMEOUT_MS;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const result = await requestBridge(
        { type: "status", requestId: randomUUID() },
        bridgeOptions(remainingCheckTime(options.deadlineAt, timeoutMs), target)
      );
      if (result.ok !== true) throw statusCheckError(result);
      return result;
    } catch (error) {
      lastError = error;
      if (attempt < attempts - 1) {
        await waitForCheckRetry(
          BRIDGE_CHECK_RETRY_DELAYS_MS[attempt] ?? BRIDGE_CHECK_RETRY_DELAYS_MS.at(-1)!,
          options.deadlineAt,
        );
      }
    }
  }
  throw toError(lastError);
}

export function phoneSnapshot(value: Record<string, unknown>): PhoneSnapshot {
  return {
    state: typeof value.state === "string" ? value.state : "unknown",
    active: value.active === true,
    ...(typeof value.companionConnected === "boolean" ? { companionConnected: value.companionConnected } : {}),
    ...(typeof value.sessionId === "string" ? { sessionId: value.sessionId } : {}),
    ...(typeof value.request === "string" ? { request: value.request } : {}),
    ...(typeof value.currentPurpose === "string" ? { currentPurpose: value.currentPurpose } : {}),
    ...(typeof value.requestAvailable === "boolean" ? { requestAvailable: value.requestAvailable } : {})
  };
}
