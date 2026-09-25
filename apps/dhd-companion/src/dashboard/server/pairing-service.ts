import type { CompanionState, DiscoveredPhoneSnapshot } from "../../companion-web/api.js";
import {
  DEFAULT_PHONE_DISCOVERY_TIMEOUT_MS,
  discoverPhones,
  requestPairingApproval,
  type DiscoveredPhone,
} from "../../phone/pairing.js";
import type { BridgeMessage } from "../../phone/protocol.js";
import { errorMessage } from "../../shared/errors.js";
import { isRecord } from "../../shared/guards.js";
import type { CompanionDashboard } from "./dashboard.js";
import { sameConnection, saveConnection, type ConnectionConfig } from "./settings-store.js";
import {
  awaitBeforeCheckDeadline,
  remainingCheckTime,
  requestStatusWithRetry,
} from "./status-check.js";
import { SingleFlight } from "../../shared/single-flight.js";

const PHONE_DISCOVERY_TIMEOUT_MS = DEFAULT_PHONE_DISCOVERY_TIMEOUT_MS;

export function discoveredPhoneSnapshot(value: DiscoveredPhone): DiscoveredPhoneSnapshot {
  return {
    deviceId: value.deviceId,
    deviceName: value.deviceName,
    ...(value.model ? { model: value.model } : {})
  };
}

function targetHasSavedPairing(target: ConnectionConfig, deviceId: string): boolean {
  return target.deviceId === deviceId && Boolean(target.token);
}

export class PairingService {
  private readonly phoneDiscovery = new SingleFlight<DiscoveredPhone[]>();
  private connectionTransitionInFlight: Promise<CompanionState> | undefined;

  constructor(private readonly dashboard: CompanionDashboard) {}

  discoverPhonesOnNetwork(timeoutMs = PHONE_DISCOVERY_TIMEOUT_MS): Promise<DiscoveredPhone[]> {
    return this.phoneDiscovery.run(() => discoverPhones({ timeoutMs }));
  }

  async rediscoverPairedDevice(
    target: ConnectionConfig,
    deadlineAt?: number,
  ): Promise<CompanionState> {
    if (!target.deviceId || !target.token) {
      throw new Error("The saved phone pairing is incomplete.");
    }
    const phones = await awaitBeforeCheckDeadline(
      this.discoverPhonesOnNetwork(remainingCheckTime(deadlineAt, PHONE_DISCOVERY_TIMEOUT_MS)),
      deadlineAt,
    );
    const discovered = phones.find((candidate) => candidate.deviceId === target.deviceId);
    if (!discovered) {
      throw new Error("No paired DHD phone answered on the local network. Make sure the phone and computer are on the same network.");
    }

    return this.connectDiscoveredPairedDevice(target, discovered, deadlineAt);
  }

  async pairWithDiscoveredDevice(value: unknown): Promise<CompanionState> {
    const { state, monitor } = this.dashboard;
    if (!isRecord(value) || typeof value.deviceId !== "string") {
      throw new Error("A discovered phone must be selected.");
    }
    const deviceId = value.deviceId.trim();
    if (!deviceId) throw new Error("A discovered phone must be selected.");
    const expectedConnection = state.connection;
    const replacePairing = value.replacePairing === true;
    if (targetHasSavedPairing(expectedConnection, deviceId) && !replacePairing && state.bridgeStatus === "connected") {
      return state.snapshot();
    }

    // Refresh before pairing so the nonce and address belong to a recent LAN
    // response rather than a stale browser list.
    const phones = await this.discoverPhonesOnNetwork();
    const selected = phones.find((candidate) => candidate.deviceId === deviceId);
    if (!selected) {
      if (monitor.newerConnectionForSamePhone(expectedConnection, deviceId)) return state.snapshot();
      throw new Error("That phone is no longer visible on the local network. Refresh the phone list and try again.");
    }

    if (targetHasSavedPairing(expectedConnection, deviceId) && !replacePairing) {
      try {
        return await this.connectDiscoveredPairedDevice(expectedConnection, selected);
      } catch (error) {
        if (monitor.newerConnectionForSamePhone(expectedConnection, deviceId)) return state.snapshot();
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
    return this.applyPairedConnection(candidate, result, "paired", expectedConnection);
  }

  private async connectDiscoveredPairedDevice(
    target: ConnectionConfig,
    discovered: DiscoveredPhone,
    deadlineAt?: number,
  ): Promise<CompanionState> {
    const { state, monitor } = this.dashboard;
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
        return this.applyPairedConnection(candidate, result, "reconnected", target);
      } catch (error) {
        if (target.deviceId && monitor.newerConnectionForSamePhone(target, target.deviceId)) return state.snapshot();
        lastStatusError = error;
      }
    }
    throw (lastStatusError instanceof Error
      ? lastStatusError
      : new Error("The paired DHD phone was discovered but did not accept the saved token."));
  }

  private async applyPairedConnection(
    candidate: ConnectionConfig,
    result: BridgeMessage,
    logVerb: string,
    expectedConnection?: ConnectionConfig,
  ): Promise<CompanionState> {
    const { state, monitor, supervisor } = this.dashboard;
    if (this.connectionTransitionInFlight) {
      await this.connectionTransitionInFlight.catch(() => {});
    }
    if (expectedConnection && state.connection !== expectedConnection) {
      if (monitor.newerConnectionForSamePhone(expectedConnection, candidate.deviceId)) return state.snapshot();
      throw new Error("Connection settings changed while rediscovering the phone; keeping the newer settings.");
    }

    const operation = (async (): Promise<CompanionState> => {
      const workerTargetMatchesCandidate = !supervisor.isRunning || supervisor.targetsConnection(candidate);
      if (sameConnection(state.connection, candidate) && workerTargetMatchesCandidate) {
        if (supervisor.active && !supervisor.isRunning) supervisor.start();
        const nextPhone = monitor.recordPhoneStatus(result, candidate);
        state.appendLog(`Phone bridge reconnected; state: ${nextPhone.state}.`, { level: "system", source: "bridge" });
        return state.snapshot();
      }

      supervisor.transitionInFlight = true;
      try {
        const wasRunning = supervisor.isRunning;
        if (wasRunning) await supervisor.stop("phone pairing changed", monitor.checkInFlight);
        if (expectedConnection && state.connection !== expectedConnection) {
          if (monitor.newerConnectionForSamePhone(expectedConnection, candidate.deviceId)) return state.snapshot();
          if (supervisor.active || wasRunning) supervisor.start();
          throw new Error("Connection settings changed while rediscovering the phone; keeping the newer settings.");
        }
        state.connection = candidate;
        await saveConnection(state.connection);
        // The worker must use the new address before the UI can report that
        // this desktop is connected. A status request alone does not renew the
        // phone's companion presence lease.
        if (supervisor.active || wasRunning) supervisor.start();
        const nextPhone = monitor.recordPhoneStatus(result, candidate);
        state.appendLog(`Phone pairing ${logVerb}; the companion discovered the phone automatically; state: ${nextPhone.state}.`, {
          level: "system",
          source: "bridge",
        });
        return state.snapshot();
      } finally {
        supervisor.transitionInFlight = false;
      }
    })();
    this.connectionTransitionInFlight = operation;
    try {
      return await operation;
    } finally {
      if (this.connectionTransitionInFlight === operation) this.connectionTransitionInFlight = undefined;
    }
  }
}
