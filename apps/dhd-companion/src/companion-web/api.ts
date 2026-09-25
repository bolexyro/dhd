import type {
  CompanionJsonValue,
  CompanionPlanStep,
  CompanionTokenUsageMetrics
} from "../shared/companion-events.js";

export type { CompanionJsonValue } from "../shared/companion-events.js";

export type CompanionProcessStatus = "stopped" | "starting" | "running" | "stopping" | "error";
export type BridgeStatus = "unknown" | "checking" | "connected" | "offline";

export interface CompanionSettingsSnapshot {
  host: string;
  port: number;
  tokenConfigured: boolean;
  pairingConfigured: boolean;
  /** Safe device identifier used to label the matching discovered phone. */
  pairedDeviceId?: string;
}

export interface PairingDeviceInput {
  deviceId: string;
  /** Explicitly request a new phone approval after a saved pairing fails. */
  replacePairing?: boolean;
}

/** Safe-to-display discovery metadata; no bridge token or pairing nonce. */
export interface DiscoveredPhoneSnapshot {
  deviceId: string;
  deviceName: string;
  model?: string;
}

export interface PhoneSnapshot {
  state: string;
  active: boolean;
  /** Whether the phone has recently heard from the companion worker. */
  companionConnected?: boolean;
  sessionId?: string;
  request?: string;
  currentPurpose?: string;
  requestAvailable?: boolean;
}

export interface CompanionLogEntry {
  id: string;
  timestamp: number;
  level: "info" | "error" | "system";
  source: "companion" | "bridge" | "system";
  message: string;
}

export type CompanionToolCallStatus = "running" | "success" | "error";

export interface CompanionToolCallImageContent {
  type: "image";
  imageUrl: string;
  mimeType: string;
  index: number;
}

export interface CompanionToolCallDebugImage extends CompanionToolCallImageContent {
  label: "before" | "after";
}

export interface CompanionToolCallResponse {
  isError?: boolean;
  images: CompanionToolCallImageContent[];
  debugImages?: CompanionToolCallDebugImage[];
  structuredContent?: { [key: string]: CompanionJsonValue };
}

export interface CompanionToolCall {
  id: string;
  tool: string;
  arguments: CompanionJsonValue;
  rawArguments?: string;
  startedAt: number;
  completedAt?: number;
  durationMs?: number;
  status: CompanionToolCallStatus;
  response?: CompanionToolCallResponse;
  error?: string;
}

export interface CompanionTokenUsageSnapshot extends CompanionTokenUsageMetrics {
  turnId: string;
  updatedAt: number;
  modelContextWindow: number | null;
  model?: string;
  serviceTier?: string;
}

export interface CompanionPlanSnapshot {
  threadId: string;
  turnId: string;
  explanation?: string;
  steps: CompanionPlanStep[];
  updatedAt: number;
}

export interface CompanionState {
  processStatus: CompanionProcessStatus;
  bridgeStatus: BridgeStatus;
  settings: CompanionSettingsSnapshot;
  phone?: PhoneSnapshot;
  lastError?: string;
  logs: CompanionLogEntry[];
  toolCalls: CompanionToolCall[];
  plan?: CompanionPlanSnapshot;
  tokenUsage?: CompanionTokenUsageSnapshot;
}

export interface BridgeCheckResult {
  ok: boolean;
  message: string;
  phone?: PhoneSnapshot;
}

export interface CompanionClientApi {
  getState(): Promise<CompanionState>;
  discoverPhones(): Promise<DiscoveredPhoneSnapshot[]>;
  pairWithDiscoveredPhone(input: PairingDeviceInput): Promise<CompanionState>;
  checkConnection(): Promise<BridgeCheckResult>;
  clearLogs(): Promise<CompanionState>;
  clearToolCalls(): Promise<CompanionState>;
  onState(callback: (state: CompanionState) => void): () => void;
}
