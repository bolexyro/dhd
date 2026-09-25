import { DEFAULT_CODEX_MODEL } from "../../shared/default-model.js";
import type { CompanionState } from "./api.js";

export interface TokenPricing {
  inputPerMillion: number;
  cachedInputPerMillion: number;
  outputPerMillion: number;
  longContextThreshold?: number;
  longContextInputMultiplier?: number;
  longContextOutputMultiplier?: number;
}

export const TOKEN_PRICING: Record<string, TokenPricing> = {
  "gpt-6-luna": {
    inputPerMillion: 0.1,
    cachedInputPerMillion: 0.01,
    outputPerMillion: 0.5,
    longContextThreshold: 272_000,
    longContextInputMultiplier: 2,
    longContextOutputMultiplier: 1.5,
  },
  "gpt-6-astra": {
    inputPerMillion: 10,
    cachedInputPerMillion: 1,
    outputPerMillion: 50,
    longContextThreshold: 272_000,
    longContextInputMultiplier: 2,
    longContextOutputMultiplier: 1.5,
  },
  "gpt-5.6-sol": {
    inputPerMillion: 4,
    cachedInputPerMillion: 0.4,
    outputPerMillion: 20,
    longContextThreshold: 272_000,
    longContextInputMultiplier: 2,
    longContextOutputMultiplier: 1.5,
  },
  "gpt-5.6-terra": {
    inputPerMillion: 2,
    cachedInputPerMillion: 0.2,
    outputPerMillion: 12,
    longContextThreshold: 272_000,
    longContextInputMultiplier: 2,
    longContextOutputMultiplier: 1.5,
  },
  "gpt-5.6-luna": {
    inputPerMillion: 0.2,
    cachedInputPerMillion: 0.02,
    outputPerMillion: 1.2,
    longContextThreshold: 272_000,
    longContextInputMultiplier: 2,
    longContextOutputMultiplier: 1.5,
  },
  "gpt-5.5": {
    inputPerMillion: 5,
    cachedInputPerMillion: 0.5,
    outputPerMillion: 30,
    longContextThreshold: 272_000,
    longContextInputMultiplier: 2,
    longContextOutputMultiplier: 1.5,
  },
  "gpt-5.4": {
    inputPerMillion: 2.5,
    cachedInputPerMillion: 0.25,
    outputPerMillion: 15,
    longContextThreshold: 272_000,
    longContextInputMultiplier: 2,
    longContextOutputMultiplier: 1.5,
  },
  "gpt-5.4-mini": {
    inputPerMillion: 0.75,
    cachedInputPerMillion: 0.075,
    outputPerMillion: 4.5,
  },
};

export interface TokenCostEstimate {
  model: string;
  totalCost: number;
  uncachedInputCost: number;
  cachedInputCost: number;
  outputCost: number;
  inputRatePerMillion: number;
  cachedInputRatePerMillion: number;
  outputRatePerMillion: number;
  rateLabel: string;
}

export function estimateTokenCost(
  usage: NonNullable<CompanionState["tokenUsage"]>,
): TokenCostEstimate | null {
  const model = usage.model?.trim() || DEFAULT_CODEX_MODEL;
  const pricing = TOKEN_PRICING[model];
  if (!pricing) return null;

  const cachedInputTokens = Math.min(usage.inputTokens, usage.cachedInputTokens);
  const uncachedInputTokens = Math.max(0, usage.inputTokens - cachedInputTokens);
  const usesLongContextRate =
    pricing.longContextThreshold !== undefined &&
    usage.inputTokens > pricing.longContextThreshold;
  const serviceTierMultiplier = usage.serviceTier === "priority" ? 2 : 1;
  const inputMultiplier = serviceTierMultiplier * (
    usesLongContextRate ? pricing.longContextInputMultiplier ?? 1 : 1
  );
  const outputMultiplier = serviceTierMultiplier * (
    usesLongContextRate ? pricing.longContextOutputMultiplier ?? 1 : 1
  );
  const uncachedInputCost =
    (uncachedInputTokens / 1_000_000) * pricing.inputPerMillion * inputMultiplier;
  const cachedInputCost =
    (cachedInputTokens / 1_000_000) * pricing.cachedInputPerMillion * inputMultiplier;
  const outputCost =
    (usage.outputTokens / 1_000_000) * pricing.outputPerMillion * outputMultiplier;

  const rateNotes = [
    usage.serviceTier === "priority" ? "Priority 2x" : "Standard",
    usesLongContextRate ? "long-context rate" : "",
  ].filter(Boolean);

  return {
    model,
    totalCost: uncachedInputCost + cachedInputCost + outputCost,
    uncachedInputCost,
    cachedInputCost,
    outputCost,
    inputRatePerMillion: pricing.inputPerMillion * inputMultiplier,
    cachedInputRatePerMillion: pricing.cachedInputPerMillion * inputMultiplier,
    outputRatePerMillion: pricing.outputPerMillion * outputMultiplier,
    rateLabel: rateNotes.join(" · "),
  };
}
