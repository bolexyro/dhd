import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import * as readline from "node:readline";

import {
  emitCompanionPlanEvent,
  emitCompanionTokenUsageEvent,
} from "./companion-events.js";
import {
  bridgeHost,
  bridgePort,
  isLoopbackBridgeHost,
  requestBridge,
} from "./phone/bridge-client.js";
import type { BridgeMessage } from "./phone/protocol.js";
import { errorMessage, toError } from "./shared/errors.js";
import { asRecord } from "./shared/guards.js";
import { isMainModule } from "./shared/is-main-module.js";
import {
  codexHomeDirectory,
  codexRuntimeDirectory,
  isDebugTimingEnabled,
  pollIntervalSetting,
} from "./config/env.js";
import {
  extractCompanionPlanUpdatedEvent,
  extractCompanionTokenUsageEvent,
  extractText,
  extractThreadId,
  extractTurnId,
} from "./codex/extract.js";
import {
  recordAgentMessageCompleted,
  recordAgentMessageDelta,
  recordAgentMessageStarted,
  selectFinalAgentMessageText,
  type AgentMessageState,
  type AgentMessageStreamUpdate,
} from "./codex/agent-messages.js";
import {
  DEFAULT_CODEX_SERVICE_TIER,
  normalizeCodexEffort,
  resolveCodexEffort,
  resolveCodexModel,
  serviceTierForFastMode,
} from "./codex/settings.js";
import { spawnAppServerProcess, type AppServerSpawner } from "./codex/process.js";
import { PhaseTimer, logCompanionPhase } from "./shared/timing.js";
import {
  buildDhdDynamicTools,
  extractDynamicToolFailure,
  extractDynamicToolName,
  handleDynamicToolCall,
  type DynamicToolCallResponse,
  type PhoneToolFailure,
} from "./codex/dynamic-tools.js";
import { answerServerRequest } from "./codex/server-requests.js";
import { JsonRpcConnection, type JsonRpcMessage } from "./codex/json-rpc.js";
import {
  logServerNotification,
  startedThreadId,
  turnCompletedStatus,
  turnCompletionError,
  turnFailureError,
  unloadedThreadId,
} from "./codex/notifications.js";

const DEFAULT_POLL_INTERVAL_MS = 1_000;
const BRIDGE_POLL_TIMEOUT_MS = 5_000;
const COMPANION_HEARTBEAT_INTERVAL_MS = 2_500;
const COMPANION_HEARTBEAT_TIMEOUT_MS = 4_000;
const STREAM_BRIDGE_TIMEOUT_MS = 5_000;
const MAX_AGENT_FEEDBACK_CHARS = 4_000;
const MAX_STEER_CHARS = 4_000;
const DEFAULT_COMPLETION_MESSAGE = "Your DHD task is ready to review.";
const PREWARM_ATTEMPTS = 2;
const PREWARM_RETRY_DELAY_MS = 500;
interface TurnResult {
  text: string;
  threadId: string;
  phoneToolFailures: PhoneToolFailure[];
}

export interface CodexAppServerClientOptions {
  spawnAppServer?: AppServerSpawner;
}

/**
 * Persistent Codex App Server client. Authentication stays in the Codex
 * CLI/App Server; this process never handles ChatGPT cookies or API keys.
 * DHD gives the child App Server its own Codex home so global coding-agent
 * configuration and credentials do not leak into phone turns.
 */
export class CodexAppServerClient {
  private child: ChildProcessWithoutNullStreams | null = null;
  private reader: readline.Interface | null = null;
  private readonly rpc = new JsonRpcConnection((line) => this.writeToAppServer(line), {
    onRequest: (message) => void this.handleServerRequest(message),
    onNotification: (message) => this.handleNotification(message),
  });
  private startPromise: Promise<void> | null = null;
  private initialized = false;
  private loadedThreadIds = new Set<string>();
  private readonly codexHome = codexHomeDirectory();
  private readonly runtimeCwd = codexRuntimeDirectory();
  private turnCompletion: {
    resolve: (result: TurnResult) => void;
    reject: (error: Error) => void;
    agentMessages: Map<string, AgentMessageState>;
    nextAgentMessageOrder: number;
    phoneToolFailures: PhoneToolFailure[];
    onAgentMessageDelta?: (update: AgentMessageStreamUpdate) => void;
  } | null = null;
  private activeThreadId: string | null = null;
  private activeDhdThreadId: string | null = null;
  // Only trust the loaded-thread cache after this companion has established
  // the current DHD tool contract. Persisted ids from a previous process must
  // still go through thread/resume below.
  private hasCurrentDhdThread = false;
  private activeTurnId: string | null = null;
  private interruptRequested = false;
  private turnRequested = false;
  private activeTiming: PhaseTimer | null = null;
  private userMessageLogged = false;
  private activeModel = resolveCodexModel();
  private activeServiceTier = DEFAULT_CODEX_SERVICE_TIER;
  private readonly spawnAppServer: AppServerSpawner;

