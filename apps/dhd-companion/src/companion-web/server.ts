import { spawn, type ChildProcess } from "node:child_process";
import { randomUUID } from "node:crypto";
import { existsSync, watch } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import http from "node:http";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

import {
  isCompanionPlanEvent,
  isCompanionTokenUsageEvent,
  isCompanionToolCallEvent,
  type CompanionJsonValue,
  type CompanionPlanEvent,
  type CompanionPlanUpdatedEvent,
  type CompanionTokenUsageEvent,
  type CompanionToolCallEvent,
} from "../companion-events.js";
import { parsePort, requestBridge } from "../phone/bridge-client.js";
import {
  DEFAULT_BRIDGE_HOST,
  DEFAULT_BRIDGE_PORT,
  type BridgeMessage,
} from "../phone/protocol.js";
import {
  DEFAULT_PHONE_DISCOVERY_TIMEOUT_MS,
  discoverPhones,
  requestPairingApproval,
  type DiscoveredPhone,
} from "../phone/pairing.js";
import type {
  BridgeCheckResult,
  BridgeStatus,
  CompanionLogEntry,
  CompanionSettingsSnapshot,
  CompanionState,
  CompanionProcessStatus,
  PhoneSnapshot,
  CompanionToolCall,
  CompanionToolCallResponse,
  CompanionPlanSnapshot,
  CompanionTokenUsageSnapshot,
  DiscoveredPhoneSnapshot,
} from "./api.js";
import { errorMessage, toError } from "../shared/errors.js";
import { isPlainRecord, isRecord } from "../shared/guards.js";
import { isMainModule } from "../shared/is-main-module.js";
import {
  bridgeHostSetting,
  bridgePortSetting,
  bridgeTokenSetting,
  companionSettingsPath,
  dashboardHostSetting,
  dashboardPortSetting,
} from "../config/env.js";

export interface ConnectionConfig {
  host: string;
  port: number;
  token: string;
  deviceId?: string;
}

interface StoredConnectionSettings {
  host?: string;
  port?: number;
  token?: string;
  deviceId?: string;
}

const WEB_DIRECTORY = fileURLToPath(new URL(".", import.meta.url));
const PROJECT_ROOT = resolve(WEB_DIRECTORY, "../../");
const COMPANION_SCRIPT_JS = resolve(WEB_DIRECTORY, "../assistant-companion.js");
const COMPANION_SCRIPT_TS = resolve(PROJECT_ROOT, "src/assistant-companion.ts");
const MAX_LOG_ENTRIES = 250;
const MAX_TOOL_CALLS = 50;
const DEFAULT_WEB_PORT = 8766;
const DEFAULT_WEB_HOST = "127.0.0.1";
const BRIDGE_CHECK_TIMEOUT_MS = 5_000;
const BRIDGE_CHECK_TOTAL_TIMEOUT_MS = 15_000;
const PAIRED_DIRECT_CHECK_TIMEOUT_MS = 2_000;
const PAIRED_DIRECT_CHECK_ATTEMPTS = 2;
const BRIDGE_DISCONNECT_GRACE_MS = 30_000;
const BRIDGE_CHECK_ATTEMPTS = 3;
const BRIDGE_CHECK_RETRY_DELAYS_MS = [150, 400] as const;
const COMPANION_DISCONNECT_TIMEOUT_MS = 1_500;
const AUTOMATIC_REDISCOVERY_COOLDOWN_MS = 15_000;
const BRIDGE_HEARTBEAT_INTERVAL_MS = 4_000;
const BRIDGE_OFFLINE_RETRY_DELAYS_MS = [4_000, 8_000, 15_000] as const;
const PHONE_DISCOVERY_TIMEOUT_MS = DEFAULT_PHONE_DISCOVERY_TIMEOUT_MS;
const WORKER_RESTART_DELAY_MS = 1_000;

let connection: ConnectionConfig = initialConnection();
let worker: ChildProcess | null = null;
let workerConnection: ConnectionConfig | null = null;
let workerTransitionInFlight = false;
let connectionTransitionInFlight: Promise<CompanionState> | undefined;
let processStatus: CompanionProcessStatus = "stopped";
let bridgeStatus: BridgeStatus = "unknown";
let phone: PhoneSnapshot | undefined;
let lastError: string | undefined;
let logEntries: CompanionLogEntry[] = [];
let toolCalls: CompanionToolCall[] = [];
let plan: CompanionPlanSnapshot | undefined;
let tokenUsage: CompanionTokenUsageSnapshot | undefined;
let bridgeCheckInFlight: Promise<BridgeCheckResult> | undefined;
let lastAutomaticRediscoveryAt = 0;
let heartbeatRetryAt = 0;
let heartbeatFailureCount = 0;
let consecutiveBridgeCheckFailures = 0;
let consecutiveUnconfirmedWorkerStatuses = 0;
let lastSuccessfulBridgeCheckAt = 0;
let successfulBridgeCheckVersion = 0;
let dashboardActive = false;
let workerRestartTimer: NodeJS.Timeout | undefined;
let discoveredPhoneList: DiscoveredPhone[] = [];
let phoneDiscoveryInFlight: Promise<DiscoveredPhone[]> | undefined;
const toolImages = new Map<string, { bytes: Buffer; mimeType: string }>();
const sseClients = new Set<http.ServerResponse>();

function readEnvPort(): number {
  try {
    return parsePort(bridgePortSetting() ?? `${DEFAULT_BRIDGE_PORT}`);
  } catch {
    return DEFAULT_BRIDGE_PORT;
  }
}

function initialConnection(): ConnectionConfig {
  return {
    host: bridgeHostSetting() ?? DEFAULT_BRIDGE_HOST,
    port: readEnvPort(),
    token: bridgeTokenSetting() ?? ""
  };
}

