import type {
  CompanionClientApi,
  DiscoveredPhoneSnapshot,
  CompanionLogEntry,
  CompanionState,
  CompanionPlanSnapshot,
  CompanionToolCall,
  CompanionToolCallDebugImage,
  CompanionToolCallImageContent
} from "./api.js";
import { estimateTokenCost } from "./pricing.js";
import { toolImageLabel, type ToolImage } from "./tool-images.js";

function createWebApi(): CompanionClientApi {
  return {
    async getState(): Promise<CompanionState> {
      const controller = new AbortController();
      const timeoutId = window.setTimeout(() => controller.abort(), 5_000);
      try {
        const res = await fetch("/api/state", { cache: "no-store", signal: controller.signal });
        if (!res.ok) throw new Error(`Server returned ${res.status}: ${res.statusText}`);
        return res.json();
      } finally {
        window.clearTimeout(timeoutId);
      }
    },
    async discoverPhones(): Promise<DiscoveredPhoneSnapshot[]> {
      const res = await fetch("/api/discover", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      const data = await res.json() as { phones?: DiscoveredPhoneSnapshot[] };
      return Array.isArray(data.phones) ? data.phones : [];
    },
    async pairWithDiscoveredPhone(input): Promise<CompanionState> {
      const res = await fetch("/api/pair-device", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(input)
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    async checkConnection() {
      const controller = new AbortController();
      const timeoutId = window.setTimeout(() => controller.abort(), CHECK_REQUEST_TIMEOUT_MS);
      try {
        const res = await fetch("/api/check", { method: "POST", signal: controller.signal });
        if (!res.ok) {
          const data = await res.json().catch(() => ({}));
          throw new Error(data.message || `Server returned ${res.status}`);
        }
        return res.json();
      } catch (error) {
        if (controller.signal.aborted) {
          throw new Error("Timed out checking the phone assistant bridge.");
        }
        throw error;
      } finally {
        window.clearTimeout(timeoutId);
      }
    },
    async clearLogs(): Promise<CompanionState> {
      const res = await fetch("/api/clear-logs", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    async clearToolCalls(): Promise<CompanionState> {
      const res = await fetch("/api/clear-tool-calls", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    onState(callback: (state: CompanionState) => void) {
      const source = new EventSource("/api/events");
      source.onmessage = (event) => {
        try {
          const state = JSON.parse(event.data) as CompanionState;
          callback(state);
        } catch (err) {
          console.error("Failed to parse SSE state:", err);
        }
      };

      // Hot reload listener for live development
      source.addEventListener("reload", (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data) as { type?: string; file?: string };
          if (data.type === "css") {
            const links = document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]');
            links.forEach((link) => {
              const url = new URL(link.href, window.location.origin);
              url.searchParams.set("_reload", String(Date.now()));
              link.href = url.toString();
            });
          } else {
            window.location.reload();
          }
        } catch {
          window.location.reload();
        }
      });

      return () => source.close();
    }
  };
}

const api: CompanionClientApi = createWebApi();
const byId = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const elements = {
  activePhoneRequest: byId<HTMLDivElement>("active-phone-request"),
  phonePurpose: byId<HTMLHeadingElement>("phone-purpose"),
  phoneRequest: byId<HTMLPreElement>("phone-request"),
  tokenUsagePopover: byId<HTMLDivElement>("token-usage-popover"),
  tokenUsageTrigger: byId<HTMLButtonElement>("token-usage-trigger"),
  tokenUsageTriggerTotal: byId<HTMLSpanElement>("token-usage-trigger-total"),
  tokenUsageDetails: byId<HTMLDivElement>("token-usage-details"),
  tokenUsageState: byId<HTMLSpanElement>("token-usage-state"),
  tokenUsageInput: byId<HTMLElement>("token-usage-input"),
  tokenUsageOutput: byId<HTMLElement>("token-usage-output"),
  tokenUsageCachedInput: byId<HTMLElement>("token-usage-cached-input"),
  tokenUsageReasoningOutput: byId<HTMLElement>("token-usage-reasoning-output"),
  tokenUsageTotal: byId<HTMLElement>("token-usage-total"),
  tokenUsageContextWindow: byId<HTMLSpanElement>("token-usage-context-window"),
  tokenEstimatedCost: byId<HTMLElement>("token-estimated-cost"),
  tokenPricingBreakdown: byId<HTMLDivElement>("token-pricing-breakdown"),
  tokenUsageMeta: byId<HTMLDivElement>("token-usage-meta"),
  sessionBadge: byId<HTMLSpanElement>("session-badge"),
  logCount: byId<HTMLSpanElement>("log-count"),
  logCountBadge: byId<HTMLSpanElement>("log-count-badge"),
  clearLogs: byId<HTMLButtonElement>("clear-logs"),
  toolCount: byId<HTMLSpanElement>("tool-count"),
  toolCountBadge: byId<HTMLSpanElement>("tool-count-badge"),
  clearToolCalls: byId<HTMLButtonElement>("clear-tool-calls"),
  expandToolCalls: byId<HTMLInputElement>("expand-tool-calls"),
  toolList: byId<HTMLDivElement>("tool-list"),
  toolScrollContainer: byId<HTMLDivElement>("tool-scroll-container"),
  agentPlan: byId<HTMLElement>("agent-plan"),
  agentPlanProgress: byId<HTMLSpanElement>("agent-plan-progress"),
  agentPlanExplanation: byId<HTMLParagraphElement>("agent-plan-explanation"),
  agentPlanSteps: byId<HTMLOListElement>("agent-plan-steps"),
  toolImageDialog: byId<HTMLDialogElement>("tool-image-dialog"),
  toolImageDialogGallery: byId<HTMLDivElement>("tool-image-dialog-gallery"),
  toolImageDialogImage: byId<HTMLImageElement>("tool-image-dialog-image"),
  toolImageDialogLabel: byId<HTMLSpanElement>("tool-image-dialog-label"),
  closeToolImageDialog: byId<HTMLButtonElement>("close-tool-image-dialog"),
  discoverPhones: byId<HTMLButtonElement>("discover-phones"),
  discoveryStatus: byId<HTMLSpanElement>("discovery-status"),
  discoveredPhones: byId<HTMLDivElement>("discovered-phones"),
  toolsTab: byId<HTMLElement>("tab-tools"),
  logList: byId<HTMLDivElement>("log-list"),
  logScrollContainer: byId<HTMLDivElement>("log-scroll-container"),
  toast: byId<HTMLDivElement>("toast"),
  toastIcon: document.getElementById("toast-icon") as HTMLDivElement | null,
  toastMessage: document.getElementById("toast-message") as HTMLSpanElement | null,
  toastClose: document.getElementById("toast-close") as HTMLButtonElement | null,
  connectionStatusPill: document.getElementById("connection-status-pill") as HTMLSpanElement | null
};

let toastTimer: number | undefined;
const tokenFormatter = new Intl.NumberFormat();
const usdFormatter = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 4,
  maximumFractionDigits: 6,
});

const TOAST_ICONS = {
  success: `<svg class="toast-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path stroke-linecap="round" stroke-linejoin="round" d="M20 6L9 17l-5-5"/></svg>`,
  error: `<svg class="toast-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`,
  info: `<svg class="toast-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>`
};

const SPINNER_SVG = `<svg class="btn-svg spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10" stroke-opacity="0.25" stroke="currentColor" fill="none"/><path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" stroke-linecap="round"/></svg>`;
const CHECK_REQUEST_TIMEOUT_MS = 18_000;

function formatTime(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(timestamp);
}

function setText(element: HTMLElement, value: string): void {
  element.textContent = value;
}

function renderLogLine(entry: CompanionLogEntry): HTMLElement {
  const row = document.createElement("div");
  row.className = `log-line ${entry.level}`;

  const time = document.createElement("span");
  time.className = "log-time";
  time.textContent = formatTime(entry.timestamp);

  const source = document.createElement("span");
  source.className = `log-source ${entry.source}`;
  source.textContent = entry.source;

  const message = document.createElement("span");
  message.className = "log-msg";
  message.textContent = entry.message;

  row.append(time, source, message);
  return row;
}

function renderLogs(entries: CompanionLogEntry[]): void {
  elements.logList.replaceChildren();
  const countStr = `${entries.length}`;
  elements.logCount.textContent = `${entries.length} events`;
  elements.logCountBadge.textContent = countStr;

  if (entries.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-console font-mono";
    empty.textContent = "No events recorded. Phone activity and link checks will appear here.";
    elements.logList.append(empty);
    return;
  }

  for (const entry of entries) {
    elements.logList.append(renderLogLine(entry));
  }

  if (elements.logScrollContainer) {
    elements.logScrollContainer.scrollTop = elements.logScrollContainer.scrollHeight;
  }
}

function formatJson(value: unknown): string {
  try {
    const formatted = JSON.stringify(value, null, 2);
    return formatted ?? String(value);
  } catch {
    return String(value);
  }
}

async function copyText(value: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }

  const fallback = document.createElement("textarea");
  fallback.value = value;
  fallback.setAttribute("readonly", "true");
  fallback.style.position = "fixed";
  fallback.style.opacity = "0";
  document.body.append(fallback);
  fallback.select();
  const copied = document.execCommand("copy");
  fallback.remove();
  if (!copied) throw new Error("Clipboard is unavailable.");
}

function renderPayload(
  title: string,
  value: unknown,
  open: boolean,
): HTMLDetailsElement {
  const details = document.createElement("details");
  details.className = "tool-payload";
  details.open = open;

  const summary = document.createElement("summary");
  summary.className = "tool-payload-summary";
  const label = document.createElement("span");
  label.className = "tool-payload-label";
  label.textContent = title;

  const payloadText = typeof value === "string" ? value : formatJson(value);
  const copy = document.createElement("button");
  copy.className = "tool-copy-button";
  copy.type = "button";
  copy.textContent = "Copy";
  copy.setAttribute("aria-label", `Copy ${title}`);
  copy.addEventListener("click", (event) => {
    event.preventDefault();
    event.stopPropagation();
    copy.disabled = true;
    copy.textContent = "Copying…";
    void copyText(payloadText).then(() => {
      copy.textContent = "Copied";
      window.setTimeout(() => {
        copy.disabled = false;
        copy.textContent = "Copy";
      }, 1_200);
    }).catch(() => {
      copy.disabled = false;
      copy.textContent = "Copy failed";
      window.setTimeout(() => {
        copy.textContent = "Copy";
      }, 1_500);
    });
  });
  summary.append(label, copy);

  const content = document.createElement("pre");
  content.className = "tool-payload-content font-mono";
  content.textContent = payloadText;

  details.dataset.payloadTitle = title;
  details.append(summary, content);
  return details;
}

function toolCallDuration(call: CompanionToolCall): string {
  if (call.durationMs === undefined) return "in progress";
  return `${call.durationMs}ms`;
}

function openToolImageDialog(
  call: CompanionToolCall,
  items: readonly ToolImage[],
  title: string,
): void {
  const image = items[0];
  if (!image) return;

  elements.toolImageDialogLabel.textContent = `${call.tool} · ${title}`;
  elements.toolImageDialogGallery.replaceChildren();

  if (items.length === 1) {
    const imageLabel = toolImageLabel(image);
    elements.toolImageDialogGallery.hidden = true;
    elements.toolImageDialogImage.hidden = false;
    elements.toolImageDialogImage.src = image.imageUrl;
    elements.toolImageDialogImage.alt = `${call.tool} ${imageLabel.toLowerCase()}`;
  } else {
    elements.toolImageDialogImage.hidden = true;
    elements.toolImageDialogImage.removeAttribute("src");
    elements.toolImageDialogImage.alt = "";
    elements.toolImageDialogGallery.hidden = false;
    elements.toolImageDialogGallery.setAttribute(
      "aria-label",
      `${call.tool} ${title.toLowerCase()}`,
    );
    elements.toolImageDialogGallery.setAttribute("role", "group");

    for (const item of items) {
      const imageLabel = toolImageLabel(item);
      const frame = document.createElement("figure");
      frame.className = "tool-image-dialog-frame";
      const frameImage = document.createElement("img");
      frameImage.src = item.imageUrl;
      frameImage.alt = `${call.tool} ${imageLabel.toLowerCase()}`;
      frameImage.loading = "eager";
      frameImage.decoding = "async";
      const caption = document.createElement("figcaption");
      caption.textContent = imageLabel;
      frame.append(frameImage, caption);
      elements.toolImageDialogGallery.append(frame);
    }
  }

  if (!elements.toolImageDialog.open) elements.toolImageDialog.showModal();
}

function resetToolImageDialog(): void {
  elements.toolImageDialogGallery.replaceChildren();
  elements.toolImageDialogGallery.hidden = true;
  elements.toolImageDialogGallery.removeAttribute("aria-label");
  elements.toolImageDialogGallery.removeAttribute("role");
  elements.toolImageDialogImage.hidden = false;
  elements.toolImageDialogImage.removeAttribute("src");
  elements.toolImageDialogImage.alt = "Tool response image";
}

function renderToolImageSection(
  call: CompanionToolCall,
  title: string,
  images: CompanionToolCallImageContent[] | CompanionToolCallDebugImage[],
  showLabels = false,
): HTMLDivElement {
  const imageSection = document.createElement("div");
  imageSection.className = "tool-images";
  const imageHeader = document.createElement("div");
  imageHeader.className = "tool-images-header";
  const imageTitle = document.createElement("div");
  imageTitle.className = "tool-images-title";
  imageTitle.textContent = title;
  imageHeader.append(imageTitle);
  if (showLabels && images.length > 1) {
    const viewBoth = document.createElement("button");
    viewBoth.className = "tool-image-pair-button";
    viewBoth.type = "button";
    viewBoth.textContent = "View both frames";
    viewBoth.setAttribute("aria-label", `Open both ${title.toLowerCase()}`);
    viewBoth.addEventListener("click", () => {
      openToolImageDialog(call, images, "Before and after action");
    });
    imageHeader.append(viewBoth);
  }
  imageSection.append(imageHeader);

  const imageGrid = document.createElement("div");
  imageGrid.className = showLabels ? "tool-image-grid tool-debug-image-grid" : "tool-image-grid";
  for (const item of images) {
    const imageLabel = toolImageLabel(item);
    const imageAlt = `${call.tool} ${imageLabel.toLowerCase()}`;
    const previewButton = document.createElement("button");
    previewButton.className = "tool-image-preview";
    previewButton.type = "button";
    previewButton.title = showLabels && images.length > 1
      ? "Open both frames"
      : "Open image preview";
    previewButton.setAttribute(
      "aria-label",
      showLabels && images.length > 1
        ? `Open both ${title.toLowerCase()}`
        : `Open ${imageAlt}`,
    );
    const image = document.createElement("img");
    image.src = item.imageUrl;
    image.alt = imageAlt;
    image.loading = "lazy";
    image.decoding = "async";
    previewButton.append(image);
    previewButton.addEventListener("click", () => {
      openToolImageDialog(
        call,
        showLabels && images.length > 1 ? images : [item],
        showLabels && images.length > 1 ? "Before and after action" : imageLabel,
      );
    });

    if (showLabels) {
      const frame = document.createElement("figure");
      frame.className = "tool-debug-image";
      const caption = document.createElement("figcaption");
      caption.className = "tool-debug-image-label";
      caption.textContent = imageLabel;
      frame.append(previewButton, caption);
      imageGrid.append(frame);
    } else {
      imageGrid.append(previewButton);
    }
  }
  imageSection.append(imageGrid);
  return imageSection;
}

function renderToolCall(
  call: CompanionToolCall,
  open: boolean,
  openPayloadKeys: Set<string>,
  existingPayloadKeys: Set<string>,
): HTMLDetailsElement {
  const card = document.createElement("details");
  card.className = `tool-call-card ${call.status}`;
  card.dataset.callId = call.id;
  card.open = open;

  const summary = document.createElement("summary");
  summary.className = "tool-call-summary";

  const summaryLeft = document.createElement("span");
  summaryLeft.className = "tool-call-summary-left";
  const toolName = document.createElement("span");
  toolName.className = "tool-call-name font-mono";
  toolName.textContent = call.tool;
  summaryLeft.append(toolName);

  const summaryRight = document.createElement("span");
  summaryRight.className = "tool-call-summary-right";
  const status = document.createElement("span");
  status.className = `state-badge font-mono tool-call-status ${call.status}`;
  status.textContent = call.status.toUpperCase();
  const duration = document.createElement("span");
  duration.className = "tool-call-duration font-mono";
  duration.textContent = toolCallDuration(call);
  summaryRight.append(status, duration);
  summary.append(summaryLeft, summaryRight);

  const body = document.createElement("div");
  body.className = "tool-call-body";

  const metadata = document.createElement("div");
  metadata.className = "tool-call-metadata font-mono";
  metadata.textContent = [
    `started ${formatTime(call.startedAt)}`,
    call.completedAt === undefined ? "awaiting response" : `completed ${formatTime(call.completedAt)}`,
    `id ${call.id}`
  ].join("  ·  ");
  body.append(metadata);

  const argumentsKey = `${call.id}:Arguments`;
  body.append(renderPayload(
    "Arguments",
    call.arguments,
    openPayloadKeys.has(argumentsKey) || !existingPayloadKeys.has(argumentsKey),
  ));
  if (call.rawArguments) {
    const rawArgumentsKey = `${call.id}:Raw invalid arguments`;
    body.append(renderPayload(
      "Raw invalid arguments",
      call.rawArguments,
      openPayloadKeys.has(rawArgumentsKey) || !existingPayloadKeys.has(rawArgumentsKey),
    ));
  }
  if (call.response) {
    const responseKey = `${call.id}:Response`;
    body.append(renderPayload(
      "Response",
      call.response.structuredContent ?? {},
      openPayloadKeys.has(responseKey) || !existingPayloadKeys.has(responseKey),
    ));
  } else {
    const pending = document.createElement("div");
    pending.className = "tool-call-pending font-mono";
    pending.textContent = "Waiting for tool response…";
    body.append(pending);
  }

  if (call.error) {
    const error = document.createElement("div");
    error.className = "tool-call-error";
    error.textContent = call.error;
    body.append(error);
  }

  const images = call.response?.images ?? [];
  const debugImages = call.response?.debugImages ?? [];
  if (debugImages.length > 0) {
    body.append(renderToolImageSection(
      call,
      "Execution frames (before / after)",
      debugImages,
      true,
    ));
  }
  if (images.length > 0 && !debugImages.some((item) => item.label === "after")) {
    body.append(renderToolImageSection(call, `Response images (${images.length})`, images));
  }

  card.append(summary, body);
  return card;
}

let renderedToolCallsSignature: string | undefined;
const TOOL_CALLS_EXPANDED_KEY = "dhd_companion_tool_calls_expanded";
let expandToolCalls = localStorage.getItem(TOOL_CALLS_EXPANDED_KEY) === "true";
elements.expandToolCalls.checked = expandToolCalls;

function renderToolCalls(calls: CompanionToolCall[]): void {
  const signature = JSON.stringify(calls);
  if (signature === renderedToolCallsSignature) return;
  renderedToolCallsSignature = signature;

  const previousOuterScrollTop = elements.toolScrollContainer.scrollTop;
  const previousOuterScrollLeft = elements.toolScrollContainer.scrollLeft;
  const payloadScrollPositions = new Map<string, { top: number; left: number }>();
  const existingPayloadKeys = new Set<string>();
  for (const payload of elements.toolList.querySelectorAll<HTMLDetailsElement>(".tool-payload")) {
    const callId = payload.closest<HTMLDetailsElement>(".tool-call-card")?.dataset.callId;
    const title = payload.dataset.payloadTitle;
    const content = payload.querySelector<HTMLElement>(".tool-payload-content");
    if (callId && title) {
      const key = `${callId}:${title}`;
      existingPayloadKeys.add(key);
      if (content) {
        payloadScrollPositions.set(key, {
          top: content.scrollTop,
          left: content.scrollLeft,
        });
      }
    }
  }

  const existingCards = [...elements.toolList.querySelectorAll<HTMLDetailsElement>(".tool-call-card")];
  const openCallIds = new Set(
    existingCards
      .filter((card) => card.open)
      .map((card) => card.dataset.callId)
      .filter((id): id is string => Boolean(id)),
  );
  const openPayloadKeys = new Set(
    [...elements.toolList.querySelectorAll<HTMLDetailsElement>(".tool-payload[open]")]
      .map((payload) => {
        const callId = payload.closest<HTMLDetailsElement>(".tool-call-card")?.dataset.callId;
        const title = payload.dataset.payloadTitle;
        return callId && title ? `${callId}:${title}` : "";
      })
      .filter(Boolean),
  );

  elements.toolList.replaceChildren();
  elements.toolCount.textContent = `${calls.length} calls`;
  elements.toolCountBadge.textContent = `${calls.length}`;

  if (calls.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-console font-mono";
    empty.textContent = "No tool calls recorded. Send a phone request from DHD to see activity here.";
    elements.toolList.append(empty);
    elements.toolScrollContainer.scrollTop = previousOuterScrollTop;
    elements.toolScrollContainer.scrollLeft = previousOuterScrollLeft;
    return;
  }

  for (const call of calls) {
    const shouldOpen = expandToolCalls || openCallIds.has(call.id);
    elements.toolList.append(renderToolCall(call, shouldOpen, openPayloadKeys, existingPayloadKeys));
  }

  // Live state updates must never move the user's viewport. New calls remain
  // available below the current position for the user to inspect on demand.
  elements.toolScrollContainer.scrollTop = previousOuterScrollTop;
  elements.toolScrollContainer.scrollLeft = previousOuterScrollLeft;
  for (const payload of elements.toolList.querySelectorAll<HTMLDetailsElement>(".tool-payload")) {
    const callId = payload.closest<HTMLDetailsElement>(".tool-call-card")?.dataset.callId;
    const title = payload.dataset.payloadTitle;
    const content = payload.querySelector<HTMLElement>(".tool-payload-content");
    const position = callId && title ? payloadScrollPositions.get(`${callId}:${title}`) : undefined;
    if (content && position) {
      content.scrollTop = position.top;
      content.scrollLeft = position.left;
    }
  }
}

let renderedPlanSignature: string | undefined;

function renderPlan(plan: CompanionPlanSnapshot | undefined): void {
  const signature = JSON.stringify(plan ?? null);
  if (signature === renderedPlanSignature) return;
  renderedPlanSignature = signature;

  elements.agentPlan.hidden = !plan;
  elements.toolsTab.classList.toggle("has-plan", Boolean(plan));
  if (!plan) return;

  const completedCount = plan.steps.filter((step) => step.status === "completed").length;
  elements.agentPlanProgress.textContent = plan.steps.length === 0
    ? "No steps"
    : completedCount === plan.steps.length
      ? "Complete"
      : `${completedCount}/${plan.steps.length} complete`;
  elements.agentPlanExplanation.textContent = plan.explanation ?? "";
  elements.agentPlanExplanation.hidden = !plan.explanation;
  elements.agentPlanSteps.replaceChildren();

  for (const [index, step] of plan.steps.entries()) {
    const item = document.createElement("li");
    item.className = `agent-plan-step ${step.status}`;

    const number = document.createElement("span");
    number.className = "agent-plan-step-number font-mono";
    number.textContent = step.status === "completed" ? "✓" : String(index + 1);
    number.setAttribute("aria-hidden", "true");

    const label = document.createElement("span");
    label.className = "agent-plan-step-label";
    label.textContent = step.step;

    const status = document.createElement("span");
    status.className = `agent-plan-step-status font-mono ${step.status}`;
    status.textContent = step.status === "in_progress"
      ? "In progress"
      : step.status === "completed"
        ? "Done"
        : "Pending";

    item.append(number, label, status);
    elements.agentPlanSteps.append(item);
  }
}

function formatTokenCount(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : tokenFormatter.format(value);
}

function formatUsd(value: number): string {
  return value === 0 ? "$0.00" : usdFormatter.format(value);
}

function renderTokenUsage(
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

let stateRenderVersion = 0;
let lastRenderedState: string | undefined;
let serverUnavailable = false;
let stateSyncFailures = 0;

function render(next: CompanionState): void {
  serverUnavailable = false;
  stateSyncFailures = 0;
  const serializedState = JSON.stringify(next);
  if (serializedState === lastRenderedState) return;
  stateRenderVersion += 1;
  pairedDeviceId = next.settings.pairedDeviceId;
  latestBridgeStatus = next.bridgeStatus;

  if (elements.connectionStatusPill) {
    if (next.bridgeStatus === "connected") {
      elements.connectionStatusPill.textContent = "PHONE CONNECTED";
      elements.connectionStatusPill.className = "state-badge font-mono connected";
    } else if (next.bridgeStatus === "checking" || isCheckingSavedPhone()) {
      elements.connectionStatusPill.textContent = "CHECKING PHONE";
      elements.connectionStatusPill.className = "state-badge font-mono checking";
    } else {
      elements.connectionStatusPill.textContent = "PHONE NOT CONNECTED";
      elements.connectionStatusPill.className = "state-badge font-mono stopped";
    }
  }

  const phoneState = next.phone;
  const isPhoneActive = phoneState?.active === true;
  setText(elements.phonePurpose, phoneState?.currentPurpose || (isPhoneActive ? "Active Phone Session" : "Waiting for phone session..."));
  setText(elements.phoneRequest, phoneState?.request || "No active request reported by the phone.");
  elements.activePhoneRequest.hidden = !isPhoneActive;

  elements.sessionBadge.textContent = isPhoneActive ? (phoneState?.state ?? "ACTIVE").toUpperCase() : "IDLE";
  elements.sessionBadge.className = `state-badge font-mono ${isPhoneActive ? "active" : ""}`;

  renderDiscoveredPhones();
  updateDiscoveryStatus();

  renderLogs(next.logs);
  renderPlan(next.plan);
  renderToolCalls(next.toolCalls);
  renderTokenUsage(next.tokenUsage, isPhoneActive);
  lastRenderedState = serializedState;
}

function hideToast(): void {
  elements.toast.classList.remove("visible");
  if (toastTimer !== undefined) {
    window.clearTimeout(toastTimer);
    toastTimer = undefined;
  }
}

function showToast(message: string, variant: "success" | "error" | "info" = "info"): void {
  if (elements.toastMessage) {
    elements.toastMessage.textContent = message;
  } else {
    elements.toast.textContent = message;
  }

  if (elements.toastIcon) {
    elements.toastIcon.innerHTML = TOAST_ICONS[variant];
  }

  elements.toast.className = `toast-box visible ${variant}`;

  if (toastTimer !== undefined) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(hideToast, 4_000);
}

if (elements.toastClose) {
  elements.toastClose.addEventListener("click", hideToast);
}

type BridgeCheckPromise = ReturnType<CompanionClientApi["checkConnection"]>;
let connectionCheckInFlight: BridgeCheckPromise | undefined;

function runConnectionCheck(): BridgeCheckPromise {
  if (connectionCheckInFlight) return connectionCheckInFlight;
  const request = api.checkConnection();
  connectionCheckInFlight = request;
  void request.then(
    () => {
      if (connectionCheckInFlight === request) connectionCheckInFlight = undefined;
    },
    () => {
      if (connectionCheckInFlight === request) connectionCheckInFlight = undefined;
    }
  );
  return request;
}

async function refreshState(options: { verifyConnection?: boolean } = {}): Promise<void> {
  const version = stateRenderVersion;
  const state = await api.getState();
  if (version !== stateRenderVersion) return;
  render(state);
  // Verify once on every page load so a previous offline result cannot remain
  // visible forever after the phone comes back. Calls made after an explicit
  // action keep the existing behavior and only probe an unknown connection.
  const shouldVerify = state.bridgeStatus === "unknown" ||
    (options.verifyConnection === true && state.bridgeStatus !== "connected");
  if (shouldVerify && (state.settings.tokenConfigured || state.settings.pairingConfigured)) {
    const checkingState = { ...state, bridgeStatus: "checking" as const, lastError: undefined };
    if (state.bridgeStatus !== "connected") render(checkingState);
    const pendingRenderVersion = stateRenderVersion;
    void runConnectionCheck()
      .then(async (result) => {
        try {
          const checkedState = await api.getState();
          if (stateRenderVersion === pendingRenderVersion) render(checkedState);
        } catch {
          if (stateRenderVersion === pendingRenderVersion) {
            render({
              ...checkingState,
              bridgeStatus: result.ok ? "connected" : "offline",
              ...(result.ok ? {} : { lastError: result.message }),
            });
          }
        }
      })
      .catch((error: unknown) => {
        if (stateRenderVersion === pendingRenderVersion) {
          render({
            ...checkingState,
            bridgeStatus: "offline",
            lastError: error instanceof Error ? error.message : String(error),
          });
        }
      });
  }
}

let discoveredPhoneList: DiscoveredPhoneSnapshot[] = [];
let pairingDeviceId: string | undefined;
let repairDeviceId: string | undefined;
let pairedDeviceId: string | undefined;
let latestBridgeStatus: CompanionState["bridgeStatus"] = "unknown";
let discoveryInFlight: Promise<void> | undefined;
let discoveryFinished = false;
const discoverButtonIdleHtml = elements.discoverPhones.innerHTML;

function isCheckingSavedPhone(): boolean {
  return Boolean(pairedDeviceId) &&
    (latestBridgeStatus === "checking" || latestBridgeStatus === "unknown");
}

let stateSyncInFlight: Promise<void> | undefined;

function syncState(): Promise<void> {
  if (stateSyncInFlight) return stateSyncInFlight;
  const version = stateRenderVersion;
  const operation = api.getState().then((state) => {
    stateSyncFailures = 0;
    // An event or action can render a newer state while this request is on the
    // wire. The next sync can reconcile it without repainting stale data now.
    if (version === stateRenderVersion) render(state);
  }).catch((error: unknown) => {
    if (version === stateRenderVersion && ++stateSyncFailures >= 2 && !serverUnavailable) {
      serverUnavailable = true;
      stateRenderVersion += 1;
      lastRenderedState = undefined;
      if (elements.connectionStatusPill) {
        elements.connectionStatusPill.textContent = "COMPANION UNAVAILABLE";
        elements.connectionStatusPill.className = "state-badge font-mono stopped";
      }
      elements.discoverPhones.disabled = true;
      elements.discoveryStatus.textContent = "Reconnecting to the desktop companion...";
      elements.discoveredPhones.replaceChildren();
    }
    throw error;
  });
  stateSyncInFlight = operation;
  void operation.finally(() => {
    if (stateSyncInFlight === operation) stateSyncInFlight = undefined;
  }).catch(() => {});
  return operation;
}

function updateDiscoverButton(): void {
  const searching = Boolean(discoveryInFlight) || isCheckingSavedPhone();
  elements.discoverPhones.disabled = searching || serverUnavailable;
  elements.discoverPhones.innerHTML = searching
    ? `${SPINNER_SVG}<span>Searching...</span>`
    : discoverButtonIdleHtml;
}

function renderDiscoveredPhones(): void {
  elements.discoveredPhones.replaceChildren();
  if (discoveredPhoneList.length === 0) {
    if (!discoveryFinished) return;
    const empty = document.createElement("div");
    empty.className = "discovery-empty";
    const title = document.createElement("p");
    title.className = "discovery-empty-title";
    const checkingSavedPhone = isCheckingSavedPhone();
    title.textContent = latestBridgeStatus === "connected"
      ? "Phone connected"
      : checkingSavedPhone ? "Checking saved phone" : "No phones found";
    empty.append(title);
    if (latestBridgeStatus === "connected" || checkingSavedPhone) {
      const message = document.createElement("p");
      message.className = "discovery-connected-copy";
      message.textContent = latestBridgeStatus === "connected"
        ? "DHD is responding. Nearby search only lists phones available to pair."
        : "Confirming the saved connection. This may take a moment.";
      empty.append(message);
      elements.discoveredPhones.append(empty);
      return;
    }

    const steps = document.createElement("ul");
    const openApp = document.createElement("li");
    openApp.textContent = "Make sure DHD is open on your phone.";
    const sameNetwork = document.createElement("li");
    sameNetwork.textContent = "Connect both devices to the same Wi-Fi, or connect this computer to your phone's hotspot.";
    steps.append(openApp, sameNetwork);
    empty.append(steps);
    elements.discoveredPhones.append(empty);
    return;
  }

  for (const phone of discoveredPhoneList) {
    const card = document.createElement("div");
    card.className = "discovered-phone-card";

    const details = document.createElement("div");
    details.className = "discovered-phone-details";
    const name = document.createElement("div");
    name.className = "discovered-phone-name";
    name.textContent = phone.deviceName;
    const model = document.createElement("div");
    model.className = "discovered-phone-model font-mono";
    model.textContent = phone.model || "DHD phone on local network";
    details.append(name);
    if (!phone.model || !phone.deviceName.toLowerCase().includes(phone.model.toLowerCase())) {
      details.append(model);
    }

    const isSavedPhone = phone.deviceId === pairedDeviceId;
    const isConnectedPhone = isSavedPhone && latestBridgeStatus === "connected";
    const isCheckingPhone = isSavedPhone && isCheckingSavedPhone();
    const pairButton = document.createElement("button");
    pairButton.type = "button";
    pairButton.className = `action-btn ${isConnectedPhone ? "secondary" : "primary"} small discovered-phone-pair`;
    pairButton.disabled = pairingDeviceId !== undefined || isConnectedPhone || isCheckingPhone;
    pairButton.textContent = isConnectedPhone
      ? "Connected"
      : isCheckingPhone
        ? "Checking..."
      : pairingDeviceId === phone.deviceId
        ? repairDeviceId === phone.deviceId || !isSavedPhone ? "Approve on phone" : "Reconnecting..."
        : repairDeviceId === phone.deviceId ? "Pair again" : isSavedPhone ? "Reconnect" : "Pair";
    if (!isConnectedPhone) {
      pairButton.addEventListener("click", () => {
        void pairDiscoveredPhone(phone);
      });
    }

    card.append(details, pairButton);
    elements.discoveredPhones.append(card);
  }
}

function updateDiscoveryStatus(): void {
  updateDiscoverButton();
  if (latestBridgeStatus === "connected") {
    elements.discoveryStatus.textContent = "Phone connected.";
    return;
  }
  if (!discoveryFinished || discoveryInFlight || pairingDeviceId) return;
  elements.discoveryStatus.textContent = discoveredPhoneList.length === 0
    ? isCheckingSavedPhone() ? "Checking saved phone..." : "No phones answered."
    : `${discoveredPhoneList.length} phone${discoveredPhoneList.length === 1 ? "" : "s"} found.`;
}

async function discoverPhonesOnNetwork(): Promise<void> {
  if (discoveryInFlight) return discoveryInFlight;
  const operation = (async () => {
    elements.discoveryStatus.textContent = "Searching the local network...";
    try {
      discoveredPhoneList = await api.discoverPhones();
      discoveryFinished = true;
      renderDiscoveredPhones();
    } catch (error) {
      elements.discoveryStatus.textContent = "Discovery failed.";
      throw error;
    }
  })();
  discoveryInFlight = operation;
  updateDiscoverButton();
  try {
    await operation;
  } finally {
    if (discoveryInFlight === operation) discoveryInFlight = undefined;
    updateDiscoverButton();
  }
  updateDiscoveryStatus();
}

function setTokenUsagePopoverOpen(open: boolean): void {
  elements.tokenUsagePopover.classList.toggle("is-open", open);
  elements.tokenUsageTrigger.setAttribute("aria-expanded", String(open));
  elements.tokenUsageDetails.setAttribute("aria-hidden", String(!open));
}

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

// Tab Switching
document.querySelectorAll<HTMLButtonElement>(".tab-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    const tabName = btn.getAttribute("data-tab");
    if (!tabName) return;

    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.remove("active"));

    btn.classList.add("active");
    const panel = document.getElementById(`tab-${tabName}`);
    if (panel) panel.classList.add("active");
  });
});

