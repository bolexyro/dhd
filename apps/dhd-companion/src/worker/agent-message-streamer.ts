import { randomUUID } from "node:crypto";

import type { AgentMessageStreamUpdate } from "../codex/agent-messages.js";
import { requestBridge } from "../phone/bridge-client.js";
import { errorMessage } from "../shared/errors.js";

const STREAM_BRIDGE_TIMEOUT_MS = 5_000;
export const MAX_AGENT_FEEDBACK_CHARS = 4_000;

/**
 * Forward the latest cumulative final-answer text to the phone while keeping
 * bridge writes ordered. If Codex emits faster than the phone can refresh its
 * Room-backed timeline, intermediate snapshots are coalesced; the phone still
 * receives the newest text in order. Completion does not wait for these
 * presentation updates because `complete_session` is the authoritative
 * terminal update for the same message id.
 */
export class AgentMessageStreamer {
  private latest: AgentMessageStreamUpdate | null = null;
  private drainPromise: Promise<void> | null = null;
  private lastSentText: string | null = null;
  private _hasUpdates = false;

  constructor(
    private readonly sessionId: string,
    readonly messageId: string,
  ) {}

  get hasUpdates(): boolean {
    return this._hasUpdates;
  }

  push(update: AgentMessageStreamUpdate): void {
    if (!update.text.trim()) return;
    this.latest = update;
    this._hasUpdates = true;
    this.startDrain();
  }

  private startDrain(): void {
    if (this.drainPromise) return;
    const drain = this.drain();
    this.drainPromise = drain;
    void drain.finally(() => {
      if (this.drainPromise !== drain) return;
      this.drainPromise = null;
      if (this.latest) this.startDrain();
    });
  }

  private async drain(): Promise<void> {
    while (this.latest) {
      const update = this.latest;
      this.latest = null;
      const text = update.text.slice(0, MAX_AGENT_FEEDBACK_CHARS);
      if (text === this.lastSentText) continue;
      try {
        const response = await requestBridge(
          {
            type: "stream_agent_message",
            requestId: randomUUID(),
            sessionId: this.sessionId,
            messageId: this.messageId,
            text,
          },
          { timeoutMs: STREAM_BRIDGE_TIMEOUT_MS },
        );
        if (response.ok !== true) {
          console.error(
            `[phone-assistant-companion] phone rejected streamed agent message: ${String(response.message ?? "unknown error")}`,
          );
        } else {
          this.lastSentText = text;
        }
      } catch (error) {
        // Streaming is presentation feedback. A dropped update should not
        // turn a healthy Codex turn into a failed phone session; the final
        // complete_session call remains authoritative.
        console.error(
          `[phone-assistant-companion] could not stream agent message: ${errorMessage(error)}`,
        );
      }
    }
  }
}

export function streamedAgentMessageId(sessionId: string): string {
  return `dhd-agent-${sessionId}`;
}