export async function loadConnection(path = companionSettingsPath()): Promise<ConnectionConfig> {
  const defaults = initialConnection();
  let stored: StoredConnectionSettings = {};
  try {
    stored = JSON.parse(await readFile(path, "utf8")) as StoredConnectionSettings;
  } catch {
    // Default configuration if settings file does not exist
  }

  const storedPort = typeof stored.port === "number" && Number.isInteger(stored.port)
    ? stored.port
    : defaults.port;
  const port = storedPort >= 1 && storedPort <= 65_535 ? storedPort : defaults.port;
  const hasStoredPairing = Boolean(
    typeof stored.deviceId === "string" && stored.deviceId.trim(),
  );
  return {
    // A saved pairing owns the discovered address and token. Environment
    // values are commonly inherited from an older worker/MCP session; letting
    // them override a paired record makes the dashboard probe a stale phone
    // forever after the phone changes networks. Keep env overrides for manual
    // or unpaired configurations.
    host: hasStoredPairing
      ? stored.host?.trim() || DEFAULT_BRIDGE_HOST
      : bridgeHostSetting() || stored.host?.trim() || defaults.host,
    port: hasStoredPairing
      ? port
      : bridgePortSetting() ? defaults.port : port,
    token: hasStoredPairing
      ? stored.token?.trim() || ""
      : bridgeTokenSetting() || stored.token?.trim() || "",
    ...(stored.deviceId ? { deviceId: stored.deviceId.trim() } : {})
  };
}

async function saveConnection(): Promise<void> {
  const stored: StoredConnectionSettings = {
    host: connection.host,
    port: connection.port,
    token: connection.token,
    ...(connection.deviceId ? { deviceId: connection.deviceId } : {})
  };
  await mkdir(dirname(companionSettingsPath()), { recursive: true });
  await writeFile(companionSettingsPath(), `${JSON.stringify(stored, null, 2)}\n`, {
    encoding: "utf8",
    mode: 0o600
  });
}

function settingsSnapshot(): CompanionSettingsSnapshot {
  return {
    host: connection.host,
    port: connection.port,
    tokenConfigured: connection.token.length > 0,
    pairingConfigured: Boolean(connection.deviceId),
    ...(connection.deviceId ? { pairedDeviceId: connection.deviceId } : {})
  };
}

function snapshot(): CompanionState {
  return {
    processStatus,
    bridgeStatus,
    settings: settingsSnapshot(),
    ...(phone ? { phone } : {}),
    ...(lastError ? { lastError } : {}),
    logs: [...logEntries],
    toolCalls: [...toolCalls],
    ...(plan ? { plan } : {}),
    ...(tokenUsage ? { tokenUsage } : {})
  };
}

function publishState(): void {
  const payload = `data: ${JSON.stringify(snapshot())}\n\n`;
  for (const client of sseClients) {
    try {
      client.write(payload);
    } catch {
      sseClients.delete(client);
    }
  }
}

function appendLog(
  message: string,
  options: Pick<CompanionLogEntry, "level" | "source"> = { level: "info", source: "companion" }
): void {
  const trimmed = message.trim();
  if (!trimmed) return;
  logEntries = [
    ...logEntries,
    { id: randomUUID(), timestamp: Date.now(), message: trimmed, ...options }
  ].slice(-MAX_LOG_ENTRIES);
  publishState();
}

function toolImageKey(callId: string, index: number): string {
  return `${callId}:response:${index}`;
}

function toolDebugImageKey(callId: string, index: number): string {
  return `${callId}:debug:${index}`;
}

function toolImageUrl(callId: string, index: number, source: "response" | "debug" = "response"): string {
  const suffix = source === "debug" ? "?source=debug" : "";
  return `/api/tool-calls/${encodeURIComponent(callId)}/images/${index}${suffix}`;
}

function removeToolImages(callId: string): void {
  const prefix = `${callId}:`;
  for (const key of toolImages.keys()) {
    if (key.startsWith(prefix)) toolImages.delete(key);
  }
}

export function toJsonValue(value: unknown): CompanionJsonValue {
  if (value === null) return null;
  if (typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "number") return Number.isFinite(value) ? value : String(value);
  if (Array.isArray(value)) return value.map((item) => toJsonValue(item));
  if (typeof value === "object") {
    const object: { [key: string]: CompanionJsonValue } = {};
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      object[key] = toJsonValue(item);
    }
    return object;
  }
  return String(value);
}

export function decodeImage(value: string): Buffer | undefined {
  const raw = value.trim();
  const dataUrlMatch = raw.match(/^data:([^;,]+);base64,([\s\S]*)$/i);
  const base64 = (dataUrlMatch ? dataUrlMatch[2] : raw).replace(/\s+/g, "");
  if (!base64 || !/^[A-Za-z0-9+/]+={0,2}$/.test(base64) || base64.length % 4 !== 0) {
    return undefined;
  }
  const bytes = Buffer.from(base64, "base64");
  return bytes.length > 0 ? bytes : undefined;
}

export function dashboardToolResponse(
  callId: string,
  value: unknown,
): CompanionToolCallResponse | undefined {
  if (!isRecord(value)) return undefined;
  const result = value;
  removeToolImages(callId);
  const images: CompanionToolCallResponse["images"] = [];
  const rawContent = Array.isArray(result.content) ? result.content : [];

  rawContent.forEach((item, index) => {
    if (!isRecord(item)) return;
    if (
      item.type !== "image" ||
      typeof item.data !== "string" ||
      typeof item.mimeType !== "string" ||
      !item.mimeType.startsWith("image/")
    ) {
      return;
    }
    const bytes = decodeImage(item.data);
    if (!bytes) return;
    toolImages.set(toolImageKey(callId, index), {
      bytes,
      mimeType: item.mimeType
    });
    images.push({
      type: "image",
      imageUrl: toolImageUrl(callId, index),
      mimeType: item.mimeType,
      index
    });
  });

  const response: CompanionToolCallResponse = { images };
  const debugImages: NonNullable<CompanionToolCallResponse["debugImages"]> = [];
  const rawDebugImages = Array.isArray(result.debugImages) ? result.debugImages : [];
  rawDebugImages.forEach((item, index) => {
    if (!isRecord(item)) return;
    if (
      item.type !== "image" ||
      (item.label !== "before" && item.label !== "after") ||
      typeof item.data !== "string" ||
      typeof item.mimeType !== "string" ||
      !item.mimeType.startsWith("image/")
    ) {
      return;
    }
    const bytes = decodeImage(item.data);
    if (!bytes) return;
    toolImages.set(toolDebugImageKey(callId, index), {
      bytes,
      mimeType: item.mimeType
    });
    debugImages.push({
      type: "image",
      label: item.label,
      imageUrl: toolImageUrl(callId, index, "debug"),
      mimeType: item.mimeType,
      index
    });
  });
  if (debugImages.length > 0) response.debugImages = debugImages;
  if (result.isError === true) response.isError = true;
  if (isPlainRecord(result.structuredContent)) {
    response.structuredContent = toJsonValue(result.structuredContent) as { [key: string]: CompanionJsonValue };
  }
  return response;
}

