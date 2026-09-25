import type { CompanionState } from "../api.js";
import { elements } from "../dom.js";
import { formatTime, formatTokenCount, formatUsd } from "../format.js";
import { estimateTokenCost } from "../pricing.js";

export function renderTokenUsage(
  usage: CompanionState["tokenUsage"],
  isPhoneActive: boolean,
): void {
  const hasUsage = Boolean(usage);
  const status = !usage ? "NO DATA" : isPhoneActive ? "LIVE" : "LAST";
  elements.tokenUsageTriggerTotal.textContent = formatTokenCount(usage?.totalTokens);
  elements.tokenUsageState.textContent = status;
  elements.tokenUsageState.className = `state-badge font-mono ${usage && isPhoneActive ? "running" : ""}`;
  elements.tokenUsageInput.textContent = formatTokenCount(usage?.inputTokens);
  elements.tokenUsageOutput.textContent = formatTokenCount(usage?.outputTokens);
  elements.tokenUsageCachedInput.textContent = formatTokenCount(usage?.cachedInputTokens);
  elements.tokenUsageReasoningOutput.textContent = formatTokenCount(usage?.reasoningOutputTokens);
  elements.tokenUsageTotal.textContent = formatTokenCount(usage?.totalTokens);
  elements.tokenUsageContextWindow.textContent = formatTokenCount(usage?.modelContextWindow);
  const estimate = usage ? estimateTokenCost(usage) : null;
  elements.tokenEstimatedCost.textContent = estimate ? formatUsd(estimate.totalCost) : "—";
  elements.tokenPricingBreakdown.textContent = !usage
    ? "No pricing estimate available yet."
    : !estimate
      ? "No API rate card is configured for " + (usage.model ?? "the active model") + "."
      : estimate.model + " · " + estimate.rateLabel +
        " · rates " + formatUsd(estimate.inputRatePerMillion) + "/M input, " +
        formatUsd(estimate.cachedInputRatePerMillion) + "/M cached, " +
        formatUsd(estimate.outputRatePerMillion) + "/M output" +
        ": " + formatTokenCount(Math.max(0, usage.inputTokens - Math.min(usage.inputTokens, usage.cachedInputTokens))) +
        " uncached input " + formatUsd(estimate.uncachedInputCost) +
        " + " + formatTokenCount(Math.min(usage.inputTokens, usage.cachedInputTokens)) +
        " cached input " + formatUsd(estimate.cachedInputCost) +
        " + " + formatTokenCount(usage.outputTokens) +
        " output " + formatUsd(estimate.outputCost) +
        " · cache writes not included";
  elements.tokenUsageMeta.textContent = hasUsage && usage
    ? `turn ${usage.turnId.slice(0, 8)}  ·  updated ${formatTime(usage.updatedAt)}`
    : "No App Server token usage reported yet.";
}

function setTokenUsagePopoverOpen(open: boolean): void {
  elements.tokenUsagePopover.classList.toggle("is-open", open);
  elements.tokenUsageTrigger.setAttribute("aria-expanded", String(open));
  elements.tokenUsageDetails.setAttribute("aria-hidden", String(!open));
}

export function initTokenUsagePopover(): void {
  elements.tokenUsageTrigger.addEventListener("click", () => {
    setTokenUsagePopoverOpen(!elements.tokenUsagePopover.classList.contains("is-open"));
  });

  elements.tokenUsagePopover.addEventListener("mouseenter", () => {
    elements.tokenUsageDetails.setAttribute("aria-hidden", "false");
  });

  elements.tokenUsagePopover.addEventListener("mouseleave", () => {
    if (!elements.tokenUsagePopover.classList.contains("is-open")) {
      elements.tokenUsageDetails.setAttribute("aria-hidden", "true");
    }
  });

  elements.tokenUsagePopover.addEventListener("focusin", () => {
    elements.tokenUsageDetails.setAttribute("aria-hidden", "false");
  });

  elements.tokenUsagePopover.addEventListener("focusout", (event) => {
    const nextFocusedElement = event.relatedTarget as Node | null;
    if (!nextFocusedElement || !elements.tokenUsagePopover.contains(nextFocusedElement)) {
      if (!elements.tokenUsagePopover.classList.contains("is-open")) {
        elements.tokenUsageDetails.setAttribute("aria-hidden", "true");
      }
    }
  });

  document.addEventListener("click", (event) => {
    const target = event.target;
    if (target instanceof Node && !elements.tokenUsagePopover.contains(target)) {
      setTokenUsagePopoverOpen(false);
    }
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && elements.tokenUsagePopover.classList.contains("is-open")) {
      setTokenUsagePopoverOpen(false);
      elements.tokenUsageTrigger.focus();
    }
  });
}