  constructor(options: CodexAppServerClientOptions = {}) {
    this.spawnAppServer = options.spawnAppServer ?? spawn;
  }

  /** True while this client still owns an in-flight App Server turn. */
  get isTurnInFlight(): boolean {
    // A turn is also in flight while initialize/resume/thread-start is still
    // running. The completion promise is created only after initialize, so
    // relying on it alone loses a phone-side Stop during that handoff.
    return this.turnRequested || this.turnCompletion !== null;
  }

  /** True when the active thread and turn ids are available for steering. */
  get canSteer(): boolean {
    return (
      this.isTurnInFlight && Boolean(this.activeThreadId && this.activeTurnId)
    );
  }

  /**
   * Start and initialize one App Server connection. The connection remains
   * alive after a turn so later requests can reuse its loaded thread state.
   */
  async start(timing?: PhaseTimer): Promise<void> {
    if (this.initialized && this.child) {
      timing?.log("connection:reuse", `pid=${this.child.pid ?? "?"}`);
      return;
    }
    if (this.startPromise) return this.startPromise;

    const logger = timing ?? new PhaseTimer("codex-connection");
    this.startPromise = (async () => {
      if (this.child) await this.stopProcess();
      logger.log(
        "spawn:start",
        `cwd=${this.runtimeCwd} codexHome=${this.codexHome}`,
      );
      this.startProcess();
      logger.log("spawn:complete", `pid=${this.child?.pid ?? "?"}`);
      logger.log("initialize:start");
      try {
        await this.rpc.request("initialize", {
          clientInfo: {
            name: "dhd-phone-assistant",
            title: "DHD phone assistant",
            version: "0.1.0",
          },
          capabilities: { experimentalApi: true },
        });
        this.rpc.notify("initialized", {});
        this.initialized = true;
        logger.log("initialize:complete");
      } catch (error) {
        await this.stopProcess();
        throw error;
      }
    })();

    try {
      await this.startPromise;
    } finally {
      this.startPromise = null;
    }
  }