function upsertToolCall(entry: CompanionToolCall): void {
  const existingIndex = toolCalls.findIndex((call) => call.id === entry.id);
  if (existingIndex >= 0) {
    toolCalls = toolCalls.map((call, index) => index === existingIndex ? entry : call);
  } else {
    toolCalls = [...toolCalls, entry];
  }

  while (toolCalls.length > MAX_TOOL_CALLS) {
    const evicted = toolCalls.shift();
    if (evicted) removeToolImages(evicted.id);
  }
}

export function ingestCompanionToolCallEvent(value: unknown): void {
  if (!isCompanionToolCallEvent(value) || !value.tool.startsWith("dhd_")) return;
  const event: CompanionToolCallEvent = value;

  if (event.phase === "started") {
    upsertToolCall({
      id: event.callId,
      tool: event.tool,
      arguments: toJsonValue(event.arguments),
      ...(event.rawArguments ? { rawArguments: event.rawArguments } : {}),
      startedAt: event.timestamp,
      status: "running"
    });
    publishState();
    return;
  }

  const existing = toolCalls.find((call) => call.id === event.callId);
  const startedAt = existing?.startedAt ?? event.completedAt;
  let response: CompanionToolCallResponse | undefined;
  let conversionError: string | undefined;
  if (event.result) {
    try {
      response = dashboardToolResponse(event.callId, event.result);
    } catch (error) {
      conversionError = errorMessage(error);
    }
  }
  const error = event.error || conversionError;
  upsertToolCall({
    id: event.callId,
    tool: event.tool,
    arguments: existing?.arguments ?? {},
    ...(existing?.rawArguments ? { rawArguments: existing.rawArguments } : {}),
    startedAt,
    completedAt: event.completedAt,
    durationMs: Math.max(0, event.completedAt - startedAt),
    status: error || event.result?.isError === true ? "error" : "success",
    ...(response ? { response } : {}),
    ...(error ? { error } : {})
  });
  publishState();
}

export function ingestCompanionTokenUsageEvent(value: unknown): void {
  if (!isCompanionTokenUsageEvent(value)) return;
  const event: CompanionTokenUsageEvent = value;
  tokenUsage = {
    turnId: event.turnId,
    updatedAt: event.timestamp,
    ...event.usage,
    modelContextWindow: event.modelContextWindow,
    ...(event.model ? { model: event.model } : {}),
    ...(event.serviceTier ? { serviceTier: event.serviceTier } : {})
  };
  publishState();
}

export function ingestCompanionPlanEvent(value: unknown): void {
  if (!isCompanionPlanEvent(value)) return;
  const event: CompanionPlanEvent = value;
  if (event.phase === "reset") {
    plan = undefined;
  } else {
    const updated: CompanionPlanUpdatedEvent = event;
    plan = {
      threadId: updated.threadId,
      turnId: updated.turnId,
      ...(updated.explanation ? { explanation: updated.explanation } : {}),
      steps: [...updated.steps],
      updatedAt: updated.timestamp
    };
  }
  publishState();
}

function childOutput(child: ChildProcess, source: "companion" | "bridge"): void {
  for (const stream of [child.stdout, child.stderr]) {
    if (!stream) continue;
    let buffered = "";
    stream.setEncoding("utf8");
    stream.on("data", (chunk: string) => {
      buffered += chunk;
      let newline = buffered.indexOf("\n");
      while (newline >= 0) {
        const line = buffered.slice(0, newline).replace(/\r$/, "");
        buffered = buffered.slice(newline + 1);
        appendLog(line, {
          source,
          level: /error|failed|rejected|could not|timed out/i.test(line) ? "error" : "info"
        });
        newline = buffered.indexOf("\n");
      }
    });
    stream.on("end", () => {
      if (buffered.trim()) appendLog(buffered, { source, level: "info" });
    });
  }
}

function workerEnvironment(): NodeJS.ProcessEnv {
  return {
    ...process.env,
    PHONE_ASSISTANT_BRIDGE_HOST: connection.host,
    PHONE_ASSISTANT_BRIDGE_PORT: String(connection.port),
    PHONE_ASSISTANT_BRIDGE_TOKEN: connection.token,
    ...(process.versions.electron ? { ELECTRON_RUN_AS_NODE: "1" } : {})
  };
}

function getWorkerScript(): { command: string; args: string[] } | null {
  if (existsSync(COMPANION_SCRIPT_JS)) {
    return { command: process.execPath, args: [COMPANION_SCRIPT_JS] };
  }
  if (existsSync(COMPANION_SCRIPT_TS)) {
    return { command: process.execPath, args: ["--import", "tsx", COMPANION_SCRIPT_TS] };
  }
  const distScript = resolve(PROJECT_ROOT, "dist/assistant-companion.js");
  if (existsSync(distScript)) {
    return { command: process.execPath, args: [distScript] };
  }
  return null;
}

function scheduleWorkerRestart(): void {
  if (!dashboardActive || workerTransitionInFlight || processStatus === "stopping" || worker || workerRestartTimer) return;
  appendLog("Companion worker exited unexpectedly; restarting it.", {
    level: "error",
    source: "system",
  });
  workerRestartTimer = setTimeout(() => {
    workerRestartTimer = undefined;
    if (!dashboardActive || worker) return;
    startWorker();
  }, WORKER_RESTART_DELAY_MS);
}

function ensureWorkerRunning(): void {
  if (!dashboardActive || workerTransitionInFlight || processStatus === "stopping") return;
  if (worker && !worker.killed) return;
  if (workerRestartTimer) return;
  startWorker();
}

