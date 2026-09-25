import dgram from "node:dgram";
import { randomUUID } from "node:crypto";
import { hostname, networkInterfaces } from "node:os";
import net from "node:net";
import { isRecord } from "../shared/guards.js";

export const PAIRING_PROTOCOL_VERSION = 1;
export const PAIRING_DISCOVERY_PORT = 8766;
export const DEFAULT_PHONE_DISCOVERY_TIMEOUT_MS = 2_500;
export const DEFAULT_PAIRING_APPROVAL_TIMEOUT_MS = 60_000;
const PAIRING_SEND_ATTEMPTS = 3;
const PAIRING_RETRY_DELAY_MS = 350;

export interface PhoneDiscoveryOffer {
  type: "dhd_discover_offer";
  version: 1;
  requestId: string;
  deviceId: string;
  deviceName: string;
  model?: string;
  addresses: string[];
  port: number;
  pairingNonce: string;
}

/** A phone discovered on the current local network. */
export interface DiscoveredPhone {
  deviceId: string;
  deviceName: string;
  model?: string;
  host: string;
  addresses: string[];
  port: number;
  pairingNonce: string;
}

export interface ResolvedPairing {
  host: string;
  port: number;
  token: string;
  deviceId: string;
  addresses: string[];
}

export interface PairingDiscoveryOptions {
  timeoutMs?: number;
  discoveryPort?: number;
  /** Override destinations for deterministic local tests or custom networks. */
  broadcastAddresses?: string[];
}

export interface PairingApprovalOptions {
  timeoutMs?: number;
  discoveryPort?: number;
  /** Name shown on the phone's approval prompt. */
  desktopName?: string;
}

function ipv4ToNumber(value: string): number | null {
  const parts = value.split(".");
  if (parts.length !== 4) return null;
  let result = 0;
  for (const part of parts) {
    const octet = Number(part);
    if (!Number.isInteger(octet) || octet < 0 || octet > 255) return null;
    result = (result << 8) | octet;
  }
  return result >>> 0;
}

function numberToIpv4(value: number): string {
  return [24, 16, 8, 0].map((shift) => (value >>> shift) & 0xff).join(".");
}

function broadcastAddresses(): string[] {
  const addresses = new Set<string>(["255.255.255.255"]);
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.family !== "IPv4" || entry.internal) continue;
      const address = ipv4ToNumber(entry.address);
      const mask = ipv4ToNumber(entry.netmask);
      if (address === null || mask === null) continue;
      addresses.add(numberToIpv4((address & mask) | (~mask >>> 0)));
    }
  }
  return [...addresses];
}

function isValidPort(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1 && value <= 65_535;
}

function ipv4Addresses(sourceAddress: string, advertised: unknown): string[] {
  const advertisedAddresses = Array.isArray(advertised)
    ? advertised.filter((address): address is string => typeof address === "string" && net.isIP(address) === 4)
    : [];
  return [...new Set([sourceAddress, ...advertisedAddresses].filter((address) => net.isIP(address) === 4))];
}

function parsePhoneDiscoveryOffer(
  value: unknown,
  requestId: string,
  sourceAddress: string,
): PhoneDiscoveryOffer | null {
  if (!isRecord(value)) return null;
  const message = value;
  if (message.type !== "dhd_discover_offer" || message.version !== PAIRING_PROTOCOL_VERSION) return null;
  if (message.requestId !== requestId) return null;
  if (typeof message.deviceId !== "string" || !message.deviceId.trim()) return null;
  if (typeof message.deviceName !== "string" || !message.deviceName.trim()) return null;
  if (typeof message.pairingNonce !== "string" || !message.pairingNonce.trim()) return null;
  if (!isValidPort(message.port)) return null;

  const addresses = ipv4Addresses(sourceAddress, message.addresses);
  if (addresses.length === 0) return null;

  const model = typeof message.model === "string" && message.model.trim()
    ? message.model.trim()
    : undefined;
  return {
    type: "dhd_discover_offer",
    version: 1,
    requestId,
    deviceId: message.deviceId.trim(),
    deviceName: message.deviceName.trim(),
    ...(model ? { model } : {}),
    addresses,
    port: message.port,
    pairingNonce: message.pairingNonce.trim()
  };
}

function parsePairingApprovalResponse(
  value: unknown,
  requestId: string,
  deviceId: string,
  sourceAddress: string,
): ResolvedPairing | Error | null {
  if (!isRecord(value)) return null;
  const message = value;
  if (message.version !== PAIRING_PROTOCOL_VERSION || message.requestId !== requestId) return null;
  if (typeof message.deviceId !== "string" || message.deviceId.trim() !== deviceId) return null;

  if (message.type === "dhd_pair_approval_rejected") {
    const rejection = typeof message.message === "string" && message.message.trim()
      ? message.message.trim()
      : "The phone declined the desktop companion pairing request.";
    return new Error(rejection);
  }
  if (message.type !== "dhd_pair_approval_offer") return null;
  if (typeof message.token !== "string" || !message.token.trim()) return null;
  if (!isValidPort(message.port)) return null;

  const addresses = ipv4Addresses(sourceAddress, message.addresses);
  if (addresses.length === 0) return null;

  return {
    host: addresses[0],
    port: message.port,
    token: message.token.trim(),
    deviceId,
    addresses
  };
}

type ExchangeOutcome<T> = { value: T } | { error: Error };

interface DatagramExchange<T> {
  payload: Buffer;
  port: number;
  destinations: () => string[];
  timeoutMs: number;
  enableBroadcast: boolean;
  failurePrefix: string;
  onMessage: (message: unknown, sourceAddress: string) => ExchangeOutcome<T> | undefined;
  onTimeout: () => ExchangeOutcome<T>;
}