  async runTurn(
    phoneRequest: string,
    existingThreadId?: string,
    threadTitle?: string,
    timing?: PhaseTimer,
    reasoningEffort: string = resolveCodexEffort(),
    fastMode = false,
    onAgentMessageDelta?: (update: AgentMessageStreamUpdate) => void,
    onThreadReady?: (threadId: string) => Promise<void>,
    isContinuation = false,
  ): Promise<TurnResult> {
    const logger = timing ?? new PhaseTimer("codex-turn");
    this.turnRequested = true;
    emitCompanionPlanEvent({ type: "dhd_plan", phase: "reset" });
    this.activeTiming = logger;
    this.userMessageLogged = false;
    this.interruptRequested = false;
    try {
      await this.start(logger);
      const completion = new Promise<TurnResult>((resolve, reject) => {
        this.turnCompletion = {
          resolve,
          reject,
          agentMessages: new Map(),
          nextAgentMessageOrder: 0,
          phoneToolFailures: [],
          onAgentMessageDelta,
        };
      });

      const model = resolveCodexModel();
      const serviceTier = serviceTierForFastMode(fastMode);
      this.activeModel = model;
      this.activeServiceTier = serviceTier;
      const threadParams: Record<string, unknown> = {
        dynamicTools: buildDhdDynamicTools(),
        model,
        cwd: this.runtimeCwd,
      };

      let threadId: string | null = null;
      if (
        this.activeDhdThreadId &&
        this.activeDhdThreadId !== existingThreadId
      ) {
        await this.unsubscribeThread(this.activeDhdThreadId, logger);
      }
      if (
        existingThreadId &&
        this.hasCurrentDhdThread &&
        this.loadedThreadIds.has(existingThreadId)
      ) {
        threadId = existingThreadId;
        logger.log("thread:reuse_loaded", `threadId=${threadId}`);
      } else if (existingThreadId) {
        logger.log("resume:start", `threadId=${existingThreadId}`);
        try {
          const threadResponse = await this.rpc.request("thread/resume", {
            ...threadParams,
            threadId: existingThreadId,
          });
          threadId = extractThreadId(threadResponse.result) || existingThreadId;
          logger.log("resume:complete", `threadId=${threadId}`);
        } catch (error) {
          // A persisted thread may have been deleted or may belong to an
          // older App Server contract. Only an explicit resume failure is
          // allowed to rotate the thread; a new companion process must not
          // discard a valid stored context merely because it has no local
          // loaded-thread cache.
          console.error(
            `[codex-app-server] could not resume stored thread ${existingThreadId}: ${errorMessage(error)}`,
          );
          logger.log("resume:failed", `threadId=${existingThreadId}`);
        }
      }
      if (!threadId) {
        if (existingThreadId) {
          logger.log(
            "thread:fresh_contract",
            `replacingStoredThread=${existingThreadId}`,
          );
        }
        logger.log("thread/start:start");
        const threadResponse = await this.rpc.request("thread/start", threadParams);
        threadId = extractThreadId(threadResponse.result);
        logger.log("thread/start:complete", `threadId=${threadId ?? "?"}`);
      }
      if (!threadId)
        throw new Error("Codex App Server did not return a thread id.");
      this.activeThreadId = threadId;
      this.activeDhdThreadId = threadId;
      this.loadedThreadIds.add(threadId);
      this.hasCurrentDhdThread = true;
      await onThreadReady?.(threadId);
      if (!existingThreadId && threadTitle?.trim()) {
        // Naming is best-effort: older App Server builds may not expose this
        // convenience method, but a failed name update must not lose a turn.
        try {
          await this.rpc.request("thread/name/set", {
            threadId,
            name: threadTitle.trim().slice(0, 80),
          });
        } catch (error) {
          console.error(
            `[codex-app-server] could not name thread: ${errorMessage(error)}`,
          );
        }
      }

      try {
        if (this.interruptRequested) {
          throw new Error("Codex App Server turn was interrupted.");
        }
        logger.log("turn/start:start", `threadId=${threadId}`);
        const turnStartResponse = await this.rpc.request("turn/start", {
          threadId,
          model,
          effort: normalizeCodexEffort(reasoningEffort),
          serviceTier,
          cwd: this.runtimeCwd,
          // A continuation is a real, minimal user turn so the model can
          // advance from the persisted tool responses and errors in the
          // resumed thread. The phone-side continuation run remains hidden
          // from DHD's local timeline; this text is only the App Server input.
          input: isContinuation
            ? [{ type: "text", text: "continue" }]
            : [{ type: "text", text: phoneRequest }],
        });
        // `turn/start` returns the initial turn object. The notification is
        // also tracked below, but capturing this response makes user-driven
        // cancellation reliable even if the notification arrives later.
        this.activeTurnId =
          extractTurnId(turnStartResponse.result) || this.activeTurnId;
        this.logUserMessagePhaseFromValue(
          turnStartResponse.result,
          "turn/start.response",
        );
        logger.log("turn/start:complete", `turnId=${this.activeTurnId ?? "?"}`);
        if (this.interruptRequested) {
          await this.interrupt();
          throw new Error("Codex App Server turn was interrupted.");
        }
      } catch (error) {
        this.turnCompletion?.reject(
          toError(error),
        );
        this.turnCompletion = null;
        throw error;
      }
      // A phone turn may legitimately run for longer than ten minutes. Leave
      // completion under App Server/user control; only individual RPC and
      // bridge requests retain bounded transport timeouts.
      const result = await completion;
      return result;
    } catch (error) {
      // A failed/interrupted turn may still be active inside App Server. Restart
      // the connection on the next request so a bad turn cannot poison the
      // persistent connection or make later requests fail mysteriously.
      await this.stopProcess();
      throw error;
    } finally {
      this.turnRequested = false;
      this.activeThreadId = null;
      this.activeTurnId = null;
      this.interruptRequested = false;
      this.activeTiming = null;
      this.userMessageLogged = false;
      if (this.turnCompletion) {
        this.turnCompletion.reject(
          new Error("Codex App Server turn ended before completion."),
        );
        this.turnCompletion = null;
      }
    }
  }

  /** Stop the persistent App Server connection during companion shutdown. */
  async close(): Promise<void> {
    await this.stopProcess();
  }

  /** Append a user instruction to the currently running turn. */
  async steer(text: string): Promise<void> {
    const safeText = text.trim().slice(0, MAX_STEER_CHARS);
    if (!safeText) throw new Error("A steer instruction is required.");
    const threadId = this.activeThreadId;
    const turnId = this.activeTurnId;
    if (!this.isTurnInFlight || !threadId || !turnId) {
      throw new Error("Codex has no active turn to steer.");
    }

    const response = await this.rpc.request("turn/steer", {
      threadId,
      input: [{ type: "text", text: safeText }],
      expectedTurnId: turnId,
    });
    const acceptedTurnId = asRecord(response.result)?.turnId;
    if (typeof acceptedTurnId === "string" && acceptedTurnId !== turnId) {
      throw new Error(
        `Codex accepted the steer for unexpected turn ${acceptedTurnId}.`,
      );
    }
  }

  /** Interrupt the active turn, for example after the phone-side Stop action. */
  async interrupt(): Promise<void> {
    const threadId = this.activeThreadId;
    if (!this.isTurnInFlight) return;
    this.interruptRequested = true;
    if (!threadId) return;
    const turnId = this.activeTurnId;
    await this.rpc.request("turn/interrupt", {
      threadId,
      ...(turnId ? { turnId } : {}),
    });
  }