function startWorker(): CompanionState {
  if (worker && !worker.killed) return snapshot();

  if (workerRestartTimer) {
    clearTimeout(workerRestartTimer);
    workerRestartTimer = undefined;
  }

  const scriptConfig = getWorkerScript();
  if (!scriptConfig) {
    processStatus = "error";
    lastError = "Could not find assistant companion script. Build the project first.";
    appendLog(lastError, { level: "error", source: "system" });
    return snapshot();
  }

  processStatus = "starting";
  if (bridgeStatus === "offline") bridgeStatus = "unknown";
  lastError = undefined;
  consecutiveUnconfirmedWorkerStatuses = 0;
  resetHeartbeatRetry();
  appendLog(`Starting companion worker for ${connection.host}:${connection.port}.`, { level: "system", source: "system" });

  const child = spawn(scriptConfig.command, scriptConfig.args, {
    cwd: PROJECT_ROOT,
    env: workerEnvironment(),
    stdio: ["ignore", "pipe", "pipe", "ipc"],
    windowsHide: true
  });

  worker = child;
  workerConnection = { ...connection };
  child.on("message", (message) => {
    ingestCompanionToolCallEvent(message);
    ingestCompanionTokenUsageEvent(message);
    ingestCompanionPlanEvent(message);
  });
  childOutput(child, "companion");
  child.once("error", (error) => {
    if (worker !== child) return;
    worker = null;
    workerConnection = null;
    processStatus = "error";
    lastError = error.message;
    appendLog(`Companion worker failed: ${error.message}`, { level: "error", source: "system" });
    publishState();
    scheduleWorkerRestart();
  });
  child.once("exit", (code, signal) => {
    // A forced stop can finish before the old child emits its exit event. If
    // a replacement worker has already been installed, this callback is
    // stale and must not overwrite the replacement's state.
    if (worker !== child) return;
    worker = null;
    workerConnection = null;
    const expected = processStatus === "stopping";
    processStatus = expected || code === 0 ? "stopped" : "error";
    if (!expected && code !== 0) {
      lastError = `Companion worker exited with ${code === null ? signal ?? "unknown signal" : `code ${code}`}.`;
    }
    appendLog(
      `Companion worker ${expected ? "stopped" : "exited"}${code === null ? ` (${signal ?? "unknown"})` : ` (code ${code})`}.`,
      { level: expected || code === 0 ? "system" : "error", source: "system" }
    );
    if (!expected) {
      bridgeStatus = "offline";
      resetHeartbeatRetry();
      publishState();
      void releaseCompanionPresence()
        .finally(() => scheduleWorkerRestart())
        .catch(() => {});
    }
  });
  processStatus = "running";
  appendLog("Companion worker is running.", { level: "system", source: "system" });
  if (connection.token) {
    void checkConnection({ silent: true }).catch(() => {});
  }
  return snapshot();
}

async function stopWorker(
  reason = "requested",
  checkToIgnore?: Promise<BridgeCheckResult>,
): Promise<CompanionState> {
  if (workerRestartTimer) {
    clearTimeout(workerRestartTimer);
    workerRestartTimer = undefined;
  }
  const child = worker;
  if (!child || child.killed) {
    worker = null;
    workerConnection = null;
    processStatus = "stopped";
    const target = connection;
    await releaseCompanionPresence(target, checkToIgnore);
    if (connection === target) {
      bridgeStatus = "offline";
      resetHeartbeatRetry();
      publishState();
    }
    return snapshot();
  }
  const target = connection;
  processStatus = "stopping";
  publishState();
  appendLog(`Stopping companion worker (${reason}).`, { level: "system", source: "system" });
  await new Promise<void>((resolveStop) => {
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      resolveStop();
    };
    child.once("exit", finish);
    child.kill();
    setTimeout(() => {
      if (!settled) {
        child.kill("SIGKILL");
        finish();
      }
    }, 3_000);
  });
  await releaseCompanionPresence(target, checkToIgnore);
  if (connection === target) {
    bridgeStatus = "offline";
    resetHeartbeatRetry();
    publishState();
  }
  return snapshot();
}

function clearLogs(): CompanionState {
  logEntries = [];
  publishState();
  return snapshot();
}

function clearToolCalls(): CompanionState {
  toolCalls = [];
  toolImages.clear();
  publishState();
  return snapshot();
}

function bridgeOptions(timeoutMs: number, target: ConnectionConfig = connection) {
  return {
    host: target.host,
    port: target.port,
    token: target.token || undefined,
    timeoutMs
  };
}

function statusCheckError(result: BridgeMessage): Error {
  return new Error(
    typeof result.message === "string"
      ? result.message
      : "The phone bridge rejected the status check."
  );
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolveWait) => setTimeout(resolveWait, milliseconds));
}

const BRIDGE_CHECK_DEADLINE_MESSAGE = "Timed out checking the phone assistant bridge.";

function remainingCheckTime(deadlineAt: number | undefined, maximumMs: number): number {
  if (deadlineAt === undefined) return maximumMs;
  const remainingMs = deadlineAt - Date.now();
  if (remainingMs <= 0) throw new Error(BRIDGE_CHECK_DEADLINE_MESSAGE);
  return Math.min(maximumMs, remainingMs);
}

async function waitForCheckRetry(milliseconds: number, deadlineAt: number | undefined): Promise<void> {
  await wait(remainingCheckTime(deadlineAt, milliseconds));
}

