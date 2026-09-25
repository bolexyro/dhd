import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { killProcessTree } from "../../shared/process-tree.js";
import { WORKER_SHUTDOWN_TIMEOUT_MS } from "../../worker/shutdown.js";
import type { BridgeCheckResult, CompanionState } from "../client/api.js";
import type { CompanionDashboard } from "./dashboard.js";
import { sameConnection, type ConnectionConfig } from "./settings-store.js";
import type { DashboardState } from "./state-store.js";

const MODULE_DIRECTORY = fileURLToPath(new URL(".", import.meta.url));
const PROJECT_ROOT = resolve(MODULE_DIRECTORY, "../../../");
const COMPANION_SCRIPT_JS = resolve(MODULE_DIRECTORY, "../../assistant-companion.js");
const COMPANION_SCRIPT_TS = resolve(PROJECT_ROOT, "src/assistant-companion.ts");
const WORKER_RESTART_DELAY_MS = 1_000;
const WORKER_STOP_GRACE_MS = WORKER_SHUTDOWN_TIMEOUT_MS + 5_000;
const WORKER_EXIT_AFTER_KILL_MS = 2_000;

function childOutput(child: ChildProcess, source: "companion" | "bridge", state: DashboardState): void {
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
        state.appendLog(line, {
          source,
          level: /error|failed|rejected|could not|timed out/i.test(line) ? "error" : "info"
        });
        newline = buffered.indexOf("\n");
      }
    });
    stream.on("end", () => {
      if (buffered.trim()) state.appendLog(buffered, { source, level: "info" });
    });
  }
}

function requestGracefulStop(child: ChildProcess): void {
  if (process.platform !== "win32") {
    child.kill();
  } else if (child.connected) {
    child.disconnect();
  } else {
    killProcessTree(child);
  }
}

