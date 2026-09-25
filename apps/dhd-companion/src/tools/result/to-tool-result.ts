import { Buffer } from "node:buffer";

import {
  cropScreenshotPng,
  type ScreenshotEvidenceMetadata,
  type ScreenshotMarker,
} from "@dhd/screenshot-markers";

import type { BridgeMessage } from "../../phone/protocol.js";
import { errorMessage } from "../../shared/errors.js";
import {
  markerObservation,
  renderScreenshot,
  resetScreenshotMarkers,
  tapPoint,
  type DhdMarkerContext,
} from "./markers.js";
import { withoutScreenshot } from "./sanitize.js";
import { normalizeScreenshot, type NormalizedScreenshot } from "./screenshot.js";
import type {
  AssistantImageContent,
  AssistantTextContent,
  DhdToolInvocationOptions,
  PhoneAssistantDebugImage,
  PhoneAssistantToolResult,
} from "./types.js";

export function toMcpResult(
  message: BridgeMessage,
  error?: unknown,
  markerContext?: DhdMarkerContext,
  options: DhdToolInvocationOptions = {},
): PhoneAssistantToolResult {
  const isError = Boolean(error) || message.ok === false;
  if (message.type === "stopped") resetScreenshotMarkers();
  const content: Array<AssistantTextContent | AssistantImageContent> = [
    { type: "text", text: "" }
  ];
  let screenshot = normalizeScreenshot(message.screenshotBase64, message.screenshotMimeType);
  let marker: ScreenshotMarker | undefined;
  let debugImages: PhoneAssistantDebugImage[] | undefined;
  let beforeTapImage: NormalizedScreenshot | undefined;
  let screenshotEvidence: ScreenshotEvidenceMetadata | undefined;
  let beforeScreenshot: NormalizedScreenshot | undefined;
  let screenshotRendered = false;
  const actionTap = markerContext?.action ? tapPoint(markerContext.action) : undefined;
  if (actionTap || options.includeDebugImages) {
    try {
      beforeScreenshot = normalizeScreenshot(
        message.beforeScreenshotBase64,
        message.beforeScreenshotMimeType,
      );
    } catch (debugError) {
      console.error(
        `[phone-assistant-mcp] before-screenshot evidence ignored: ${errorMessage(debugError)}`,
      );
    }
  }
  if (beforeScreenshot && screenshot && !error && message.ok === true) {
    const beforeMessage: BridgeMessage = {
      ...message,
      ...(message.beforeObservation !== undefined
        ? { observation: message.beforeObservation }
        : {})
    };
    const beforeRendered = renderScreenshot(beforeMessage, beforeScreenshot, markerContext);
    const afterRendered = renderScreenshot(message, screenshot, markerContext);
    screenshot = afterRendered.screenshot;
    marker = afterRendered.marker;
    screenshotRendered = true;
    if (actionTap) {
      try {
        const beforeObservation = markerObservation(beforeMessage);
        if (!beforeObservation || message.beforeObservation === undefined) {
          throw new Error("The before observation is missing provenance or screenshot dimensions.");
        }
        const crop = cropScreenshotPng(
          Buffer.from(beforeRendered.screenshot.base64, "base64"),
          beforeObservation.screenshotDimensions,
          actionTap,
        );
        const cropBase64 = Buffer.from(crop.screenshot).toString("base64");
        beforeTapImage = {
          base64: cropBase64,
          mimeType: beforeRendered.screenshot.mimeType,
          dataUrl: `data:${beforeRendered.screenshot.mimeType};base64,${cropBase64}`,
        };
        screenshotEvidence = {
          kind: "before_tap_crop",
          sourceObservationId: beforeObservation.observationId,
          tap: actionTap,
          coordinateSpace: "display",
          crop: crop.bounds,
        };
        if (options.includeDebugImages) {
          debugImages = [
            {
              type: "image",
              label: "before",
              data: beforeTapImage.base64,
              mimeType: beforeTapImage.mimeType,
            },
            {
              type: "image",
              label: "after",
              data: afterRendered.screenshot.base64,
              mimeType: afterRendered.screenshot.mimeType,
            },
          ];
        }
      } catch (evidenceError) {
        console.error(
          `[phone-assistant-mcp] before-tap crop failed: ${errorMessage(evidenceError)}`,
        );
      }
    } else if (options.includeDebugImages) {
      debugImages = [
        {
          type: "image",
          label: "before",
          data: beforeRendered.screenshot.base64,
          mimeType: beforeRendered.screenshot.mimeType,
        },
        {
          type: "image",
          label: "after",
          data: afterRendered.screenshot.base64,
          mimeType: afterRendered.screenshot.mimeType,
        },
      ];
    }
  }
  if (screenshot && !error) {
    if (!debugImages && !screenshotRendered) {
      const rendered = renderScreenshot(message, screenshot, markerContext);
      screenshot = rendered.screenshot;
      marker = rendered.marker;
    }
  }
  const responseMessage = withoutScreenshot(message);
  if (marker) responseMessage.screenshotMarker = marker;
  if (screenshotEvidence) responseMessage.screenshotEvidence = screenshotEvidence;
  content[0] = {
    type: "text",
    text: JSON.stringify(error ? { ok: false, message: errorMessage(error) } : responseMessage)
  };
  if (beforeTapImage) {
    content.push({ type: "image", data: beforeTapImage.base64, mimeType: beforeTapImage.mimeType });
  }
  if (screenshot) {
    content.push({ type: "image", data: screenshot.base64, mimeType: screenshot.mimeType });
  }
  return {
    ...(isError ? { isError: true } : {}),
    content,
    structuredContent: error
      ? { ok: false, message: errorMessage(error) }
      : responseMessage,
    ...(debugImages ? { debugImages } : {})
  };
}
