import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

import {
  bridgeHostSetting,
  bridgePortSetting,
  bridgeTokenSetting,
  companionSettingsPath,
} from "../../config/env.js";
import { parsePort } from "../../phone/bridge-client.js";
import { DEFAULT_BRIDGE_HOST, DEFAULT_BRIDGE_PORT } from "../../phone/protocol.js";

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

function readEnvPort(): number {
  try {
    return parsePort(bridgePortSetting() ?? `${DEFAULT_BRIDGE_PORT}`);
  } catch {
    return DEFAULT_BRIDGE_PORT;
  }
}

export function initialConnection(): ConnectionConfig {
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

export async function saveConnection(connection: ConnectionConfig): Promise<void> {
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

export function sameConnection(left: ConnectionConfig, right: ConnectionConfig): boolean {
  return left.host === right.host &&
    left.port === right.port &&
    left.token === right.token &&
    left.deviceId === right.deviceId;
}