function workerEnvironment(connection: ConnectionConfig): NodeJS.ProcessEnv {
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

export class WorkerSupervisor {
  active = false;
  transitionInFlight = false;
  private worker: ChildProcess | null = null;
  private workerConnection: ConnectionConfig | null = null;
  private restartTimer: NodeJS.Timeout | undefined;

  constructor(private readonly dashboard: CompanionDashboard) {}

  get isRunning(): boolean {
    return Boolean(this.worker && !this.worker.killed);
  }

  targetsConnection(target: ConnectionConfig): boolean {
    return Boolean(
      this.worker &&
        !this.worker.killed &&
        this.workerConnection &&
        sameConnection(this.workerConnection, target),
    );
  }

  ensureRunning(): void {
    const state = this.dashboard.state;
    if (!this.active || this.transitionInFlight || state.processStatus === "stopping") return;
    if (this.isRunning) return;
    if (this.restartTimer) return;
    this.start();
  }

  start(): CompanionState {
    const { state, monitor } = this.dashboard;
    if (this.isRunning) return state.snapshot();

    this.clearRestartTimer();

    const scriptConfig = getWorkerScript();
    if (!scriptConfig) {
      state.processStatus = "error";
      state.lastError = "Could not find assistant companion script. Build the project first.";
      state.appendLog(state.lastError, { level: "error", source: "system" });
      return state.snapshot();
    }

    state.processStatus = "starting";
    if (state.bridgeStatus === "offline") state.bridgeStatus = "unknown";
    state.lastError = undefined;
    monitor.resetWorkerConfirmation();
    monitor.resetHeartbeatRetry();
    state.appendLog(
      `Starting companion worker for ${state.connection.host}:${state.connection.port}.`,
      { level: "system", source: "system" },
    );

    const child = spawn(scriptConfig.command, scriptConfig.args, {
      cwd: PROJECT_ROOT,
      env: workerEnvironment(state.connection),
      stdio: ["ignore", "pipe", "pipe", "ipc"],
      windowsHide: true
    });

    this.worker = child;
    this.workerConnection = { ...state.connection };
    child.on("message", (message) => {
      state.ingestToolCallEvent(message);
      state.ingestTokenUsageEvent(message);
      state.ingestPlanEvent(message);
    });
    childOutput(child, "companion", state);
    child.once("error", (error) => {
      if (this.worker !== child) return;
      this.worker = null;
      this.workerConnection = null;
      state.processStatus = "error";
      state.lastError = error.message;
      state.appendLog(`Companion worker failed: ${error.message}`, { level: "error", source: "system" });
      state.publish();
      this.scheduleRestart();
    });
    child.once("exit", (code, signal) => {
      // A forced stop can finish before the old child emits its exit event. If
      // a replacement worker has already been installed, this callback is
      // stale and must not overwrite the replacement's state.
      if (this.worker !== child) return;
      this.worker = null;
      this.workerConnection = null;
      const expected = state.processStatus === "stopping";
      state.processStatus = expected || code === 0 ? "stopped" : "error";
      if (!expected && code !== 0) {
        state.lastError = `Companion worker exited with ${code === null ? signal ?? "unknown signal" : `code ${code}`}.`;
      }
      state.appendLog(
        `Companion worker ${expected ? "stopped" : "exited"}${code === null ? ` (${signal ?? "unknown"})` : ` (code ${code})`}.`,
        { level: expected || code === 0 ? "system" : "error", source: "system" }
      );
      if (!expected) {
        state.bridgeStatus = "offline";
        monitor.resetHeartbeatRetry();
        state.publish();
        void monitor.releaseCompanionPresence()
          .finally(() => this.scheduleRestart())
          .catch(() => {});
      }
    });
    state.processStatus = "running";
    state.appendLog("Companion worker is running.", { level: "system", source: "system" });
    if (state.connection.token) {
      void monitor.checkConnection({ silent: true }).catch(() => {});
    }
    return state.snapshot();
  }

  async stop(
    reason = "requested",
    checkToIgnore?: Promise<BridgeCheckResult>,
  ): Promise<CompanionState> {
    const { state, monitor } = this.dashboard;
    this.clearRestartTimer();
    const child = this.worker;
    if (!child || child.killed) {
      this.worker = null;
      this.workerConnection = null;
      state.processStatus = "stopped";
      const target = state.connection;
      await monitor.releaseCompanionPresence(target, checkToIgnore);
      if (state.connection === target) {
        state.bridgeStatus = "offline";
        monitor.resetHeartbeatRetry();
        state.publish();
      }
      return state.snapshot();
    }
    const target = state.connection;
    state.processStatus = "stopping";
    state.publish();
    state.appendLog(`Stopping companion worker (${reason}).`, { level: "system", source: "system" });
    await new Promise<void>((resolveStop) => {
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        resolveStop();
      };
      child.once("exit", finish);
      requestGracefulStop(child);
      setTimeout(() => {
        if (settled) return;
        killProcessTree(child, "SIGKILL");
        setTimeout(finish, WORKER_EXIT_AFTER_KILL_MS);
      }, WORKER_STOP_GRACE_MS);
    });
    await monitor.releaseCompanionPresence(target, checkToIgnore);
    if (state.connection === target) {
      state.bridgeStatus = "offline";
      monitor.resetHeartbeatRetry();
      state.publish();
    }
    return state.snapshot();
  }

  private scheduleRestart(): void {
    const state = this.dashboard.state;
    if (!this.active || this.transitionInFlight || state.processStatus === "stopping" || this.worker || this.restartTimer) return;
    state.appendLog("Companion worker exited unexpectedly; restarting it.", {
      level: "error",
      source: "system",
    });
    this.restartTimer = setTimeout(() => {
      this.restartTimer = undefined;
      if (!this.active || this.worker) return;
      this.start();
    }, WORKER_RESTART_DELAY_MS);
  }

  private clearRestartTimer(): void {
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = undefined;
    }
  }
}