  private startProcess(): void {
    if (this.child)
      throw new Error("Codex App Server client is already running.");
    const child = spawnAppServerProcess(this.spawnAppServer, {
      codexHome: this.codexHome,
      runtimeCwd: this.runtimeCwd,
    });
    this.child = child;
    this.reader = readline.createInterface({ input: child.stdout });
    this.reader.on("line", (line) => this.rpc.handleLine(line));
    child.stderr.on("data", (chunk: Buffer) => {
      const text = chunk.toString("utf8").trim();
      if (text) console.error(`[codex-app-server] ${text}`);
    });
    child.once("error", (error) =>
      this.failPending(
        new Error(`Could not start Codex App Server: ${error.message}`),
      ),
    );
    child.once("close", (code, signal) =>
      this.handleChildClose(child, code, signal),
    );
  }

  private handleChildClose(
    child: ChildProcessWithoutNullStreams,
    code: number | null,
    signal: NodeJS.Signals | null,
  ): void {
    // stopProcess() detaches the old child before starting its replacement,
    // but Windows can deliver the old child's close event after that
    // replacement has already begun initialize. Never let a stale close
    // reject the replacement child's pending RPCs.
    if (this.child !== child) return;
    this.child = null;
    this.reader = null;
    this.initialized = false;
    this.loadedThreadIds.clear();
    this.failPending(
      new Error(
        `Codex App Server exited before completing the turn (code=${code ?? "?"}, signal=${signal ?? "?"}).`,
      ),
    );
  }

  private handleNotification(message: JsonRpcMessage): void {
    const tokenUsageEvent = extractCompanionTokenUsageEvent(message);
    if (tokenUsageEvent) {
      emitCompanionTokenUsageEvent({
        ...tokenUsageEvent,
        model: this.activeModel,
        serviceTier: this.activeServiceTier,
      });
    }

    const loadedThreadId = startedThreadId(message);
    if (loadedThreadId) this.loadedThreadIds.add(loadedThreadId);
    const closedThreadId = unloadedThreadId(message);
    if (closedThreadId) this.forgetLoadedThread(closedThreadId);

    const completion = this.turnCompletion;
    if (!completion || !message.method) return;
    logServerNotification(message);
    if (message.method === "thread/tokenUsage/updated") return;
    if (message.method === "turn/plan/updated") {
      const planEvent = extractCompanionPlanUpdatedEvent(
        message,
        this.activeDhdThreadId ?? this.activeThreadId,
        this.activeTurnId,
      );
      if (planEvent) emitCompanionPlanEvent(planEvent);
      return;
    }
    if (message.method === "turn/started") {
      this.activeTurnId = extractTurnId(message.params) || this.activeTurnId;
      this.activeTiming?.log(
        "turn/started",
        `turnId=${this.activeTurnId ?? "?"}`,
      );
      return;
    }
    if (message.method === "item/started") {
      this.logUserMessagePhase(message);
      this.reportAgentMessageStream(
        completion,
        recordAgentMessageStarted(completion, message.params),
      );
      return;
    }
    if (message.method === "item/agentMessage/delta") {
      this.reportAgentMessageStream(
        completion,
        recordAgentMessageDelta(completion, message.params),
      );
      return;
    }
    if (message.method === "item/completed") {
      this.logUserMessagePhase(message);
      this.reportAgentMessageStream(
        completion,
        recordAgentMessageCompleted(completion, message.params),
      );
      return;
    }
    if (message.method === "turn/completed") {
      this.activeTiming?.log(
        "turn/completed",
        `status=${String(turnCompletedStatus(message) ?? "unknown")}`,
      );
      const error = turnCompletionError(message);
      if (error) {
        completion.reject(error);
      } else {
        completion.resolve({
          text:
            selectFinalAgentMessageText(completion.agentMessages) ||
            extractText(message.params),
          threadId: this.activeThreadId || "",
          phoneToolFailures: [...(completion.phoneToolFailures ?? [])],
        });
      }
      this.turnCompletion = null;
      return;
    }
    if (message.method === "turn/failed" || message.method === "error") {
      completion.reject(turnFailureError(message));
      this.turnCompletion = null;
    }
  }

  private forgetLoadedThread(threadId: string): void {
    this.loadedThreadIds.delete(threadId);
    if (this.activeDhdThreadId === threadId) {
      this.activeDhdThreadId = null;
      this.hasCurrentDhdThread = false;
    }
  }

  private reportAgentMessageStream(
    completion: {
      onAgentMessageDelta?: (update: AgentMessageStreamUpdate) => void;
    },
    state: AgentMessageState | null,
  ): void {
    if (
      !state ||
      state.phase !== "final_answer" ||
      !state.text.trim() ||
      !completion.onAgentMessageDelta
    ) {
      return;
    }
    completion.onAgentMessageDelta({ itemId: state.id, text: state.text });
  }

  private logUserMessagePhase(message: JsonRpcMessage): void {
    this.logUserMessagePhaseFromValue(
      message.params,
      message.method || "notification",
    );
  }

