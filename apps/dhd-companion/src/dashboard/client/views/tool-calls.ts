import type {
  CompanionToolCall,
  CompanionToolCallDebugImage,
  CompanionToolCallImageContent,
} from "../api.js";
import { elements } from "../dom.js";
import { formatJson, formatTime } from "../format.js";
import { toolImageLabel, type ToolImage } from "../tool-images.js";

const TOOL_CALLS_EXPANDED_KEY = "dhd_companion_tool_calls_expanded";
let renderedToolCallsSignature: string | undefined;
let expandToolCalls = false;

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


export function renderToolCalls(calls: CompanionToolCall[]): void {
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

export function initToolCalls(): void {
  expandToolCalls = localStorage.getItem(TOOL_CALLS_EXPANDED_KEY) === "true";
  elements.expandToolCalls.checked = expandToolCalls;

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
}
