import { describe, expect, it } from "vitest";

import type { CompanionTokenUsageSnapshot } from "../src/companion-web/api.js";
import { DEFAULT_TOKEN_PRICING_MODEL, TOKEN_PRICING, estimateTokenCost } from "../src/companion-web/pricing.js";
import { toolImageLabel } from "../src/companion-web/tool-images.js";

function usage(overrides: Partial<CompanionTokenUsageSnapshot> = {}): CompanionTokenUsageSnapshot {
  return {
    turnId: "turn-1",
    updatedAt: 0,
    inputTokens: 1_000_000,
    cachedInputTokens: 400_000,
    outputTokens: 100_000,
    reasoningOutputTokens: 10_000,
    totalTokens: 1_100_000,
    modelContextWindow: null,
    ...overrides,
  };
}

describe("dashboard token pricing", () => {
  it("pins the rate card", () => {
    expect(DEFAULT_TOKEN_PRICING_MODEL).toBe("gpt-6-luna");
    expect(TOKEN_PRICING).toMatchInlineSnapshot(`
      {
        "gpt-5.4": {
          "cachedInputPerMillion": 0.25,
          "inputPerMillion": 2.5,
          "longContextInputMultiplier": 2,
          "longContextOutputMultiplier": 1.5,
          "longContextThreshold": 272000,
          "outputPerMillion": 15,
        },
        "gpt-5.4-mini": {
          "cachedInputPerMillion": 0.075,
          "inputPerMillion": 0.75,
          "outputPerMillion": 4.5,
        },
        "gpt-5.5": {
          "cachedInputPerMillion": 0.5,
          "inputPerMillion": 5,
          "longContextInputMultiplier": 2,
          "longContextOutputMultiplier": 1.5,
          "longContextThreshold": 272000,
          "outputPerMillion": 30,
        },
        "gpt-5.6-luna": {
          "cachedInputPerMillion": 0.02,
          "inputPerMillion": 0.2,
          "longContextInputMultiplier": 2,
          "longContextOutputMultiplier": 1.5,
          "longContextThreshold": 272000,
          "outputPerMillion": 1.2,
        },
        "gpt-5.6-sol": {
          "cachedInputPerMillion": 0.4,
          "inputPerMillion": 4,
          "longContextInputMultiplier": 2,
          "longContextOutputMultiplier": 1.5,
          "longContextThreshold": 272000,
          "outputPerMillion": 20,
        },
        "gpt-5.6-terra": {
          "cachedInputPerMillion": 0.2,
          "inputPerMillion": 2,
          "longContextInputMultiplier": 2,
          "longContextOutputMultiplier": 1.5,
          "longContextThreshold": 272000,
          "outputPerMillion": 12,
        },
        "gpt-6-astra": {
          "cachedInputPerMillion": 1,
          "inputPerMillion": 10,
          "longContextInputMultiplier": 2,
          "longContextOutputMultiplier": 1.5,
          "longContextThreshold": 272000,
          "outputPerMillion": 50,
        },
        "gpt-6-luna": {
          "cachedInputPerMillion": 0.01,
          "inputPerMillion": 0.1,
          "longContextInputMultiplier": 2,
          "longContextOutputMultiplier": 1.5,
          "longContextThreshold": 272000,
          "outputPerMillion": 0.5,
        },
      }
    `);
  });

  it("prices the default model at the standard rate below the long-context threshold", () => {
    const estimate = estimateTokenCost(usage({ inputTokens: 200_000, cachedInputTokens: 50_000 }));

    expect(estimate).toEqual({
      model: "gpt-6-luna",
      totalCost: expect.closeTo(0.0655, 10),
      uncachedInputCost: expect.closeTo(0.015, 10),
      cachedInputCost: expect.closeTo(0.0005, 10),
      outputCost: expect.closeTo(0.05, 10),
      inputRatePerMillion: 0.1,
      cachedInputRatePerMillion: 0.01,
      outputRatePerMillion: 0.5,
      rateLabel: "Standard",
    });
  });

  it("applies long-context and priority multipliers together", () => {
    const estimate = estimateTokenCost(usage({ model: " gpt-5.5 ", serviceTier: "priority" }));

    expect(estimate).toMatchObject({
      model: "gpt-5.5",
      inputRatePerMillion: 20,
      cachedInputRatePerMillion: 2,
      outputRatePerMillion: 90,
      rateLabel: "Priority 2x · long-context rate",
    });
    expect(estimate?.uncachedInputCost).toBeCloseTo(12, 10);
    expect(estimate?.cachedInputCost).toBeCloseTo(0.8, 10);
    expect(estimate?.outputCost).toBeCloseTo(9, 10);
    expect(estimate?.totalCost).toBeCloseTo(21.8, 10);
  });

  it("does not apply long-context rates to models without a threshold", () => {
    expect(estimateTokenCost(usage({ model: "gpt-5.4-mini" }))).toMatchObject({
      inputRatePerMillion: 0.75,
      outputRatePerMillion: 4.5,
      rateLabel: "Standard",
    });
  });

  it("caps cached input at the reported input tokens", () => {
    const estimate = estimateTokenCost(usage({ inputTokens: 100, cachedInputTokens: 500, outputTokens: 0 }));

    expect(estimate?.uncachedInputCost).toBe(0);
    expect(estimate?.cachedInputCost).toBeCloseTo((100 / 1_000_000) * 0.01, 12);
  });

  it("returns no estimate for an unknown model", () => {
    expect(estimateTokenCost(usage({ model: "gpt-unknown" }))).toBeNull();
  });
});

describe("dashboard tool image labels", () => {
  it.each([
    [{ type: "image" as const, imageUrl: "/a", mimeType: "image/png", index: 0 }, "Response image 1"],
    [{ type: "image" as const, imageUrl: "/a", mimeType: "image/png", index: 2 }, "Response image 3"],
    [{ type: "image" as const, imageUrl: "/a", mimeType: "image/png", index: 0, label: "before" as const }, "Before action"],
    [{ type: "image" as const, imageUrl: "/a", mimeType: "image/png", index: 1, label: "after" as const }, "After action"],
  ])("labels %j as %s", (image, label) => {
    expect(toolImageLabel(image)).toBe(label);
  });
});