  private logUserMessagePhaseFromValue(value: unknown, event: string): void {
    if (this.userMessageLogged) return;
    const record = asRecord(value);
    const candidates: unknown[] = [record?.item];
    const turn = asRecord(record?.turn);
    if (Array.isArray(turn?.items)) candidates.push(...turn.items);
    if (Array.isArray(record?.items)) candidates.push(...record.items);
    if (record?.type === "userMessage") candidates.push(record);
    const userMessage = candidates
      .map((candidate) => asRecord(candidate))
      .find((item) => item?.type === "userMessage");
    if (!userMessage) return;
    this.userMessageLogged = true;
    this.activeTiming?.log("userMessage", `event=${event}`);
  }

  private async unsubscribeThread(
    threadId: string,
    timing: PhaseTimer,
  ): Promise<void> {
    if (!this.loadedThreadIds.has(threadId)) {
      this.forgetLoadedThread(threadId);
      return;
    }
    timing.log("thread/unsubscribe:start", `threadId=${threadId}`);
    try {
      await this.rpc.request("thread/unsubscribe", { threadId });
      timing.log("thread/unsubscribe:complete", `threadId=${threadId}`);
    } catch (error) {
      timing.log("thread/unsubscribe:error", `threadId=${threadId}`);
      console.error(
        `[codex-app-server] could not unsubscribe superseded thread ${threadId}: ` +
          `${errorMessage(error)}`,
      );
    } finally {
      this.forgetLoadedThread(threadId);
    }
  }

  private async handleServerRequest(message: JsonRpcMessage): Promise<void> {
    const id = message.id;
    const method = message.method;
    if (id === undefined || !method) return;

    try {
      if (method === "item/tool/call") {
        const result = await handleDynamicToolCall(message.params);
        this.recordDynamicToolResult(message.params, result);
        this.rpc.respond(id, result);
        return;
      }
      const answer = answerServerRequest(method, message.params);
      if ("error" in answer) {
        this.rpc.respondError(id, answer.error.code, answer.error.message);
      } else {
        this.rpc.respond(id, answer.result);
      }
    } catch (error) {
      this.rpc.respondError(
        id,
        -32000,
        errorMessage(error),
      );
    }
  }

  private recordDynamicToolResult(
    value: unknown,
    result: DynamicToolCallResponse,
  ): void {
    if (result.success || !this.turnCompletion) return;
    const failure = extractDynamicToolFailure(result);
    this.turnCompletion.phoneToolFailures.push({
      tool: extractDynamicToolName(value) || "phone tool",
      ...failure,
    });
  }

  private writeToAppServer(line: string): void {
    if (!this.child || this.child.stdin.destroyed) {
      throw new Error("Codex App Server is not running.");
    }
    this.child.stdin.write(line);
  }

  private failPending(error: Error): void {
    this.rpc.rejectPending(error);
    this.turnCompletion?.reject(error);
    this.turnCompletion = null;
  }

  private async stopProcess(): Promise<void> {
    const child = this.child;
    const reader = this.reader;
    this.child = null;
    this.reader = null;
    this.initialized = false;
    this.loadedThreadIds.clear();
    this.activeDhdThreadId = null;
    this.hasCurrentDhdThread = false;
    this.turnCompletion = null;
    this.rpc.rejectPending(new Error("Codex App Server stopped."));
    reader?.close();
    if (!child || child.killed) return;
    child.stdin.end();
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        child.kill();
        resolve();
      }, 1_500);
      child.once("close", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
}

/**
 * Forward the latest cumulative final-answer text to the phone while keeping
 * bridge writes ordered. If Codex emits faster than the phone can refresh its
 * Room-backed timeline, intermediate snapshots are coalesced; the phone still
 * receives the newest text in order. Completion does not wait for these
 * presentation updates because `complete_session` is the authoritative
 * terminal update for the same message id.
 */
class AgentMessageStreamer {
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
    this.drainPromise = this.drain();
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
    this.drainPromise = null;
    if (this.latest) this.startDrain();
  }
}

function streamedAgentMessageId(sessionId: string): string {
  return `dhd-agent-${sessionId}`;
}

export interface ActiveCodexTurn {
  sessionId: string;
  client: CodexAppServerClient;
}

/**
 * The phone bridge is pull-based: the desktop companion polls the phone over
 * the adb-forwarded socket. Keep the active App Server client here so those
 * polls can deliver steering input to the same in-flight turn.
 */
let activeCodexTurn: ActiveCodexTurn | null = null;

/** Keep phone-side companion presence alive independently of task polling. */
async function maintainCompanionHeartbeat(
  isStopping: () => boolean,
): Promise<void> {
  let lastHealthy: boolean | undefined;
  while (!isStopping()) {
    try {
      const response = await requestBridge(
        { type: "heartbeat", requestId: randomUUID() },
        { timeoutMs: COMPANION_HEARTBEAT_TIMEOUT_MS },
      );
      if (response.ok !== true) {
        throw new Error(
          typeof response.message === "string"
            ? response.message
            : "The phone bridge rejected the companion heartbeat.",
        );
      }
      if (lastHealthy === false) {
        console.error("[phone-assistant-companion] phone bridge heartbeat restored");
      }
      lastHealthy = true;
    } catch (error) {
      if (lastHealthy !== false) {
        console.error(
          `[phone-assistant-companion] phone bridge heartbeat unavailable: ${errorMessage(error)}`,
        );
      }
      lastHealthy = false;
    }
    if (!isStopping()) await delay(COMPANION_HEARTBEAT_INTERVAL_MS);
  }
}

