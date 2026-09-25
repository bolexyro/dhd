import type { BridgeMessage } from "../../phone/protocol.js";
import { isRecord } from "../../shared/guards.js";

const AGENT_HIDDEN_KEYS = new Set(["requestId", "taskId", "taskSessionKey", "sessionKey", "displayId"]);

export function withoutScreenshot(message: BridgeMessage): Record<string, unknown> {
  const copy = sanitizeAgentValue(message) as Record<string, unknown>;
  delete copy.screenshotBase64;
  delete copy.beforeScreenshotBase64;
  delete copy.beforeScreenshotMimeType;
  delete copy.beforeObservation;
  delete copy.initialPointer;
  return copy;
}

/** Remove bridge correlation, owner, and native display identifiers before a result reaches Codex. */
function sanitizeAgentValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sanitizeAgentValue);
  if (!isRecord(value)) return value;
  const sanitized: Record<string, unknown> = {};
  for (const [key, nested] of Object.entries(value)) {
    if (AGENT_HIDDEN_KEYS.has(key)) continue;
    sanitized[key] = sanitizeAgentValue(nested);
  }
  return sanitized;
}
