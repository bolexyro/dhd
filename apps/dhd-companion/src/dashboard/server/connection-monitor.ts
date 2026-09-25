import { randomUUID } from "node:crypto";

import type { BridgeCheckResult, PhoneSnapshot } from "../../companion-web/api.js";
import { requestBridge } from "../../phone/bridge-client.js";
import type { BridgeMessage } from "../../phone/protocol.js";
import { errorMessage } from "../../shared/errors.js";
import type { CompanionDashboard } from "./dashboard.js";
import type { ConnectionConfig } from "./settings-store.js";
import {
  bridgeOptions,
  phoneSnapshot,
  requestStatusWithRetry,
  statusCheckError,
} from "./status-check.js";
import { SingleFlight } from "../../shared/single-flight.js";

const BRIDGE_CHECK_TOTAL_TIMEOUT_MS = 15_000;
const PAIRED_DIRECT_CHECK_TIMEOUT_MS = 2_000;
const PAIRED_DIRECT_CHECK_ATTEMPTS = 2;
const BRIDGE_DISCONNECT_GRACE_MS = 30_000;
const COMPANION_DISCONNECT_TIMEOUT_MS = 1_500;
const AUTOMATIC_REDISCOVERY_COOLDOWN_MS = 15_000;
const BRIDGE_HEARTBEAT_INTERVAL_MS = 4_000;
const BRIDGE_OFFLINE_RETRY_DELAYS_MS = [4_000, 8_000, 15_000] as const;

export interface ConnectionCheckOptions {
  silent?: boolean;
}

export class ConnectionMonitor {
  private readonly bridgeCheck = new SingleFlight<BridgeCheckResult>();
  private lastAutomaticRediscoveryAt = 0;
  private heartbeatRetryAt = 0;
  private heartbeatFailureCount = 0;
  private consecutiveBridgeCheckFailures = 0;
  private consecutiveUnconfirmedWorkerStatuses = 0;
  private lastSuccessfulBridgeCheckAt = 0;
  private successfulBridgeCheckVersion = 0;
  private heartbeatTimer: NodeJS.Timeout | undefined;

  constructor(private readonly dashboard: CompanionDashboard) {}

  get checkInFlight(): Promise<BridgeCheckResult> | undefined {
    return this.bridgeCheck.inFlight;
  }

  checkConnection(options: ConnectionCheckOptions = {}): Promise<BridgeCheckResult> {
    // Startup, the heartbeat, and the initial page load can all ask for the same
    // probe. Share one operation so an older failure cannot
    // overwrite a newer success or produce a misleading red toast.
    return this.bridgeCheck.run(() => this.performConnectionCheck(options));
  }

  resetHeartbeatRetry(): void {
    this.heartbeatRetryAt = 0;
    this.heartbeatFailureCount = 0;
    this.consecutiveBridgeCheckFailures = 0;
  }

  resetWorkerConfirmation(): void {
    this.consecutiveUnconfirmedWorkerStatuses = 0;
  }

  recordPhoneStatus(result: BridgeMessage, target: ConnectionConfig): PhoneSnapshot {
    const { state, supervisor } = this.dashboard;
    const phone = phoneSnapshot(result);
    state.phone = phone;
    const workerConfirmed = phone.companionConnected === true &&
      (!supervisor.active || supervisor.targetsConnection(target));
    this.consecutiveUnconfirmedWorkerStatuses = workerConfirmed ? 0 : this.consecutiveUnconfirmedWorkerStatuses + 1;
    state.bridgeStatus = workerConfirmed
      ? "connected"
      : this.consecutiveUnconfirmedWorkerStatuses >= 3 ? "offline" : "checking";
    state.lastError = undefined;
    this.lastAutomaticRediscoveryAt = 0;
    this.lastSuccessfulBridgeCheckAt = Date.now();
    this.successfulBridgeCheckVersion += 1;
    this.resetHeartbeatRetry();
    return phone;
  }

  newerConnectionForSamePhone(expected: ConnectionConfig, deviceId: string | undefined): boolean {
    const connection = this.dashboard.state.connection;
    return Boolean(deviceId) && connection !== expected && connection.deviceId === deviceId && this.lastSuccessfulBridgeCheckAt > 0;
  }