export async function runAssistantCompanion(
  codexClient = new CodexAppServerClient(),
): Promise<void> {
  const pollIntervalMs = parsePollInterval(pollIntervalSetting());
  let stopping = false;
  let pendingRun: Promise<void> | null = null;
  const stop = () => {
    stopping = true;
    const active = activeCodexTurn;
    if (active) {
      void active.client.interrupt().catch((error) => {
        console.error(
          `[phone-assistant-companion] could not interrupt on shutdown: ${errorMessage(error)}`,
        );
      });
    }
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);

  let codexWarmup: Promise<boolean> | null = null;
  const scheduleCodexWarmup = (scope: string): void => {
    // Codex startup can take longer than the phone presence lease. Keep the
    // bridge poll loop alive while warming the App Server in the background.
    if (codexWarmup) return;
    const operation = prewarmCodexClient(codexClient, scope);
    codexWarmup = operation;
    void operation.then(
      () => {
        if (codexWarmup === operation) codexWarmup = null;
      },
      (error) => {
        console.error(
          `[phone-assistant-companion] Codex warmup runner failed: ${errorMessage(error)}`,
        );
        if (codexWarmup === operation) codexWarmup = null;
      },
    );
  };

  console.error(
    "[phone-assistant-companion] waiting for a request typed in the Android app",
  );
  console.error(
    `[phone-assistant-companion] phone bridge target ${bridgeHost}:${bridgePort}`,
  );
  if (isLoopbackBridgeHost(bridgeHost)) {
    console.error(
      "[phone-assistant-companion] loopback mode: adb forward tcp:8765 tcp:8765 is still supported",
    );
  } else {
    console.error(
      "[phone-assistant-companion] wireless mode: phone and laptop must share Wi-Fi and PHONE_ASSISTANT_BRIDGE_TOKEN must match DHD settings",
    );
  }
  console.error(
    "[phone-assistant-companion] a logged-in Codex CLI must be available on this companion host",
  );

  const heartbeatPromise = maintainCompanionHeartbeat(() => stopping);
  try {
    scheduleCodexWarmup("codex-prewarm");
    while (!stopping) {
      const pollStartedAt = performance.now();
      if (isDebugTimingEnabled()) logCompanionPhase("poll:start");
      try {
        if (!pendingRun && !activeCodexTurn) {
          const pending = await requestBridge(
            { type: "pending_request", requestId: randomUUID() },
            { timeoutMs: BRIDGE_POLL_TIMEOUT_MS },
          );
          if (isDebugTimingEnabled()) {
            logCompanionPhase(
              "poll:complete",
              `durationMs=${Math.round(performance.now() - pollStartedAt)} available=${pending.available === true}`,
            );
          }
          if (pending.warmupRequested === true) {
            logCompanionPhase("codex:warmup_requested");
            scheduleCodexWarmup("codex-app-open-warmup");
          }
          if (pending.ok === true && pending.available === true) {
            logCompanionPhase(
              "poll:request_detected",
              `durationMs=${Math.round(performance.now() - pollStartedAt)}`,
            );
            pendingRun = processPendingRequest(pending, codexClient)
              .catch((error) => {
                console.error(
                  `[phone-assistant-companion] phone request runner failed: ${errorMessage(error)}`,
                );
              })
              .finally(() => {
                pendingRun = null;
              });
          } else if (pending.ok === false) {
            console.error(
              `[phone-assistant-companion] phone bridge rejected poll: ${String(pending.message ?? "unknown error")}`,
            );
          }
        } else if (activeCodexTurn) {
          await processPendingSteer(activeCodexTurn);
        }
      } catch (error) {
        logCompanionPhase(
          "poll:error",
          `durationMs=${Math.round(performance.now() - pollStartedAt)}`,
        );
        // The phone may be disconnected or the bridge may not be running yet.
        // Keep polling so reconnecting the device does not require a restart.
        console.error(
          `[phone-assistant-companion] ${errorMessage(error)}`,
        );
      }
      if (!stopping) await delay(pollIntervalMs);
    }
  } finally {
    stopping = true;
    if (pendingRun) await pendingRun;
    await heartbeatPromise;
    await codexClient.close();
  }
}