function exchangeDatagrams<T>(exchange: DatagramExchange<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const socket = dgram.createSocket("udp4");
    let settled = false;
    let bound = false;
    let timer: NodeJS.Timeout | undefined;
    let retryTimer: NodeJS.Timeout | undefined;

    const finish = (outcome: ExchangeOutcome<T>) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      if (retryTimer) clearTimeout(retryTimer);
      try {
        socket.close();
      } catch {
        // The socket may not have finished binding yet.
      }
      if ("error" in outcome) reject(outcome.error);
      else resolve(outcome.value);
    };

    socket.on("error", (error) => {
      if (!bound) finish({ error: new Error(`${exchange.failurePrefix}: ${error.message}`) });
      // Once bound, one unavailable adapter should not discard offers from
      // the other interfaces. The bounded timer completes the scan.
    });
    socket.on("message", (message, remote) => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(message.toString("utf8"));
      } catch {
        return;
      }
      const outcome = exchange.onMessage(parsed, remote.address);
      if (outcome) finish(outcome);
    });
    timer = setTimeout(() => finish(exchange.onTimeout()), exchange.timeoutMs);

    socket.bind(0, "0.0.0.0", () => {
      bound = true;
      if (exchange.enableBroadcast) {
        try {
          socket.setBroadcast(true);
        } catch {
          // Some platforms still allow directed UDP sends without this flag.
        }
      }

      const destinations = exchange.destinations();
      let sendAttempt = 0;
      const sendRequest = () => {
        if (settled) return;
        sendAttempt += 1;
        for (const address of destinations) {
          try {
            socket.send(exchange.payload, exchange.port, address, () => {});
          } catch {
            // A later retry or the bounded timeout provides the useful error.
          }
        }
        if (sendAttempt < PAIRING_SEND_ATTEMPTS && !settled) {
          retryTimer = setTimeout(sendRequest, PAIRING_RETRY_DELAY_MS);
        }
      };
      sendRequest();
    });
  });
}

/**
 * Find every DHD phone that answers on the current LAN. Discovery intentionally
 * returns metadata only; it never places an authentication token on the wire.
 */
export function discoverPhones(
  options: PairingDiscoveryOptions = {}
): Promise<DiscoveredPhone[]> {
  const requestId = randomUUID();
  const offers = new Map<string, DiscoveredPhone>();
  return exchangeDatagrams<DiscoveredPhone[]>({
    payload: Buffer.from(JSON.stringify({
      type: "dhd_discover_request",
      version: PAIRING_PROTOCOL_VERSION,
      requestId
    }), "utf8"),
    port: options.discoveryPort ?? PAIRING_DISCOVERY_PORT,
    destinations: () => [...new Set(options.broadcastAddresses ?? broadcastAddresses())]
      .filter((address) => net.isIP(address) === 4),
    timeoutMs: options.timeoutMs ?? DEFAULT_PHONE_DISCOVERY_TIMEOUT_MS,
    enableBroadcast: true,
    failurePrefix: "Phone discovery failed",
    onMessage: (message, sourceAddress) => {
      const offer = parsePhoneDiscoveryOffer(message, requestId, sourceAddress);
      if (!offer) return undefined;
      const existing = offers.get(offer.deviceId);
      const addresses = [...new Set([...(existing?.addresses ?? []), ...offer.addresses])];
      offers.set(offer.deviceId, {
        deviceId: offer.deviceId,
        deviceName: offer.deviceName,
        ...(offer.model ? { model: offer.model } : existing?.model ? { model: existing.model } : {}),
        host: existing?.host ?? offer.addresses[0],
        addresses,
        port: offer.port,
        pairingNonce: offer.pairingNonce
      });
      return undefined;
    },
    onTimeout: () => ({ value: [...offers.values()] }),
  });
}

/**
 * Ask one selected phone to show a one-time approval prompt. The token is
 * returned only after the user approves on the phone, then the companion can
 * store it for silent future reconnects.
 */
export function requestPairingApproval(
  phone: DiscoveredPhone,
  options: PairingApprovalOptions = {}
): Promise<ResolvedPairing> {
  const requestId = randomUUID();
  const destinations = [...new Set([phone.host, ...phone.addresses])]
    .filter((address) => net.isIP(address) === 4);
  if (destinations.length === 0) {
    return Promise.reject(new Error("The selected phone did not advertise a usable local address."));
  }
  return exchangeDatagrams<ResolvedPairing>({
    payload: Buffer.from(JSON.stringify({
      type: "dhd_pair_approval_request",
      version: PAIRING_PROTOCOL_VERSION,
      requestId,
      deviceId: phone.deviceId,
      pairingNonce: phone.pairingNonce,
      desktopName: (options.desktopName?.trim() || hostname() || "DHD Companion").slice(0, 80)
    }), "utf8"),
    port: options.discoveryPort ?? PAIRING_DISCOVERY_PORT,
    destinations: () => destinations,
    timeoutMs: options.timeoutMs ?? DEFAULT_PAIRING_APPROVAL_TIMEOUT_MS,
    enableBroadcast: false,
    failurePrefix: "Phone pairing request failed",
    onMessage: (message, sourceAddress) => {
      const response = parsePairingApprovalResponse(message, requestId, phone.deviceId, sourceAddress);
      if (response instanceof Error) return { error: response };
      return response ? { value: response } : undefined;
    },
    onTimeout: () => ({ error: new Error("Timed out waiting for approval on the selected phone.") }),
  });
}
