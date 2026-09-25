import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import * as readline from "node:readline";

import { emitCompanionPlanEvent, emitCompanionTokenUsageEvent } from "../shared/companion-events.js";
import { codexHomeDirectory, codexRuntimeDirectory } from "../config/env.js";
import { errorMessage, toError } from "../shared/errors.js";
import { asRecord } from "../shared/guards.js";
import { killProcessTree } from "../shared/process-tree.js";
import { PhaseTimer } from "../shared/timing.js";
import {
  recordAgentMessageCompleted,
  recordAgentMessageDelta,
  recordAgentMessageStarted,
  type AgentMessageStreamUpdate,
} from "./agent-messages.js";
import {
  buildDhdDynamicTools,
  extractDynamicToolFailure,
  extractDynamicToolName,
  handleDynamicToolCall,
  type DynamicToolCallResponse,
} from "./dynamic-tools.js";
import {
  extractCompanionPlanUpdatedEvent,
  extractCompanionTokenUsageEvent,
  extractThreadId,
  extractTurnId,
} from "./extract.js";
import { JsonRpcConnection, type JsonRpcMessage } from "./json-rpc.js";
import {
  isRetryableError,
  logServerNotification,
  notificationThreadId,
  notificationTurnId,
  startedThreadId,
  turnCompletedStatus,
  turnCompletionError,
  turnFailureError,
  unloadedThreadId,
} from "./notifications.js";
import { spawnAppServerProcess, type AppServerSpawner } from "./process.js";
import { answerServerRequest } from "./server-requests.js";
import {
  DEFAULT_CODEX_SERVICE_TIER,
  normalizeCodexEffort,
  resolveCodexEffort,
  resolveCodexModel,
  serviceTierForFastMode,
} from "./settings.js";
import { TurnCompletion, type TurnResult } from "./turn-completion.js";
import { SingleFlight } from "../shared/single-flight.js";

const MAX_STEER_CHARS = 4_000;
const TERMINAL_TURN_METHODS = new Set(["turn/completed", "turn/failed", "error"]);

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
  private readonly startup = new SingleFlight<void>();
  private initialized = false;
  private loadedThreadIds = new Set<string>();
  private readonly codexHome = codexHomeDirectory();
  private readonly runtimeCwd = codexRuntimeDirectory();
  private turnCompletion: TurnCompletion | null = null;
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
    return this.startup.run(() =>
      this.startAndInitialize(timing ?? new PhaseTimer("codex-connection")),
    );
  }

  private async startAndInitialize(logger: PhaseTimer): Promise<void> {
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
      const turnCompletion = new TurnCompletion(onAgentMessageDelta);
      this.turnCompletion = turnCompletion;
      const completion = turnCompletion.result;

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
      completion.streamFinalAnswer(recordAgentMessageStarted(completion, message.params));
      return;
    }
    if (message.method === "item/agentMessage/delta") {
      completion.streamFinalAnswer(recordAgentMessageDelta(completion, message.params));
      return;
    }
    if (message.method === "item/completed") {
      this.logUserMessagePhase(message);
      completion.streamFinalAnswer(recordAgentMessageCompleted(completion, message.params));
      return;
    }
    if (TERMINAL_TURN_METHODS.has(message.method) && !this.concernsActiveTurn(message)) {
      console.error(`[codex-app-server] ignored ${message.method} for another turn`);
      return;
    }
    if (isRetryableError(message)) {
      console.error(`[codex-app-server] retrying after error: ${turnFailureError(message).message}`);
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
        completion.resolve(completion.completedResult(message.params, this.activeThreadId || ""));
      }
      this.turnCompletion = null;
      return;
    }
    if (message.method === "turn/failed" || message.method === "error") {
      completion.reject(turnFailureError(message));
      this.turnCompletion = null;
    }
  }

  private concernsActiveTurn(message: JsonRpcMessage): boolean {
    const threadId = notificationThreadId(message);
    if (threadId && this.activeThreadId && threadId !== this.activeThreadId) return false;
    const turnId = notificationTurnId(message);
    return !(turnId && this.activeTurnId && turnId !== this.activeTurnId);
  }

  private forgetLoadedThread(threadId: string): void {
    this.loadedThreadIds.delete(threadId);
    if (this.activeDhdThreadId === threadId) {
      this.activeDhdThreadId = null;
      this.hasCurrentDhdThread = false;
    }
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
        killProcessTree(child);
        resolve();
      }, 1_500);
      child.once("close", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
}