async function prewarmCodexClient(
  codexClient: CodexAppServerClient,
  scope: string,
): Promise<boolean> {
  for (let attempt = 1; attempt <= PREWARM_ATTEMPTS; attempt += 1) {
    const timing = new PhaseTimer(scope);
    try {
      timing.log("start", `attempt=${attempt}`);
      await codexClient.start(timing);
      timing.log("complete", `attempt=${attempt}`);
      return true;
    } catch (error) {
      timing.log("error", `attempt=${attempt}`);
      console.error(
        `[phone-assistant-companion] Codex prewarm attempt ${attempt}/${PREWARM_ATTEMPTS} failed: ` +
          `${errorMessage(error)}`,
      );
      if (attempt < PREWARM_ATTEMPTS) await delay(PREWARM_RETRY_DELAY_MS);
    }
  }
  console.error(
    "[phone-assistant-companion] continuing without a warm Codex connection; the next request will retry startup",
  );
  return false;
}

export async function processPendingRequest(
  pending: BridgeMessage,
  codexClient: CodexAppServerClient,
): Promise<void> {
  const sessionId =
    typeof pending.sessionId === "string" ? pending.sessionId : "";
  if (!sessionId) {
    console.error(
      "[phone-assistant-companion] pending request did not include a session id",
    );
    return;
  }
  const timing = new PhaseTimer(`phone-request:${sessionId}`);
  timing.log("claim:start");
  let claimed: BridgeMessage;
  try {
    claimed = await requestBridge({
      type: "claim_request",
      requestId: randomUUID(),
      sessionId,
    });
    timing.log("claim:complete", `ok=${claimed.ok === true}`);
  } catch (error) {
    timing.log("claim:error");
    throw error;
  }
  if (claimed.ok !== true) {
    // Another companion instance may have claimed it between polling and the
    // claim call. This is expected and is safe to ignore.
    if (claimed.code !== "REQUEST_NOT_AVAILABLE") {
      console.error(
        `[phone-assistant-companion] could not claim request: ${String(claimed.message ?? "unknown error")}`,
      );
    }
    return;
  }

  const request = typeof claimed.request === "string" ? claimed.request : "";
  const isContinuation = claimed.continuation === true;
  if (!request && !isContinuation) {
    console.error(
      "[phone-assistant-companion] claimed request was empty; releasing it",
    );
    await releaseRequest(sessionId);
    return;
  }

  console.error(
    `[phone-assistant-companion] claimed ${sessionId}: ${isContinuation ? "continuation" : request}`,
  );
  activeCodexTurn = { sessionId, client: codexClient };
  const agentMessageStreamer = new AgentMessageStreamer(
    sessionId,
    streamedAgentMessageId(sessionId),
  );
  try {
    const conversationId =
      typeof claimed.conversationId === "string"
        ? claimed.conversationId
        : undefined;
    const existingThreadId =
      typeof claimed.codexThreadId === "string"
        ? claimed.codexThreadId
        : undefined;
    const threadTitle =
      typeof claimed.title === "string" ? claimed.title : request;
    const reasoningEffort =
      typeof claimed.reasoningEffort === "string"
        ? claimed.reasoningEffort
        : undefined;
    const fastMode = claimed.fastMode === true;
    const result = await codexClient.runTurn(
      request,
      existingThreadId,
      threadTitle,
      timing,
      reasoningEffort,
      fastMode,
      (update) => agentMessageStreamer.push(update),
      async (threadId) => {
        // Bind a newly created thread before the first turn can finish. If
        // the user stops mid-task, the interrupted turn still leaves enough
        // durable identity for Continue to resume the same Codex context.
        if (!conversationId || (existingThreadId && threadId === existingThreadId)) {
          return;
        }
        const bound = await requestBridge({
          type: "bind_codex_thread",
          requestId: randomUUID(),
          conversationId,
          codexThreadId: threadId,
        });
        if (bound.ok !== true) {
          throw new Error(
            `The phone did not bind Codex thread ${threadId}: ${String(bound.message ?? "unknown error")}`,
          );
        }
      },
      isContinuation,
    );
    if (result.phoneToolFailures.length > 0) {
      const failedTools = [
        ...new Set(result.phoneToolFailures.map((failure) => failure.tool)),
      ].join(", ");
      timing.log(
        "phone-tool:reported_failure",
        `count=${result.phoneToolFailures.length} tools=${failedTools || "unknown"}`,
      );
      console.error(
        `[phone-assistant-companion] ${result.phoneToolFailures.length} phone tool call(s) reported an error; preserving the Codex response and conversation context`,
      );
    }
    console.error(
      `[phone-assistant-companion] Codex turn reached terminal status; closing phone session` +
        `${result.text ? `; final assistant message: ${result.text.slice(0, 500)}` : ""}`,
    );
    const feedback = normalizeAgentFeedback(result.text);
    const completed = await requestBridge({
      type: "complete_session",
      requestId: randomUUID(),
      sessionId,
      message: feedback || DEFAULT_COMPLETION_MESSAGE,
      ...(feedback ? { feedback } : {}),
      ...(agentMessageStreamer.hasUpdates
        ? { agentMessageId: agentMessageStreamer.messageId }
        : {}),
    });
    if (completed.ok !== true) {
      console.error(
        `[phone-assistant-companion] could not mark the phone session complete: ${String(completed.message ?? "unknown error")}`,
      );
    }
  } catch (error) {
    console.error(
      `[phone-assistant-companion] Codex turn failed: ${errorMessage(error)}`,
    );
    try {
      await requestBridge({
        type: "fail_session",
        requestId: randomUUID(),
        sessionId,
        reason: errorMessage(error),
      });
    } catch (failureError) {
      console.error(
        `[phone-assistant-companion] could not mark the phone session failed: ${errorMessage(failureError)}`,
      );
    }
  } finally {
    if (activeCodexTurn?.sessionId === sessionId) activeCodexTurn = null;
  }
}