function awaitBeforeCheckDeadline<T>(operation: Promise<T>, deadlineAt: number | undefined): Promise<T> {
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

function resetHeartbeatRetry(): void {
  heartbeatRetryAt = 0;
  heartbeatFailureCount = 0;
  consecutiveBridgeCheckFailures = 0;
}

function scheduleHeartbeatRetry(): void {
  const delayIndex = Math.min(heartbeatFailureCount, BRIDGE_OFFLINE_RETRY_DELAYS_MS.length - 1);
  heartbeatRetryAt = Date.now() + BRIDGE_OFFLINE_RETRY_DELAYS_MS[delayIndex];
  heartbeatFailureCount += 1;
}

/**
 * A status request is intentionally small, but the first packet after a
 * phone/network transition can still be lost. Retry the request on a fresh
 * socket so one stale TCP attempt cannot make a healthy phone look offline.
 */
async function requestStatusWithRetry(
  target: ConnectionConfig,
  options: { deadlineAt?: number; attempts?: number; timeoutMs?: number } = {},
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

/** Tell the phone that the worker owning the liveness lease has stopped. */
async function releaseCompanionPresence(
  target: ConnectionConfig = connection,
  checkToIgnore?: Promise<BridgeCheckResult>,
): Promise<void> {
  // An unpaired target has no worker lease to release. Non-loopback targets
  // also require a token, which is guaranteed after pairing.
  if (!target.token) return;

  // A dashboard health check may already be in flight when worker shutdown
  // begins. Let it finish before sending the release so its authenticated
  // request cannot arrive after the release and immediately make the phone
  // connected.
  const inFlightCheck = bridgeCheckInFlight;
  if (inFlightCheck && inFlightCheck !== checkToIgnore) await inFlightCheck.catch(() => {});

  try {
    const result = await requestBridge(
      { type: "companion_disconnected", requestId: randomUUID() },
      bridgeOptions(COMPANION_DISCONNECT_TIMEOUT_MS, target),
    );
    if (result.ok !== true) throw statusCheckError(result);
  } catch (error) {
    // The lease timeout remains the fallback when the phone is already
    // unreachable. Stopping the local worker should still complete promptly.
    appendLog(`Could not release phone companion presence: ${errorMessage(error)}`, {
      level: "info",
      source: "bridge",
    });
  }
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

function discoveredPhoneSnapshot(value: DiscoveredPhone): DiscoveredPhoneSnapshot {
  return {
    deviceId: value.deviceId,
    deviceName: value.deviceName,
    ...(value.model ? { model: value.model } : {})
  };
}

function sameConnection(left: ConnectionConfig, right: ConnectionConfig): boolean {
  return left.host === right.host &&
    left.port === right.port &&
    left.token === right.token &&
    left.deviceId === right.deviceId;
}

async function discoverPhonesOnNetwork(timeoutMs = PHONE_DISCOVERY_TIMEOUT_MS): Promise<DiscoveredPhone[]> {
  if (phoneDiscoveryInFlight) return phoneDiscoveryInFlight;
  const operation = discoverPhones({ timeoutMs });
  phoneDiscoveryInFlight = operation;
  try {
    discoveredPhoneList = await operation;
    return discoveredPhoneList;
  } finally {
    if (phoneDiscoveryInFlight === operation) phoneDiscoveryInFlight = undefined;
  }
}

async function rediscoverPairedDevice(
  target: ConnectionConfig,
  deadlineAt?: number,
): Promise<CompanionState> {
  if (!target.deviceId || !target.token) {
    throw new Error("The saved phone pairing is incomplete.");
  }
  const phones = await awaitBeforeCheckDeadline(
    discoverPhonesOnNetwork(remainingCheckTime(deadlineAt, PHONE_DISCOVERY_TIMEOUT_MS)),
    deadlineAt,
  );
  const discovered = phones.find((candidate) => candidate.deviceId === target.deviceId);
  if (!discovered) {
    throw new Error("No paired DHD phone answered on the local network. Make sure the phone and computer are on the same network.");
  }

  return connectDiscoveredPairedDevice(target, discovered, deadlineAt);
}

function workerTargetsConnection(target: ConnectionConfig): boolean {
  return Boolean(worker && !worker.killed && workerConnection && sameConnection(workerConnection, target));
}

function recordPhoneStatus(result: BridgeMessage, target: ConnectionConfig): PhoneSnapshot {
  phone = phoneSnapshot(result);
  const workerConfirmed = phone.companionConnected === true &&
    (!dashboardActive || workerTargetsConnection(target));
  consecutiveUnconfirmedWorkerStatuses = workerConfirmed ? 0 : consecutiveUnconfirmedWorkerStatuses + 1;
  bridgeStatus = workerConfirmed
    ? "connected"
    : consecutiveUnconfirmedWorkerStatuses >= 3 ? "offline" : "checking";
  lastError = undefined;
  lastAutomaticRediscoveryAt = 0;
  lastSuccessfulBridgeCheckAt = Date.now();
  successfulBridgeCheckVersion += 1;
  resetHeartbeatRetry();
  return phone;
}

function newerConnectionForSamePhone(expected: ConnectionConfig, deviceId: string | undefined): boolean {
  return Boolean(deviceId) && connection !== expected && connection.deviceId === deviceId && lastSuccessfulBridgeCheckAt > 0;
}

async function connectDiscoveredPairedDevice(
  target: ConnectionConfig,
  discovered: DiscoveredPhone,
  deadlineAt?: number,
): Promise<CompanionState> {
  let lastStatusError: unknown;
  const addresses = [...new Set([discovered.host, ...discovered.addresses])];
  for (const host of addresses) {
    const candidate: ConnectionConfig = {
      host,
      port: discovered.port,
      token: target.token,
      deviceId: target.deviceId
    };
    try {
      const result = await requestStatusWithRetry(candidate, { deadlineAt });
      return applyPairedConnection(candidate, result, "reconnected", target);
    } catch (error) {
      if (target.deviceId && newerConnectionForSamePhone(target, target.deviceId)) return snapshot();
      lastStatusError = error;
    }
  }
  throw (lastStatusError instanceof Error
    ? lastStatusError
    : new Error("The paired DHD phone was discovered but did not accept the saved token."));
}

function checkConnection(options: { silent?: boolean } = {}): Promise<BridgeCheckResult> {
  // Startup, the heartbeat, and the initial page load can all ask for the same
  // probe. Share one operation so an older failure cannot
  // overwrite a newer success or produce a misleading red toast.
  if (bridgeCheckInFlight) return bridgeCheckInFlight;

  const operation = performConnectionCheck(options);
  bridgeCheckInFlight = operation;
  void operation.then(
    () => {
      if (bridgeCheckInFlight === operation) bridgeCheckInFlight = undefined;
    },
    () => {
      if (bridgeCheckInFlight === operation) bridgeCheckInFlight = undefined;
    }
  );
  return operation;
}

async function performConnectionCheck(options: { silent?: boolean } = {}): Promise<BridgeCheckResult> {
  const target = connection;
  const successVersionAtStart = successfulBridgeCheckVersion;
  const previousBridgeStatus = bridgeStatus;
  const previousPhone = phone;
  const deadlineAt = Date.now() + BRIDGE_CHECK_TOTAL_TIMEOUT_MS;
  // Heartbeats are recovery probes, not user actions. Keep their in-flight
  // state internal so the dashboard does not flash CHECKING every few
  // seconds while the worker remains healthy.
  if (!options.silent) {
    bridgeStatus = "checking";
    lastError = undefined;
    publishState();
  }
  try {
    const result = await requestStatusWithRetry(
      target,
      target.deviceId && target.token
        ? { deadlineAt, attempts: PAIRED_DIRECT_CHECK_ATTEMPTS, timeoutMs: PAIRED_DIRECT_CHECK_TIMEOUT_MS }
        : { deadlineAt },
    );
    if (connection !== target) {
      return { ok: false, message: "Connection settings changed while checking; check the new link." };
    }
    const nextPhone = phoneSnapshot(result);
    const phoneChanged = JSON.stringify(nextPhone) !== JSON.stringify(previousPhone);
    recordPhoneStatus(result, target);
    const linkReady = bridgeStatus === "connected";
    if (!options.silent || previousBridgeStatus !== bridgeStatus || phoneChanged) {
      const requestAvailability = nextPhone.requestAvailable === undefined
        ? ""
        : `; request available: ${nextPhone.requestAvailable}`;
      appendLog(linkReady
        ? `Phone bridge check passed; phone session state: ${nextPhone.state}${requestAvailability}.`
        : bridgeStatus === "checking"
          ? "Phone bridge responds; waiting for the companion worker to connect."
          : "Phone bridge responds, but the companion worker is not connected.",
      { level: "system", source: "bridge" });
    } else {
      publishState();
    }
    return {
      ok: linkReady,
      message: linkReady
        ? "Phone companion connected."
        : bridgeStatus === "checking"
          ? "Waiting for the companion worker to connect."
          : "The phone is reachable, but the companion worker is not connected.",
      phone: nextPhone,
    };
  } catch (error) {
    if (connection !== target) {
      return { ok: false, message: "Connection settings changed while checking; check the new link." };
    }
    if (successfulBridgeCheckVersion !== successVersionAtStart) {
      return {
        ok: bridgeStatus === "connected",
        message: "A newer phone connection check has finished.",
        phone,
      };
    }
    const directMessage = errorMessage(error);
    consecutiveBridgeCheckFailures += 1;
    const firstMissOnSavedConnection = consecutiveBridgeCheckFailures === 1 &&
      (previousBridgeStatus === "connected" || (previousBridgeStatus === "unknown" && Boolean(target.token)));
    const recentSuccess = lastSuccessfulBridgeCheckAt > 0 &&
      Date.now() - lastSuccessfulBridgeCheckAt < BRIDGE_DISCONNECT_GRACE_MS;
    if (firstMissOnSavedConnection) {
      bridgeStatus = "checking";
      lastError = undefined;
      appendLog("Phone bridge missed one check; confirming before marking it disconnected.", {
        level: "info",
        source: "bridge",
      });
      return { ok: false, message: "Rechecking the phone connection after a missed response." };
    }
    const canRediscover = Date.now() < deadlineAt &&
      Boolean(target.deviceId && target.token) &&
      (!options.silent || Date.now() - lastAutomaticRediscoveryAt >= AUTOMATIC_REDISCOVERY_COOLDOWN_MS);
    if (canRediscover) {
      if (options.silent) lastAutomaticRediscoveryAt = Date.now();
      try {
        const nextState = await rediscoverPairedDevice(target, deadlineAt);
        return {
          ok: nextState.bridgeStatus === "connected",
          message: "Phone bridge rediscovered on the local network.",
          phone: nextState.phone
        };
      } catch (rediscoveryError) {
        const rediscoveryMessage = errorMessage(rediscoveryError);
        lastError = `${directMessage} Pairing rediscovery failed: ${rediscoveryMessage}`;
      }
    } else {
      lastError = directMessage;
    }
    if (recentSuccess && previousBridgeStatus !== "offline") {
      // Rediscovery can repair a changed address, but a failed network probe
      // alone does not revoke a recently verified connection.
      bridgeStatus = "checking";
      lastError = undefined;
      publishState();
      return { ok: false, message: "Rechecking the phone connection after a missed response." };
    }
    bridgeStatus = "offline";
    if (!options.silent || previousBridgeStatus !== "offline") {
      appendLog(lastError, { level: "error", source: "bridge" });
    } else {
      publishState();
    }
    return { ok: false, message: lastError };
  }
}

async function applyPairedConnection(
  candidate: ConnectionConfig,
  result: BridgeMessage,
  logVerb: string,
  expectedConnection?: ConnectionConfig,
): Promise<CompanionState> {
  if (connectionTransitionInFlight) {
    await connectionTransitionInFlight.catch(() => {});
  }
  if (expectedConnection && connection !== expectedConnection) {
    if (newerConnectionForSamePhone(expectedConnection, candidate.deviceId)) return snapshot();
    throw new Error("Connection settings changed while rediscovering the phone; keeping the newer settings.");
  }

  const operation = (async (): Promise<CompanionState> => {
    const workerTargetMatchesCandidate = !worker || worker.killed || workerTargetsConnection(candidate);
    if (sameConnection(connection, candidate) && workerTargetMatchesCandidate) {
      if (dashboardActive && (!worker || worker.killed)) startWorker();
      const nextPhone = recordPhoneStatus(result, candidate);
      appendLog(`Phone bridge reconnected; state: ${nextPhone.state}.`, { level: "system", source: "bridge" });
      return snapshot();
    }

    workerTransitionInFlight = true;
    try {
      const wasRunning = Boolean(worker && !worker.killed);
      if (wasRunning) await stopWorker("phone pairing changed", bridgeCheckInFlight);
      if (expectedConnection && connection !== expectedConnection) {
        if (newerConnectionForSamePhone(expectedConnection, candidate.deviceId)) return snapshot();
        if (dashboardActive || wasRunning) startWorker();
        throw new Error("Connection settings changed while rediscovering the phone; keeping the newer settings.");
      }
      connection = candidate;
      await saveConnection();
      // The worker must use the new address before the UI can report that
      // this desktop is connected. A status request alone does not renew the
      // phone's companion presence lease.
      if (dashboardActive || wasRunning) startWorker();
      const nextPhone = recordPhoneStatus(result, candidate);
      appendLog(`Phone pairing ${logVerb}; the companion discovered the phone automatically; state: ${nextPhone.state}.`, {
        level: "system",
        source: "bridge",
      });
      return snapshot();
    } finally {
      workerTransitionInFlight = false;
    }
  })();
  connectionTransitionInFlight = operation;
  try {
    return await operation;
  } finally {
    if (connectionTransitionInFlight === operation) connectionTransitionInFlight = undefined;
  }
}

async function pairWithDiscoveredDevice(value: unknown): Promise<CompanionState> {
  if (!isRecord(value) || typeof value.deviceId !== "string") {
    throw new Error("A discovered phone must be selected.");
  }
  const deviceId = value.deviceId.trim();
  if (!deviceId) throw new Error("A discovered phone must be selected.");
  const expectedConnection = connection;
  const replacePairing = value.replacePairing === true;
  if (targetHasSavedPairing(expectedConnection, deviceId) && !replacePairing && bridgeStatus === "connected") {
    return snapshot();
  }

  // Refresh before pairing so the nonce and address belong to a recent LAN
  // response rather than a stale browser list.
  const phones = await discoverPhonesOnNetwork();
  const selected = phones.find((candidate) => candidate.deviceId === deviceId);
  if (!selected) {
    if (newerConnectionForSamePhone(expectedConnection, deviceId)) return snapshot();
    throw new Error("That phone is no longer visible on the local network. Refresh the phone list and try again.");
  }

  if (targetHasSavedPairing(expectedConnection, deviceId) && !replacePairing) {
    try {
      return await connectDiscoveredPairedDevice(expectedConnection, selected);
    } catch (error) {
      if (newerConnectionForSamePhone(expectedConnection, deviceId)) return snapshot();
      throw new Error(`Could not reconnect with the saved pairing: ${errorMessage(error)} Choose Pair again if the phone no longer accepts this computer.`);
    }
  }

  const offer = await requestPairingApproval(selected);
  const candidate: ConnectionConfig = {
    host: offer.host,
    port: offer.port,
    token: offer.token,
    deviceId: offer.deviceId
  };
  const result = await requestStatusWithRetry(candidate);
  return applyPairedConnection(candidate, result, "paired", expectedConnection);
}

function targetHasSavedPairing(target: ConnectionConfig, deviceId: string): boolean {
  return target.deviceId === deviceId && Boolean(target.token);
}

async function readRequestBody(req: http.IncomingMessage): Promise<unknown> {
  return new Promise((resolveBody, rejectBody) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1_000_000) {
        req.destroy();
        rejectBody(new Error("Request body too large."));
      }
    });
    req.on("end", () => {
      try {
        resolveBody(body ? JSON.parse(body) : {});
      } catch {
        rejectBody(new Error("Invalid JSON body."));
      }
    });
    req.on("error", rejectBody);
  });
}

