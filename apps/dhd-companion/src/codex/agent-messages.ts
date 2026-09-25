import { asRecord } from "../shared/guards.js";
import { extractText } from "./extract.js";

export interface AgentMessageState {
  id: string;
  text: string;
  phase?: string;
  completed: boolean;
  lastEventOrder: number;
}

export interface AgentMessageStreamUpdate {
  itemId: string;
  text: string;
}

export function recordAgentMessageStarted(
  completion: {
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
  },
  value: unknown,
): AgentMessageState | null {
  const item = extractAgentMessageItem(value);
  if (!item) return null;
  const state = getAgentMessageState(
    completion,
    extractAgentMessageId(value) || UNSCOPED_AGENT_MESSAGE_ID,
  );
  if (typeof item.text === "string") state.text = item.text;
  state.phase = extractAgentMessagePhase(value) || state.phase;
  state.completed = false;
  touchAgentMessage(completion, state);
  return state;
}

export function recordAgentMessageDelta(
  completion: {
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
  },
  value: unknown,
): AgentMessageState | null {
  const delta = extractText(value);
  if (!delta) return null;
  const state = getAgentMessageState(
    completion,
    extractAgentMessageId(value) || UNSCOPED_AGENT_MESSAGE_ID,
  );
  state.text += delta;
  state.phase = extractAgentMessagePhase(value) || state.phase;
  touchAgentMessage(completion, state);
  return state;
}

export function recordAgentMessageCompleted(
  completion: {
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
  },
  value: unknown,
): AgentMessageState | null {
  const item = extractAgentMessageItem(value);
  if (!item) return null;
  const state = getAgentMessageState(
    completion,
    extractAgentMessageId(value) || UNSCOPED_AGENT_MESSAGE_ID,
  );
  // item/completed is authoritative for the full agentMessage text. This
  // replaces any streamed deltas for this item without touching other phases.
  if (typeof item.text === "string") state.text = item.text;
  state.phase = extractAgentMessagePhase(value) || state.phase;
  state.completed = true;
  touchAgentMessage(completion, state);
  return state;
}

export function selectFinalAgentMessageText(
  agentMessages: Map<string, AgentMessageState>,
): string {
  const messages = [...agentMessages.values()]
    .filter((message) => message.text.trim())
    .sort((left, right) => left.lastEventOrder - right.lastEventOrder);
  const finalAnswer = [...messages]
    .reverse()
    .find((message) => message.completed && message.phase === "final_answer");
  if (finalAnswer) return finalAnswer.text;
  const lastCompleted = [...messages]
    .reverse()
    .find((message) => message.completed);
  if (lastCompleted) return lastCompleted.text;
  const lastFinalPhase = [...messages]
    .reverse()
    .find((message) => message.phase === "final_answer");
  return lastFinalPhase?.text || messages.at(-1)?.text || "";
}

const UNSCOPED_AGENT_MESSAGE_ID = "__agent_message_without_item_id__";

function getAgentMessageState(
  completion: {
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
  },
  id: string,
): AgentMessageState {
  const existing = completion.agentMessages.get(id);
  if (existing) return existing;
  const state: AgentMessageState = {
    id,
    text: "",
    completed: false,
    lastEventOrder: 0,
  };
  completion.agentMessages.set(id, state);
  return state;
}

function touchAgentMessage(
  completion: {
    nextAgentMessageOrder: number;
  },
  state: AgentMessageState,
): void {
  state.lastEventOrder = ++completion.nextAgentMessageOrder;
}

function extractAgentMessageItem(
  value: unknown,
): Record<string, unknown> | null {
  const record = asRecord(value);
  const item = asRecord(record?.item) || record;
  return item?.type === "agentMessage" ? item : null;
}

function extractAgentMessageId(value: unknown): string | null {
  const record = asRecord(value);
  if (!record) return null;
  if (typeof record.itemId === "string" && record.itemId) return record.itemId;
  const item = asRecord(record.item);
  if (typeof item?.id === "string" && item.id) return item.id;
  if (
    record.type === "agentMessage" &&
    typeof record.id === "string" &&
    record.id
  )
    return record.id;
  return null;
}

function extractAgentMessagePhase(value: unknown): string | null {
  const record = asRecord(value);
  if (!record) return null;
  if (typeof record.phase === "string" && record.phase) return record.phase;
  const item = asRecord(record.item);
  return typeof item?.phase === "string" && item.phase ? item.phase : null;
}
