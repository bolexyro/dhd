export const DEFAULT_BRIDGE_HOST = "127.0.0.1";
export const DEFAULT_BRIDGE_PORT = 8765;

export interface BridgeMessage {
  type?: string;
  ok?: boolean;
  [key: string]: unknown;
}

export interface BridgeRequest {
  type: string;
  requestId: string;
  /** Canonical DHD tool name used for safe phone-side activity display. */
  tool?: string;
  [key: string]: unknown;
}

const TERMINAL_MESSAGE_TYPES = new Set([
  "error",
  "started",
  "status",
  "pending_request",
  "pending_steer",
  "heartbeat",
  "companion_disconnected",
  "request_claimed",
  "request_released",
  "steer_claimed",
  "steer_released",
  "steer_completed",
  "codex_thread_bound",
  "agent_message_streamed",
  "attention_requested",
  "attention_resolved",
  "attention_cancelled",
  "session_completed",
  "session_failed",
  "allowed_apps",
  "browse_apps",
  "app_display_layout_updated",
  "displays",
  "display_closed",
  "foreground_app",
  "observation",
  "completed",
  "stopped"
]);

export function isTerminalBridgeMessage(message: BridgeMessage): boolean {
  return typeof message.type === "string" && TERMINAL_MESSAGE_TYPES.has(message.type);
}