function getContentType(path: string): string {
  if (path.endsWith(".html")) return "text/html; charset=utf-8";
  if (path.endsWith(".css")) return "text/css; charset=utf-8";
  if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
  if (path.endsWith(".json")) return "application/json; charset=utf-8";
  if (path.endsWith(".png")) return "image/png";
  if (path.endsWith(".svg")) return "image/svg+xml";
  return "text/plain; charset=utf-8";
}

const BROWSER_MODULES = new Set(["renderer", "api", "pricing", "tool-images"]);

function browserModuleName(pathname: string): string | undefined {
  const name = /^\/([a-z-]+)\.(?:js|ts)$/.exec(pathname)?.[1];
  return name && BROWSER_MODULES.has(name) ? name : undefined;
}

function transpileTsFile(tsCode: string): string {
  return ts.transpileModule(tsCode, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      esModuleInterop: true,
      sourceMap: false
    }
  }).outputText;
}

async function serveStaticFile(res: http.ServerResponse, fileName: string): Promise<void> {
  // If a JS module is requested, check if a corresponding TS source exists and transpile on-the-fly
  if (fileName.endsWith(".js")) {
    const tsFileName = fileName.replace(/\.js$/, ".ts");
    const srcTsPath = resolve(PROJECT_ROOT, "src/companion-web", tsFileName);
    if (existsSync(srcTsPath)) {
      try {
        const tsCode = await readFile(srcTsPath, "utf8");
        const jsCode = transpileTsFile(tsCode);
        res.writeHead(200, {
          "Content-Type": "application/javascript; charset=utf-8",
          "Cache-Control": "no-cache, no-store, must-revalidate",
          "Pragma": "no-cache",
          "Expires": "0"
        });
        res.end(jsCode);
        return;
      } catch (err) {
        console.error(`Failed to transpile ${tsFileName}:`, err);
      }
    }
  }

  const candidatePaths = [
    resolve(PROJECT_ROOT, "src/companion-web", fileName),
    resolve(WEB_DIRECTORY, fileName),
    resolve(PROJECT_ROOT, "dist/companion-web", fileName)
  ];

  for (const filePath of candidatePaths) {
    if (existsSync(filePath)) {
      try {
        const content = await readFile(filePath);
        res.writeHead(200, {
          "Content-Type": getContentType(fileName),
          "Cache-Control": "no-cache, no-store, must-revalidate",
          "Pragma": "no-cache",
          "Expires": "0"
        });
        res.end(content);
        return;
      } catch {
        // try next
      }
    }
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("File not found");
}

export function createCompanionWebServer(): http.Server {
  return http.createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", `http://${req.headers.host || "localhost"}`);
    const pathname = url.pathname;

    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type");

    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    if (pathname === "/api/events" && req.method === "GET") {
      res.writeHead(200, {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive"
      });
      res.write(`data: ${JSON.stringify(snapshot())}\n\n`);
      sseClients.add(res);

      req.on("close", () => {
        sseClients.delete(res);
      });
      return;
    }

    const toolImageMatch = pathname.match(/^\/api\/tool-calls\/([^/]+)\/images\/(\d+)$/);
    if (toolImageMatch && req.method === "GET") {
      let callId: string;
      try {
        callId = decodeURIComponent(toolImageMatch[1]);
      } catch {
        res.writeHead(404, { "Content-Type": "text/plain" });
        res.end("Image not found");
        return;
      }
      const imageIndex = Number(toolImageMatch[2]);
      const source = url.searchParams.get("source") === "debug" ? "debug" : "response";
      const image = Number.isSafeInteger(imageIndex)
        ? toolImages.get(source === "debug"
          ? toolDebugImageKey(callId, imageIndex)
          : toolImageKey(callId, imageIndex))
        : undefined;
      if (!image) {
        res.writeHead(404, { "Content-Type": "text/plain" });
        res.end("Image not found");
        return;
      }
      res.writeHead(200, {
        "Content-Type": image.mimeType,
        "Content-Length": image.bytes.length,
        "Cache-Control": "no-store"
      });
      res.end(image.bytes);
      return;
    }

    if (pathname === "/api/state" && req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
      res.end(JSON.stringify(snapshot()));
      return;
    }

    if (pathname === "/api/discover" && req.method === "POST") {
      try {
        const phones = await discoverPhonesOnNetwork();
        const response: { phones: DiscoveredPhoneSnapshot[] } = {
          phones: phones.map(discoveredPhoneSnapshot)
        };
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(response));
      } catch (err) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: errorMessage(err) }));
      }
      return;
    }

    if (pathname === "/api/pair-device" && req.method === "POST") {
      try {
        const body = await readRequestBody(req);
        const nextState = await pairWithDiscoveredDevice(body);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(nextState));
      } catch (err) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: errorMessage(err) }));
      }
      return;
    }

    if (pathname === "/api/check" && req.method === "POST") {
      try {
        // The dashboard is the worker's owner. A manual health check should
        // also recover a worker that exited while the dashboard stayed open.
        ensureWorkerRunning();
        const result = await checkConnection();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(result));
      } catch (err) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, message: errorMessage(err) }));
      }
      return;
    }

    if (pathname === "/api/clear-logs" && req.method === "POST") {
      try {
        const nextState = clearLogs();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(nextState));
      } catch (err) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: errorMessage(err) }));
      }
      return;
    }

    if (pathname === "/api/clear-tool-calls" && req.method === "POST") {
      try {
        const nextState = clearToolCalls();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(nextState));
      } catch (err) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: errorMessage(err) }));
      }
      return;
    }

    if (pathname === "/" || pathname === "/index.html") {
      return serveStaticFile(res, "index.html");
    }
    if (pathname === "/styles.css") {
      return serveStaticFile(res, "styles.css");
    }
    if (pathname === "/favicon.png") {
      return serveStaticFile(res, "favicon.png");
    }
    const browserModule = browserModuleName(pathname);
    if (browserModule) {
      return serveStaticFile(res, `${browserModule}.js`);
    }
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("Not Found");
  });
}

