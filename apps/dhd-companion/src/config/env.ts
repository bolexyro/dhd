import { homedir } from "node:os";
import { join, resolve } from "node:path";

export const GUARD_REGIONS_FEATURE_FLAG = "PHONE_ASSISTANT_ENABLE_GUARD_REGIONS";

const ENABLED_FLAG_VALUES = new Set(["1", "true", "yes", "on"]);

function trimmedSetting(name: string): string | undefined {
  return process.env[name]?.trim() || undefined;
}

export function isEnabledFlag(value: string | undefined): boolean {
  return ENABLED_FLAG_VALUES.has((value ?? "").trim().toLowerCase());
}

export function isGuardRegionsEnabled(environment: NodeJS.ProcessEnv = process.env): boolean {
  return isEnabledFlag(environment[GUARD_REGIONS_FEATURE_FLAG]);
}

export function isDebugTimingEnabled(): boolean {
  return isEnabledFlag(process.env.PHONE_ASSISTANT_DEBUG_TIMING);
}

export function isCodeModeHostDisabled(): boolean {
  return process.env.PHONE_ASSISTANT_ENABLE_CODE_MODE_HOST?.trim().toLowerCase() === "false";
}

export function bridgeHostSetting(): string | undefined {
  return trimmedSetting("PHONE_ASSISTANT_BRIDGE_HOST");
}

export function bridgePortSetting(): string | undefined {
  return process.env.PHONE_ASSISTANT_BRIDGE_PORT;
}

export function bridgeTokenSetting(): string | undefined {
  return trimmedSetting("PHONE_ASSISTANT_BRIDGE_TOKEN");
}

export function pollIntervalSetting(): string | undefined {
  return process.env.PHONE_ASSISTANT_POLL_MS;
}

export function codexBinSetting(): string | undefined {
  return trimmedSetting("PHONE_ASSISTANT_CODEX_BIN");
}

export function codexModelSetting(): string | undefined {
  return trimmedSetting("PHONE_ASSISTANT_CODEX_MODEL");
}

export function codexReasoningEffortSetting(): string | undefined {
  return process.env.PHONE_ASSISTANT_CODEX_REASONING_EFFORT;
}

export function windowsLocalAppDataDirectory(): string {
  return process.env.LOCALAPPDATA || join(homedir(), "AppData", "Local");
}

export function dashboardPortSetting(): string | undefined {
  return process.env.COMPANION_PORT;
}

export function dashboardHostSetting(): string | undefined {
  return process.env.COMPANION_HOST;
}

function dhdDataDirectory(): string {
  return join(homedir(), ".dhd");
}

export function codexHomeDirectory(): string {
  const configured = trimmedSetting("PHONE_ASSISTANT_CODEX_HOME");
  return configured ? resolve(configured) : join(dhdDataDirectory(), "codex-home");
}

export function codexRuntimeDirectory(): string {
  const configured = trimmedSetting("PHONE_ASSISTANT_CODEX_CWD");
  return configured ? resolve(configured) : join(dhdDataDirectory(), "codex-runtime");
}

export function companionSettingsPath(): string {
  return join(dhdDataDirectory(), "companion-connection.json");
}