// Clear Logs
if (elements.clearLogs) {
  elements.clearLogs.addEventListener("click", async () => {
    try {
      const nextState = await api.clearLogs();
      render(nextState);
    } catch (err) {
      showToast(err instanceof Error ? err.message : String(err), "error");
    }
  });
}

if (elements.clearToolCalls) {
  elements.clearToolCalls.addEventListener("click", async () => {
    try {
      const nextState = await api.clearToolCalls();
      render(nextState);
    } catch (err) {
      showToast(err instanceof Error ? err.message : String(err), "error");
    }
  });
}

elements.expandToolCalls.addEventListener("change", () => {
  expandToolCalls = elements.expandToolCalls.checked;
  localStorage.setItem(TOOL_CALLS_EXPANDED_KEY, String(expandToolCalls));
  for (const card of elements.toolList.querySelectorAll<HTMLDetailsElement>(".tool-call-card")) {
    card.open = expandToolCalls;
  }
});

elements.closeToolImageDialog.addEventListener("click", () => {
  elements.toolImageDialog.close();
});

elements.toolImageDialog.addEventListener("click", (event) => {
  if (event.target === elements.toolImageDialog) {
    elements.toolImageDialog.close();
  }
});

elements.toolImageDialog.addEventListener("close", resetToolImageDialog);

