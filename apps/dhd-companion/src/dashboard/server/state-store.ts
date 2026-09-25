import { randomUUID } from "node:crypto";

import {
  isCompanionPlanEvent,
  isCompanionTokenUsageEvent,
  type CompanionPlanEvent,
  type CompanionPlanUpdatedEvent,
  type CompanionTokenUsageEvent,
} from "../../shared/companion-events.js";
import type {
  BridgeStatus,
  CompanionLogEntry,
  CompanionPlanSnapshot,
  CompanionProcessStatus,
  CompanionSettingsSnapshot,
  CompanionState,
  CompanionTokenUsageSnapshot,
  PhoneSnapshot,
} from "../../companion-web/api.js";
import { initialConnection, type ConnectionConfig } from "./settings-store.js";
import type { SseHub } from "./sse.js";
import { ToolCallStore } from "./tool-call-store.js";

const MAX_LOG_ENTRIES = 250;

export type LogOptions = Pick<CompanionLogEntry, "level" | "source">;

export class DashboardState {
  connection: ConnectionConfig = initialConnection();
  processStatus: CompanionProcessStatus = "stopped";
  bridgeStatus: BridgeStatus = "unknown";
  phone: PhoneSnapshot | undefined;
  lastError: string | undefined;
  readonly toolCalls = new ToolCallStore();
  private logEntries: CompanionLogEntry[] = [];
  private plan: CompanionPlanSnapshot | undefined;
  private tokenUsage: CompanionTokenUsageSnapshot | undefined;

  constructor(private readonly sse: SseHub) {}

  snapshot(): CompanionState {
    return {
      processStatus: this.processStatus,
      bridgeStatus: this.bridgeStatus,
      settings: this.settingsSnapshot(),
      ...(this.phone ? { phone: this.phone } : {}),
      ...(this.lastError ? { lastError: this.lastError } : {}),
      logs: [...this.logEntries],
      toolCalls: this.toolCalls.list(),
      ...(this.plan ? { plan: this.plan } : {}),
      ...(this.tokenUsage ? { tokenUsage: this.tokenUsage } : {})
    };
  }

  publish(): void {
    this.sse.broadcast(`data: ${JSON.stringify(this.snapshot())}\n\n`);
  }

  appendLog(
    message: string,
    options: LogOptions = { level: "info", source: "companion" }
  ): void {
    const trimmed = message.trim();
    if (!trimmed) return;
    this.logEntries = [
      ...this.logEntries,
      { id: randomUUID(), timestamp: Date.now(), message: trimmed, ...options }
    ].slice(-MAX_LOG_ENTRIES);
    this.publish();
  }

  clearLogs(): CompanionState {
    this.logEntries = [];
    this.publish();
    return this.snapshot();
  }

  clearToolCalls(): CompanionState {
    this.toolCalls.clear();
    this.publish();
    return this.snapshot();
  }

  ingestToolCallEvent(value: unknown): void {
    if (this.toolCalls.ingest(value)) this.publish();
  }

  ingestTokenUsageEvent(value: unknown): void {
    if (!isCompanionTokenUsageEvent(value)) return;
    const event: CompanionTokenUsageEvent = value;
    this.tokenUsage = {
      turnId: event.turnId,
      updatedAt: event.timestamp,
      ...event.usage,
      modelContextWindow: event.modelContextWindow,
      ...(event.model ? { model: event.model } : {}),
      ...(event.serviceTier ? { serviceTier: event.serviceTier } : {})
    };
    this.publish();
  }

  ingestPlanEvent(value: unknown): void {
    if (!isCompanionPlanEvent(value)) return;
    const event: CompanionPlanEvent = value;
    if (event.phase === "reset") {
      this.plan = undefined;
    } else {
      const updated: CompanionPlanUpdatedEvent = event;
      this.plan = {
        threadId: updated.threadId,
        turnId: updated.turnId,
        ...(updated.explanation ? { explanation: updated.explanation } : {}),
        steps: [...updated.steps],
        updatedAt: updated.timestamp
      };
    }
    this.publish();
  }

  private settingsSnapshot(): CompanionSettingsSnapshot {
    const connection = this.connection;
    return {
      host: connection.host,
      port: connection.port,
      tokenConfigured: connection.token.length > 0,
      pairingConfigured: Boolean(connection.deviceId),
      ...(connection.deviceId ? { pairedDeviceId: connection.deviceId } : {})
    };
  }
}
