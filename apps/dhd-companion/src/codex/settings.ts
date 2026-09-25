import { codexModelSetting, codexReasoningEffortSetting } from "../config/env.js";
import { DEFAULT_CODEX_MODEL } from "../shared/default-model.js";

// DHD owns its App Server conversation settings. These defaults deliberately
// do not depend on the user's interactive Codex chat or global config.
const DEFAULT_CODEX_EFFORT = "high";
export const DEFAULT_CODEX_SERVICE_TIER = "default";
const FAST_CODEX_SERVICE_TIER = "priority";
const CODEX_REASONING_EFFORTS = new Set([
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
]);

export function resolveCodexModel(): string {
  return codexModelSetting() ?? DEFAULT_CODEX_MODEL;
}

export function resolveCodexEffort(): string {
  return normalizeCodexEffort(codexReasoningEffortSetting());
}

export function normalizeCodexEffort(value: string | undefined): string {
  const normalized = value?.trim().toLowerCase();
  return normalized && CODEX_REASONING_EFFORTS.has(normalized)
    ? normalized
    : DEFAULT_CODEX_EFFORT;
}

export function serviceTierForFastMode(fastMode: boolean): string {
  return fastMode ? FAST_CODEX_SERVICE_TIER : DEFAULT_CODEX_SERVICE_TIER;
}