let heartbeatTimer: NodeJS.Timeout | undefined;
let fileWatcher: ReturnType<typeof watch> | undefined;
let reloadDebounceTimer: NodeJS.Timeout | undefined;

function notifyClientsReload(type: "css" | "full", file?: string): void {
  const payload = `event: reload\ndata: ${JSON.stringify({ type, file, timestamp: Date.now() })}\n\n`;
  for (const client of sseClients) {
    try {
      client.write(payload);
    } catch {
      sseClients.delete(client);
    }
  }
}

function startFileWatcher(): void {
  if (fileWatcher) return;
  const srcWebDir = resolve(PROJECT_ROOT, "src/companion-web");
  if (!existsSync(srcWebDir)) return;

  try {
    fileWatcher = watch(srcWebDir, { recursive: true }, (_eventType, filename) => {
      if (!filename) return;
      if (filename.includes("tsconfig") || filename.endsWith(".tmp")) return;

      if (reloadDebounceTimer) clearTimeout(reloadDebounceTimer);
      reloadDebounceTimer = setTimeout(async () => {
        const isCss = filename.endsWith(".css");
        const isHtml = filename.endsWith(".html");

        // Sync static assets to dist if dist exists
        const distWebDir = resolve(PROJECT_ROOT, "dist/companion-web");
        if (existsSync(distWebDir)) {
          try {
            const srcFile = resolve(srcWebDir, filename);
            if (existsSync(srcFile) && (isCss || isHtml || filename.endsWith(".png"))) {
              await writeFile(resolve(distWebDir, filename), await readFile(srcFile));
            }
          } catch {
            // best effort copy
          }
        }

        notifyClientsReload(isCss ? "css" : "full", filename);
      }, 80);
    });
  } catch (err) {
    console.error("Failed to start file watcher:", err);
  }
}

