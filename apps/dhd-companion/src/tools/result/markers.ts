import { Buffer } from "node:buffer";

import {
  ScreenshotMarkerPresenter,
  type ScreenshotMarker,
  type ScreenshotMarkerObservation,
  type ScreenshotMarkerPoint,
} from "@dhd/screenshot-markers";

import type { BridgeMessage } from "../../phone/protocol.js";
import { errorMessage } from "../../shared/errors.js";
import { readRecord } from "../../shared/guards.js";
import { pngScreenshot, type NormalizedScreenshot } from "./screenshot.js";

const screenshotMarkerPresenter = new ScreenshotMarkerPresenter();

export function resetScreenshotMarkers(): void {
  screenshotMarkerPresenter.reset();
}

export interface DhdMarkerContext {
  resetMarker?: boolean;
  action?: Record<string, unknown>;
  sequenceActions?: readonly Record<string, unknown>[];
  initialPointer?: ScreenshotMarkerPoint;
}

export function markerObservation(message: BridgeMessage): ScreenshotMarkerObservation | undefined {
  const observation = readRecord(message.observation);
  const observationId = typeof observation.id === "string" ? observation.id : undefined;
  const displayId = typeof observation.displayId === "number" ? observation.displayId : undefined;
  const rotation = typeof observation.rotation === "number" ? observation.rotation : undefined;
  const width = typeof observation.width === "number" ? observation.width : undefined;
  const height = typeof observation.height === "number" ? observation.height : undefined;
  if (!observationId || width === undefined || height === undefined) return undefined;
  return {
    observationId,
    displayId,
    packageName: typeof observation.packageName === "string" ? observation.packageName : undefined,
    rotation,
    screenshotDimensions: { width, height },
  };
}

export function tapPoint(value: Record<string, unknown> | undefined): ScreenshotMarkerPoint | undefined {
  if (value?.type !== "tap" || !Number.isInteger(value.x) || !Number.isInteger(value.y)) {
    return undefined;
  }
  return { x: value.x as number, y: value.y as number };
}

export function initialPointerPoint(message: BridgeMessage): ScreenshotMarkerPoint | undefined {
  const pointer = readRecord(message.initialPointer);
  if (!Number.isInteger(pointer.x) || !Number.isInteger(pointer.y)) return undefined;
  return { x: pointer.x as number, y: pointer.y as number };
}

function successfulSequenceTap(
  message: BridgeMessage,
  actions: readonly Record<string, unknown>[] | undefined
): ScreenshotMarkerPoint | undefined {
  if (!actions) return undefined;
  const steps = Array.isArray(message.steps) ? message.steps : [];
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = readRecord(steps[index]);
    if (step.status !== "success" || !Number.isInteger(step.index)) continue;
    const action = actions[step.index as number];
    const point = tapPoint(action);
    if (point) return point;
  }
  return undefined;
}

function markerForContext(
  message: BridgeMessage,
  context: DhdMarkerContext | undefined
): ScreenshotMarkerPoint | undefined {
  if (!context) return undefined;
  if (context.action) {
    return message.ok === true ? tapPoint(context.action) : undefined;
  }
  return successfulSequenceTap(message, context.sequenceActions);
}

export function renderScreenshot(
  message: BridgeMessage,
  screenshot: NormalizedScreenshot,
  context: DhdMarkerContext | undefined
): { screenshot: NormalizedScreenshot; marker?: ScreenshotMarker } {
  const observation = markerObservation(message);
  if (!observation) return { screenshot };
  if (context?.resetMarker) {
    screenshotMarkerPresenter.reset(observation.displayId);
  }
  try {
    const rendered = screenshotMarkerPresenter.render(
      Buffer.from(screenshot.base64, "base64"),
      observation,
      {
        lastTap: markerForContext(message, context),
        initialPointer: context?.initialPointer,
      }
    );
    return {
      screenshot: pngScreenshot(Buffer.from(rendered.screenshot).toString("base64")),
      marker: rendered.marker,
    };
  } catch (error) {
    console.error(
      `[phone-assistant-mcp] screenshot marker render failed: ${errorMessage(error)}`
    );
    return { screenshot };
  }
}