// Theme Management
const themeToggle = document.getElementById("theme-toggle") as HTMLButtonElement | null;
const sunIcon = document.querySelector(".theme-icon-sun") as SVGElement | null;
const moonIcon = document.querySelector(".theme-icon-moon") as SVGElement | null;

function applyTheme(theme: "dark" | "light") {
  document.documentElement.setAttribute("data-theme", theme);
  localStorage.setItem("dhd_companion_theme", theme);
  if (sunIcon && moonIcon) {
    if (theme === "light") {
      sunIcon.style.display = "none";
      moonIcon.style.display = "block";
    } else {
      sunIcon.style.display = "block";
      moonIcon.style.display = "none";
    }
  }
}

const savedTheme = (localStorage.getItem("dhd_companion_theme") as "dark" | "light" | null) || "dark";
applyTheme(savedTheme);

if (themeToggle) {
  themeToggle.addEventListener("click", () => {
    const currentTheme = document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark";
    const nextTheme = currentTheme === "dark" ? "light" : "dark";
    applyTheme(nextTheme);
  });
}

async function pairDiscoveredPhone(phone: DiscoveredPhoneSnapshot): Promise<void> {
  if (pairingDeviceId) return;
  if (phone.deviceId === pairedDeviceId && latestBridgeStatus === "connected") return;
  const reconnecting = phone.deviceId === pairedDeviceId && repairDeviceId !== phone.deviceId;
  const replacePairing = repairDeviceId === phone.deviceId;
  pairingDeviceId = phone.deviceId;
  elements.discoveryStatus.textContent = reconnecting
    ? "Reconnecting with your saved pairing..."
    : "Approve the connection request on your phone.";
  renderDiscoveredPhones();
  try {
    const responseState = await api.pairWithDiscoveredPhone({
      deviceId: phone.deviceId,
      ...(replacePairing ? { replacePairing: true } : {}),
    });
    const currentState = await api.getState().catch(() => responseState);
    render(currentState);
    repairDeviceId = undefined;
    renderDiscoveredPhones();
    if (currentState.bridgeStatus === "connected") {
      elements.discoveryStatus.textContent = "Phone connected.";
      showToast(reconnecting ? `Reconnected to ${phone.deviceName}.` : `Paired with ${phone.deviceName}.`, "success");
    }
  } catch (error) {
    const currentState = await api.getState().catch(() => undefined);
    if (currentState?.settings.pairedDeviceId === phone.deviceId && currentState.bridgeStatus === "connected") {
      repairDeviceId = undefined;
      render(currentState);
      return;
    }
    if (reconnecting) repairDeviceId = phone.deviceId;
    showToast(error instanceof Error ? error.message : String(error), "error");
  } finally {
    pairingDeviceId = undefined;
    renderDiscoveredPhones();
    updateDiscoveryStatus();
  }
}

elements.discoverPhones.addEventListener("click", async () => {
  try {
    await discoverPhonesOnNetwork();
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), "error");
  }
});

api.onState(render);
renderDiscoveredPhones();
void refreshState({ verifyConnection: true }).catch((error: unknown) => {
  showToast(error instanceof Error ? error.message : String(error), "error");
}).finally(() => {
  void discoverPhonesOnNetwork().catch((error: unknown) => {
    showToast(error instanceof Error ? error.message : String(error), "error");
  });
});

// EventSource reconnects on its own, but an interrupted stream can leave a
// still-open page behind the phone. Reconcile from the server while visible.
window.setInterval(() => {
  if (!document.hidden) void syncState().catch(() => {});
}, 5_000);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) void syncState().catch(() => {});
});
window.addEventListener("focus", () => {
  void syncState().catch(() => {});
});