function startHeartbeat(): void {
  if (heartbeatTimer) return;
  heartbeatTimer = setInterval(async () => {
    if (!dashboardActive) return;
    if (!worker || worker.killed || processStatus === "stopped" || processStatus === "error") {
      ensureWorkerRunning();
      return;
    }
    if (processStatus !== "running") return;
    if (bridgeCheckInFlight) return;
    if (!connection.token) return;
    if (Date.now() < heartbeatRetryAt) return;
    try {
      const result = await checkConnection({ silent: true });
      if (result.ok) resetHeartbeatRetry();
      else if (bridgeStatus !== "checking") scheduleHeartbeatRetry();
    } catch {
      scheduleHeartbeatRetry();
    }
  }, BRIDGE_HEARTBEAT_INTERVAL_MS);
}

export async function startCompanionWebServer(port = DEFAULT_WEB_PORT, host = DEFAULT_WEB_HOST): Promise<http.Server> {
  connection = await loadConnection();
  startHeartbeat();
  startFileWatcher();
  const server = createCompanionWebServer();

  return new Promise((resolveReady, rejectReady) => {
    server.once("error", rejectReady);
    server.listen(port, host, () => {
      // The dashboard owns the worker lifecycle. Users only need to launch
      // the dashboard; worker start/stop is intentionally not a dashboard
      // action.
      dashboardActive = true;
      startWorker();
      console.log(`\n  ======================================================`);
      console.log(`  DHD Companion Web App running at:`);
      console.log(`  http://${host}:${port}`);
      console.log(`  ======================================================\n`);
      resolveReady(server);
    });
  });
}

let dashboardShutdownPromise: Promise<void> | undefined;

function shutdownDashboard(exitCode: number): void {
  if (dashboardShutdownPromise) return;
  dashboardActive = false;
  dashboardShutdownPromise = stopWorker("dashboard shutdown")
    .catch((error: unknown) => {
      console.error(
        "Failed to stop the companion worker during dashboard shutdown:",
        errorMessage(error),
      );
    })
    .then(() => {
      process.exitCode = exitCode;
      process.exit();
    });
}

if (isMainModule("server")) {
  const port = Number(dashboardPortSetting() || DEFAULT_WEB_PORT);
  const host = dashboardHostSetting() || DEFAULT_WEB_HOST;
  process.once("SIGINT", () => shutdownDashboard(0));
  process.once("SIGTERM", () => shutdownDashboard(0));
  startCompanionWebServer(port, host).catch((err) => {
    console.error("Failed to start companion web server:", err);
    process.exit(1);
  });
}
