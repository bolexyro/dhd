import type {
  CompanionPlanUpdatedEvent,
  CompanionTokenUsageEvent,
} from "../companion-events.js";
import { asRecord } from "../shared/guards.js";

export function extractThreadId(value: unknown): string | null {
  const record = asRecord(value);
  if (!record) return null;
  const threadId = asRecord(record.thread)?.id;
  if (typeof threadId === "string" && threadId) return threadId;
  if (typeof record.threadId === "string" && record.threadId)
    return record.threadId;
  return typeof record.id === "string" && record.id ? record.id : null;
}

export function extractTurnId(value: unknown): string | null {
  const record = asRecord(value);
  if (!record) return null;
  const turn = asRecord(record.turn);
  if (turn && typeof turn.id === "string" && turn.id) return turn.id;
  return typeof record.id === "string" && record.id ? record.id : null;
}

export function extractTurnError(value: unknown): string {
  const record = asRecord(value);
  const nestedError = asRecord(record?.error);
  if (typeof nestedError?.message === "string") return nestedError.message;
  const turn = asRecord(record?.turn);
  const turnError = asRecord(turn?.error);
  if (typeof turnError?.message === "string") return turnError.message;
  return typeof record?.message === "string" ? record.message : "";
}

export function extractText(value: unknown): string {
  const record = asRecord(value);
  if (!record) return "";
  for (const key of ["delta", "text", "message"]) {
    if (typeof record[key] === "string") return record[key] as string;
  }
  const item = asRecord(record.item);
  if (item) {
    for (const key of ["text", "message"]) {
      if (typeof item[key] === "string") return item[key] as string;
    }
  }
  return "";
}

function readTokenCount(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : null;
}

export function extractCompanionTokenUsageEvent(
  value: unknown,
  timestamp = Date.now(),
): CompanionTokenUsageEvent | null {
  const message = asRecord(value);
  if (message?.method !== "thread/tokenUsage/updated") return null;

  const params = asRecord(message.params);
  const tokenUsage = asRecord(params?.tokenUsage);
  const last = asRecord(tokenUsage?.last);
  const threadId = typeof params?.threadId === "string" ? params.threadId : "";
  const turnId = typeof params?.turnId === "string" ? params.turnId : "";
  if (!threadId || !turnId || !last) return null;

  const inputTokens = readTokenCount(last.inputTokens);
  const outputTokens = readTokenCount(last.outputTokens);
  const cachedInputTokens = readTokenCount(last.cachedInputTokens);
  const reasoningOutputTokens = readTokenCount(last.reasoningOutputTokens);
  const totalTokens = readTokenCount(last.totalTokens);
  if (
    inputTokens === null ||
    outputTokens === null ||
    cachedInputTokens === null ||
    reasoningOutputTokens === null ||
    totalTokens === null
  ) {
    return null;
  }

  const rawContextWindow = tokenUsage?.modelContextWindow;
  const modelContextWindow =
    rawContextWindow === undefined || rawContextWindow === null
      ? null
      : readTokenCount(rawContextWindow);
  if (rawContextWindow !== undefined && rawContextWindow !== null && modelContextWindow === null) {
    return null;
  }

  return {
    type: "dhd_token_usage",
    threadId,
    turnId,
    usage: {
      inputTokens,
      outputTokens,
      cachedInputTokens,
      reasoningOutputTokens,
      totalTokens,
    },
    modelContextWindow,
    timestamp,
  };
}

export function extractCompanionPlanUpdatedEvent(
  value: unknown,
  threadId: string | null,
  expectedTurnId: string | null,
  timestamp = Date.now(),
): CompanionPlanUpdatedEvent | null {
  const message = asRecord(value);
  if (message?.method !== "turn/plan/updated" || !threadId) return null;

  const params = asRecord(message.params);
  const turnId = typeof params?.turnId === "string" ? params.turnId : "";
  if (!turnId || (expectedTurnId && expectedTurnId !== turnId)) return null;
  if (!Array.isArray(params?.plan)) return null;

  const steps: CompanionPlanUpdatedEvent["steps"] = [];
  for (const rawStep of params.plan) {
    const step = asRecord(rawStep);
    const text = typeof step?.step === "string" ? step.step : null;
    const rawStatus = step?.status;
    const status =
      rawStatus === "pending"
        ? "pending"
        : rawStatus === "inProgress" || rawStatus === "in_progress"
          ? "in_progress"
          : rawStatus === "completed"
            ? "completed"
            : null;
    if (text === null || status === null) return null;
    steps.push({ step: text, status });
  }

  const explanation =
    typeof params.explanation === "string" ? params.explanation : undefined;
  return {
    type: "dhd_plan",
    phase: "updated",
    threadId,
    turnId,
    ...(explanation ? { explanation } : {}),
    steps,
    timestamp,
  };
}
