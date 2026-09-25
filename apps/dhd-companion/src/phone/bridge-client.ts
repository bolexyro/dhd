import net from "node:net";
import { bridgeHostSetting, bridgePortSetting, bridgeTokenSetting } from "../config/env.js";
import { NdjsonLineBuffer } from "./ndjson.js";
import {
  DEFAULT_BRIDGE_HOST,
  DEFAULT_BRIDGE_PORT,
  isTerminalBridgeMessage,
  type BridgeMessage,
  type BridgeRequest,
} from "./protocol.js";

export const DEFAULT_BRIDGE_TIMEOUT_MS = 45_000;
/** Initial connection grace period for requests that may wait after acceptance. */
export const BLOCKING_BRIDGE_TIMEOUT_MS = DEFAULT_BRIDGE_TIMEOUT_MS;
export const MAX_BRIDGE_RESPONSE_BYTES = 16 * 1024 * 1024;

export interface BridgeRequestOptions {
  timeoutMs?: number;
  /** Keep waiting after the phone has accepted a user-dependent request. */
  keepOpenAfterAccepted?: boolean;
  acceptedTimeoutMs?: number;
  host?: string;
  port?: number;
  token?: string;
}

export interface BridgeTarget {
  host: string;
  port: number;
  token: string | undefined;
}

export function parsePort(value: string): number {
  if (!/^\d+$/.test(value)) throw new Error("PHONE_ASSISTANT_BRIDGE_PORT must be an integer.");
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error("PHONE_ASSISTANT_BRIDGE_PORT must be between 1 and 65535.");
  }
  return port;
}

export function bridgePortFromEnvironment(): number {
  try {
    return parsePort(bridgePortSetting() ?? `${DEFAULT_BRIDGE_PORT}`);
  } catch {
    return DEFAULT_BRIDGE_PORT;
  }
}

export function environmentBridgeTarget(): BridgeTarget {
  return {
    host: bridgeHostSetting() ?? DEFAULT_BRIDGE_HOST,
    port: bridgePortFromEnvironment(),
    token: bridgeTokenSetting(),
  };
}

export function isLoopbackBridgeHost(host: string): boolean {
  const normalized = host.trim().toLowerCase();
  return normalized === "localhost" || normalized === "127.0.0.1" || normalized === "::1" || normalized === "[::1]";
}

export function buildBridgePayload(
  request: BridgeRequest,
  token?: string,
): BridgeRequest {
  const effectiveToken = arguments.length > 1 ? token : bridgeTokenSetting();
  const safeToken = effectiveToken?.trim();
  return safeToken ? { ...request, authToken: safeToken } : { ...request };
}

export function bridgeConfigurationError(
  host: string = environmentBridgeTarget().host,
  token: string | undefined = bridgeTokenSetting(),
): string | null {
  if (!token?.trim()) {
    return `PHONE_ASSISTANT_BRIDGE_TOKEN is required to reach the phone at ${host}. Pair the phone from the dashboard first.`;
  }
  return null;
}

/** Send one request to the phone-local NDJSON bridge and await its terminal line. */
export function requestBridge(
  request: BridgeRequest,
  options: BridgeRequestOptions = {}
): Promise<BridgeMessage> {
  const target = environmentBridgeTarget();
  const host = options.host ?? target.host;
  const port = options.port ?? target.port;
  const token = options.token ?? target.token;
  const configurationError = bridgeConfigurationError(host, token);
  if (configurationError) return Promise.reject(new Error(configurationError));
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    const lines = new NdjsonLineBuffer();
    let responseBytes = 0;
    let settled = false;
    let timeoutTimer: NodeJS.Timeout | undefined;

    const finish = (error?: Error, message?: BridgeMessage) => {
      if (settled) return;
      settled = true;
      if (timeoutTimer) clearTimeout(timeoutTimer);
      socket.destroy();
      if (error) reject(error);
      else resolve(message!);
    };

    const timeOut = () => finish(new Error("Timed out waiting for the phone assistant bridge."));
    const startDeadline = (milliseconds: number) => {
      if (timeoutTimer) clearTimeout(timeoutTimer);
      timeoutTimer = setTimeout(timeOut, milliseconds);
    };

    const timeoutMs = options.timeoutMs ?? DEFAULT_BRIDGE_TIMEOUT_MS;
    if (timeoutMs > 0) {
      // Socket inactivity timeouts do not consistently cover a TCP connect
      // that is stuck in SYN-SENT. Keep a wall-clock deadline as well so a
      // filtered or unreachable phone cannot leave callers in CHECKING forever.
      startDeadline(timeoutMs);
      socket.setTimeout(timeoutMs, timeOut);
    }
    socket.once("error", (error) => {
      finish(new Error(`Could not connect to the phone assistant bridge at ${host}:${port}: ${error.message}`));
    });
    socket.once("close", () => {
      if (!settled) finish(new Error("The phone assistant bridge closed before completing the request."));
    });
    socket.once("connect", () => {
      socket.write(`${JSON.stringify(buildBridgePayload(request, token))}\n`);
    });
    socket.on("data", (chunk: Buffer) => {
      responseBytes += chunk.byteLength;
      if (responseBytes > MAX_BRIDGE_RESPONSE_BYTES) {
        finish(new Error("The phone assistant bridge response is too large."));
        return;
      }
      for (const line of lines.readLines(chunk)) {
        let message: BridgeMessage;
        try {
          message = JSON.parse(line) as BridgeMessage;
        } catch {
          finish(new Error("The phone assistant bridge returned invalid JSON."));
          return;
        }
        // The phone sends an accepted progress line first. Resolve only on a
        // terminal response so callers can safely read the complete result.
        // A request that has been accepted is now being processed by the
        // phone. User-dependent operations may remain open until Wireless
        // debugging is restored, but connection failures before acceptance
        // still use the normal bounded timeout.
        if (message.type === "accepted") {
          if (options.keepOpenAfterAccepted) {
            if (timeoutTimer) {
              clearTimeout(timeoutTimer);
              timeoutTimer = undefined;
            }
            socket.setTimeout(0);
          } else if (options.acceptedTimeoutMs !== undefined) {
            startDeadline(options.acceptedTimeoutMs);
            socket.setTimeout(options.acceptedTimeoutMs);
          }
          continue;
        }
        if (isTerminalBridgeMessage(message)) {
          finish(undefined, message);
          return;
        }
        console.error(
          `[phone-assistant-bridge] ignored unexpected bridge message type: ${typeof message.type === "string" ? message.type : "(missing)"}`,
        );
      }
    });
  });
}