  /** Tell the phone that the worker owning the liveness lease has stopped. */
  async releaseCompanionPresence(
    target: ConnectionConfig = this.dashboard.state.connection,
    checkToIgnore?: Promise<BridgeCheckResult>,
  ): Promise<void> {
    // An unpaired target has no worker lease to release. Non-loopback targets
    // also require a token, which is guaranteed after pairing.
    if (!target.token) return;

    // A dashboard health check may already be in flight when worker shutdown
    // begins. Let it finish before sending the release so its authenticated
    // request cannot arrive after the release and immediately make the phone
    // connected.
    const inFlightCheck = this.bridgeCheck.inFlight;
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
      this.dashboard.state.appendLog(`Could not release phone companion presence: ${errorMessage(error)}`, {
        level: "info",
        source: "bridge",
      });
    }
  }

  startHeartbeat(): void {
    if (this.heartbeatTimer) return;
    this.heartbeatTimer = setInterval(async () => {
      const { state, supervisor } = this.dashboard;
      if (!supervisor.active) return;
      if (!supervisor.isRunning || state.processStatus === "stopped" || state.processStatus === "error") {
        supervisor.ensureRunning();
        return;
      }
      if (state.processStatus !== "running") return;
      if (this.bridgeCheck.inFlight) return;
      if (!state.connection.token) return;
      if (Date.now() < this.heartbeatRetryAt) return;
      try {
        const result = await this.checkConnection({ silent: true });
        if (result.ok) this.resetHeartbeatRetry();
        else if (state.bridgeStatus !== "checking") this.scheduleHeartbeatRetry();
      } catch {
        this.scheduleHeartbeatRetry();
      }
    }, BRIDGE_HEARTBEAT_INTERVAL_MS);
  }

  private scheduleHeartbeatRetry(): void {
    const delayIndex = Math.min(this.heartbeatFailureCount, BRIDGE_OFFLINE_RETRY_DELAYS_MS.length - 1);
    this.heartbeatRetryAt = Date.now() + BRIDGE_OFFLINE_RETRY_DELAYS_MS[delayIndex];
    this.heartbeatFailureCount += 1;
  }

  private async performConnectionCheck(options: ConnectionCheckOptions = {}): Promise<BridgeCheckResult> {
    const { state, pairing } = this.dashboard;
    const target = state.connection;
    const successVersionAtStart = this.successfulBridgeCheckVersion;
    const previousBridgeStatus = state.bridgeStatus;
    const previousPhone = state.phone;
    const deadlineAt = Date.now() + BRIDGE_CHECK_TOTAL_TIMEOUT_MS;
    // Heartbeats are recovery probes, not user actions. Keep their in-flight
    // state internal so the dashboard does not flash CHECKING every few
    // seconds while the worker remains healthy.
    if (!options.silent) {
      state.bridgeStatus = "checking";
      state.lastError = undefined;
      state.publish();
    }
    try {
      const result = await requestStatusWithRetry(
        target,
        target.deviceId && target.token
          ? { deadlineAt, attempts: PAIRED_DIRECT_CHECK_ATTEMPTS, timeoutMs: PAIRED_DIRECT_CHECK_TIMEOUT_MS }
          : { deadlineAt },
      );
      if (state.connection !== target) {
        return { ok: false, message: "Connection settings changed while checking; check the new link." };
      }
      const nextPhone = phoneSnapshot(result);
      const phoneChanged = JSON.stringify(nextPhone) !== JSON.stringify(previousPhone);
      this.recordPhoneStatus(result, target);
      const linkReady = state.bridgeStatus === "connected";
      if (!options.silent || previousBridgeStatus !== state.bridgeStatus || phoneChanged) {
        const requestAvailability = nextPhone.requestAvailable === undefined
          ? ""
          : `; request available: ${nextPhone.requestAvailable}`;
        state.appendLog(linkReady
          ? `Phone bridge check passed; phone session state: ${nextPhone.state}${requestAvailability}.`
          : state.bridgeStatus === "checking"
            ? "Phone bridge responds; waiting for the companion worker to connect."
            : "Phone bridge responds, but the companion worker is not connected.",
        { level: "system", source: "bridge" });
      } else {
        state.publish();
      }
      return {
        ok: linkReady,
        message: linkReady
          ? "Phone companion connected."
          : state.bridgeStatus === "checking"
            ? "Waiting for the companion worker to connect."
            : "The phone is reachable, but the companion worker is not connected.",
        phone: nextPhone,
      };
    } catch (error) {
      if (state.connection !== target) {
        return { ok: false, message: "Connection settings changed while checking; check the new link." };
      }
      if (this.successfulBridgeCheckVersion !== successVersionAtStart) {
        return {
          ok: state.bridgeStatus === "connected",
          message: "A newer phone connection check has finished.",
          phone: state.phone,
        };
      }
      const directMessage = errorMessage(error);
      this.consecutiveBridgeCheckFailures += 1;
      const firstMissOnSavedConnection = this.consecutiveBridgeCheckFailures === 1 &&
        (previousBridgeStatus === "connected" || (previousBridgeStatus === "unknown" && Boolean(target.token)));
      const recentSuccess = this.lastSuccessfulBridgeCheckAt > 0 &&
        Date.now() - this.lastSuccessfulBridgeCheckAt < BRIDGE_DISCONNECT_GRACE_MS;
      if (firstMissOnSavedConnection) {
        state.bridgeStatus = "checking";
        state.lastError = undefined;
        state.appendLog("Phone bridge missed one check; confirming before marking it disconnected.", {
          level: "info",
          source: "bridge",
        });
        return { ok: false, message: "Rechecking the phone connection after a missed response." };
      }
      const canRediscover = Date.now() < deadlineAt &&
        Boolean(target.deviceId && target.token) &&
        (!options.silent || Date.now() - this.lastAutomaticRediscoveryAt >= AUTOMATIC_REDISCOVERY_COOLDOWN_MS);
      let failureMessage: string;
      if (canRediscover) {
        if (options.silent) this.lastAutomaticRediscoveryAt = Date.now();
        try {
          const nextState = await pairing.rediscoverPairedDevice(target, deadlineAt);
          return {
            ok: nextState.bridgeStatus === "connected",
            message: "Phone bridge rediscovered on the local network.",
            phone: nextState.phone
          };
        } catch (rediscoveryError) {
          const rediscoveryMessage = errorMessage(rediscoveryError);
          failureMessage = `${directMessage} Pairing rediscovery failed: ${rediscoveryMessage}`;
        }
      } else {
        failureMessage = directMessage;
      }
      state.lastError = failureMessage;
      if (recentSuccess && previousBridgeStatus !== "offline") {
        // Rediscovery can repair a changed address, but a failed network probe
        // alone does not revoke a recently verified connection.
        state.bridgeStatus = "checking";
        state.lastError = undefined;
        state.publish();
        return { ok: false, message: "Rechecking the phone connection after a missed response." };
      }
      state.bridgeStatus = "offline";
      if (!options.silent || previousBridgeStatus !== "offline") {
        state.appendLog(failureMessage, { level: "error", source: "bridge" });
      } else {
        state.publish();
      }
      return { ok: false, message: failureMessage };
    }
  }
}
