import type { PhoneAssistantToolResult } from "./dhd-tools.js";
import { isRecord } from "./shared/guards.js";

export type CompanionJsonValue =
  | null
  | boolean
  | number
  | string
  | CompanionJsonValue[]
  | { [key: string]: CompanionJsonValue };

export const COMPANION_TOOL_EVENT_TYPE = "dhd_tool_call" as const;
export const COMPANION_TOKEN_USAGE_EVENT_TYPE = "dhd_token_usage" as const;
export const COMPANION_PLAN_EVENT_TYPE = "dhd_plan" as const;

export interface CompanionToolCallStartedEvent {
  type: typeof COMPANION_TOOL_EVENT_TYPE;
  phase: "started";
  callId: string;
  tool: string;
  arguments: CompanionJsonValue;
  rawArguments?: string;
  timestamp: number;
}

export interface CompanionToolCallCompletedEvent {
  type: typeof COMPANION_TOOL_EVENT_TYPE;
  phase: "completed";
  callId: string;
  tool: string;
  result?: PhoneAssistantToolResult;
  error?: string;
  completedAt: number;
}

export type CompanionToolCallEvent =
  | CompanionToolCallStartedEvent
  | CompanionToolCallCompletedEvent;

/** Public contract name used by worker/dashboard integrations. */
export type CompanionToolEvent = CompanionToolCallEvent;

export interface CompanionTokenUsageMetrics {
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  reasoningOutputTokens: number;
  totalTokens: number;
}

export interface CompanionTokenUsageEvent {
  type: typeof COMPANION_TOKEN_USAGE_EVENT_TYPE;
  threadId: string;
  turnId: string;
  usage: CompanionTokenUsageMetrics;
  modelContextWindow: number | null;
  model?: string;
  serviceTier?: string;
  timestamp: number;
}

export type CompanionPlanStepStatus = "pending" | "in_progress" | "completed";

export interface CompanionPlanStep {
  step: string;
  status: CompanionPlanStepStatus;
}

export interface CompanionPlanResetEvent {
  type: typeof COMPANION_PLAN_EVENT_TYPE;
  phase: "reset";
}

export interface CompanionPlanUpdatedEvent {
  type: typeof COMPANION_PLAN_EVENT_TYPE;
  phase: "updated";
  threadId: string;
  turnId: string;
  explanation?: string;
  steps: CompanionPlanStep[];
  timestamp: number;
}

export type CompanionPlanEvent =
  | CompanionPlanResetEvent
  | CompanionPlanUpdatedEvent;

export function isCompanionToolCallEvent(
  value: unknown,
): value is CompanionToolCallEvent {
  if (!isRecord(value)) return false;
  const event = value;
  return (
    event.type === COMPANION_TOOL_EVENT_TYPE &&
    (event.phase === "started" || event.phase === "completed") &&
    typeof event.callId === "string" &&
    event.callId.length > 0 &&
    typeof event.tool === "string" &&
    event.tool.length > 0 &&
    (event.phase === "started"
      ? typeof event.timestamp === "number"
      : typeof event.completedAt === "number")
  );
}

function isTokenCount(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

export function isCompanionTokenUsageEvent(
  value: unknown,
): value is CompanionTokenUsageEvent {
  if (!isRecord(value)) return false;
  const event = value;
  const metrics = event.usage;
  if (!isRecord(metrics)) return false;
  const modelContextWindow = event.modelContextWindow;
  return (
    event.type === COMPANION_TOKEN_USAGE_EVENT_TYPE &&
    typeof event.threadId === "string" &&
    event.threadId.length > 0 &&
    typeof event.turnId === "string" &&
    event.turnId.length > 0 &&
    isTokenCount(metrics.inputTokens) &&
    isTokenCount(metrics.outputTokens) &&
    isTokenCount(metrics.cachedInputTokens) &&
    isTokenCount(metrics.reasoningOutputTokens) &&
    isTokenCount(metrics.totalTokens) &&
    (modelContextWindow === null || isTokenCount(modelContextWindow)) &&
    (event.model === undefined || (typeof event.model === "string" && event.model.length > 0)) &&
    (event.serviceTier === undefined ||
      (typeof event.serviceTier === "string" && event.serviceTier.length > 0)) &&
    isTokenCount(event.timestamp)
  );
}

export function isCompanionPlanEvent(value: unknown): value is CompanionPlanEvent {
  if (!isRecord(value)) return false;
  const event = value;
  if (event.type !== COMPANION_PLAN_EVENT_TYPE) return false;
  if (event.phase === "reset") return true;
  if (
    event.phase !== "updated" ||
    typeof event.threadId !== "string" ||
    event.threadId.length === 0 ||
    typeof event.turnId !== "string" ||
    event.turnId.length === 0 ||
    !Array.isArray(event.steps) ||
    !isTokenCount(event.timestamp) ||
    (event.explanation !== undefined && typeof event.explanation !== "string")
  ) {
    return false;
  }

  return event.steps.every((step) => {
    if (!isRecord(step)) return false;
    return (
      typeof step.step === "string" &&
      (step.status === "pending" ||
        step.status === "in_progress" ||
        step.status === "completed")
    );
  });
}

export type CompanionEvent =
  | CompanionToolCallEvent
  | CompanionTokenUsageEvent
  | CompanionPlanEvent;

export type CompanionEventSink = (event: CompanionEvent) => void;

/**
 * The dashboard may run the worker as a direct command without an IPC parent.
 * Diagnostics are intentionally best-effort: an unavailable or broken event
 * channel must never change phone-tool behavior.
 */
export const ipcCompanionEventSink: CompanionEventSink = (event) => {
  if (typeof process.send !== "function" || process.connected === false) return;
  try {
    process.send(event, () => undefined);
  } catch {
    // The worker must continue even when the dashboard has gone away.
  }
};

let companionEventSink: CompanionEventSink = ipcCompanionEventSink;

export function setCompanionEventSink(sink: CompanionEventSink): void {
  companionEventSink = sink;
}

export function emitCompanionToolCallEvent(
  event: CompanionToolCallEvent,
): void {
  companionEventSink(event);
}

export function emitCompanionTokenUsageEvent(
  event: CompanionTokenUsageEvent,
): void {
  companionEventSink(event);
}

export function emitCompanionPlanEvent(event: CompanionPlanEvent): void {
  companionEventSink(event);
}