export async function processPendingSteer(active: ActiveCodexTurn): Promise<void> {
  const pending = await requestBridge(
    {
      type: "pending_steer",
      requestId: randomUUID(),
      sessionId: active.sessionId,
    },
    { timeoutMs: BRIDGE_POLL_TIMEOUT_MS },
  );
  if (pending.ok !== true) {
    if (pending.message) {
      console.error(
        `[phone-assistant-companion] phone bridge rejected steer poll: ${String(pending.message)}`,
      );
    }
    return;
  }

  // A phone-side Stop changes the coordinator state before the next poll. In
  // that case interrupt Codex as well so the desktop turn cannot continue
  // operating the phone after the user has stopped it.
  if (shouldInterruptForPhoneStop(pending) && active.client.isTurnInFlight) {
    await active.client.interrupt().catch((error) => {
      console.error(
        `[phone-assistant-companion] could not interrupt stopped phone session: ${errorMessage(error)}`,
      );
    });
    return;
  }
  // The App Server may still be completing turn/start. Leave a queued steer
  // untouched until its thread and turn ids are available for turn/steer.
  if (!active.client.canSteer) return;
  if (pending.available !== true) return;

  const steerId = typeof pending.steerId === "string" ? pending.steerId : "";
  if (!steerId) {
    console.error(
      "[phone-assistant-companion] pending steer did not include a steer id",
    );
    return;
  }
  const claimed = await requestBridge({
    type: "claim_steer",
    requestId: randomUUID(),
    sessionId: active.sessionId,
    steerId,
  });
  if (claimed.ok !== true) {
    if (claimed.code !== "STEER_NOT_AVAILABLE") {
      console.error(
        `[phone-assistant-companion] could not claim steer ${steerId}: ${String(claimed.message ?? "unknown error")}`,
      );
    }
    return;
  }

  const text = typeof claimed.text === "string" ? claimed.text.trim() : "";
  if (!text) {
    await releaseSteer(steerId, active.sessionId);
    return;
  }

  try {
    await active.client.steer(text);
    const completed = await requestBridge({
      type: "complete_steer",
      requestId: randomUUID(),
      sessionId: active.sessionId,
      steerId,
    });
    if (completed.ok !== true) {
      console.error(
        `[phone-assistant-companion] could not mark steer ${steerId} delivered: ${String(completed.message ?? "unknown error")}`,
      );
    }
    console.error(
      `[phone-assistant-companion] delivered steer ${steerId} to the active Codex turn`,
    );
  } catch (error) {
    console.error(
      `[phone-assistant-companion] Codex steer failed: ${errorMessage(error)}`,
    );
    await releaseSteer(steerId, active.sessionId);
  }
}

async function releaseSteer(steerId: string, sessionId: string): Promise<void> {
  try {
    await requestBridge({
      type: "release_steer",
      requestId: randomUUID(),
      sessionId,
      steerId,
    });
  } catch (error) {
    console.error(
      `[phone-assistant-companion] could not release steer ${steerId}: ${errorMessage(error)}`,
    );
  }
}

async function releaseRequest(sessionId: string): Promise<void> {
  try {
    await requestBridge({
      type: "release_request",
      requestId: randomUUID(),
      sessionId,
    });
  } catch (error) {
    console.error(
      `[phone-assistant-companion] could not release request: ${errorMessage(error)}`,
    );
  }
}

/** Attention waiting is active-session state, not a phone-side Stop. */
export function shouldInterruptForPhoneStop(pending: BridgeMessage): boolean {
  return pending.attentionPending !== true && pending.active === false;
}

function normalizeAgentFeedback(text: string): string {
  return text.replace(/\r\n?/g, "\n").trim().slice(0, MAX_AGENT_FEEDBACK_CHARS);
}

export function parsePollInterval(value: string | undefined): number {
  if (!value?.trim()) return DEFAULT_POLL_INTERVAL_MS;
  if (!/^\d+$/.test(value.trim()))
    throw new Error("PHONE_ASSISTANT_POLL_MS must be a positive integer.");
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 250 || parsed > 60_000) {
    throw new Error("PHONE_ASSISTANT_POLL_MS must be between 250 and 60000.");
  }
  return parsed;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

if (isMainModule("assistant-companion")) {
  runAssistantCompanion().catch((error: unknown) => {
    console.error(
      `[phone-assistant-companion] ${errorMessage(error)}`,
    );
    process.exitCode = 1;
  });
}
