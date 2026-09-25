import { asRecord } from "../shared/guards.js";
import { extractThreadId, extractTurnError } from "./extract.js";
import type { JsonRpcMessage } from "./json-rpc.js";

export function logServerNotification(message: JsonRpcMessage): void {
  if (message.method === "turn/started") {
    console.error("[codex-app-server] turn started");
    return;
  }
  if (message.method === "turn/completed") {
    const turn = asRecord(asRecord(message.params)?.turn);
    console.error(
      `[codex-app-server] turn completed (${String(turn?.status ?? "unknown")})`,
    );
    return;
  }
  if (message.method !== "item/started" && message.method !== "item/completed")
    return;
  const item = asRecord(asRecord(message.params)?.item);
  if (!item) return;
  const type = typeof item.type === "string" ? item.type : "item";
  const tool = typeof item.tool === "string" ? ` ${item.tool}` : "";
  const status = typeof item.status === "string" ? ` (${item.status})` : "";
  const duration =
    typeof item.durationMs === "number" ? ` [${item.durationMs}ms]` : "";
  console.error(
    `[codex-app-server] ${message.method} ${type}${tool}${status}${duration}`,
  );
}

export function startedThreadId(message: JsonRpcMessage): string | null {
  return message.method === "thread/started" ? extractThreadId(message.params) : null;
}

export function unloadedThreadId(message: JsonRpcMessage): string | null {
  if (message.method === "thread/closed") return extractThreadId(message.params);
  if (message.method !== "thread/status/changed") return null;
  const status = asRecord(asRecord(message.params)?.status);
  return status?.type === "notLoaded" ? extractThreadId(message.params) : null;
}

export function turnCompletedStatus(message: JsonRpcMessage): unknown {
  return asRecord(asRecord(message.params)?.turn)?.status;
}

export function turnCompletionError(message: JsonRpcMessage): Error | null {
  const status = turnCompletedStatus(message);
  if (status === "completed") return null;
  if (status === "failed") {
    return new Error(extractTurnError(message.params) || "Codex App Server turn failed.");
  }
  if (status === "interrupted") return new Error("Codex App Server turn was interrupted.");
  return new Error(
    `Codex App Server turn ended with unexpected status: ${String(status ?? "unknown")}.`,
  );
}

export function turnFailureError(message: JsonRpcMessage): Error {
  return new Error(extractTurnError(message.params) || "Codex App Server turn failed.");
}
