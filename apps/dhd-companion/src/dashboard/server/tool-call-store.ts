import { compactBase64, parseBase64DataUrl } from "../../shared/base64.js";
import {
  isCompanionToolCallEvent,
  type CompanionJsonValue,
  type CompanionToolCallEvent,
} from "../../shared/companion-events.js";
import { errorMessage } from "../../shared/errors.js";
import { isPlainRecord, isRecord } from "../../shared/guards.js";
import type { CompanionToolCall, CompanionToolCallResponse } from "../client/api.js";

const MAX_TOOL_CALLS = 50;

export type ToolImageSource = "response" | "debug";

export interface StoredToolImage {
  bytes: Buffer;
  mimeType: string;
}

function toolImageKey(callId: string, index: number): string {
  return `${callId}:response:${index}`;
}

function toolDebugImageKey(callId: string, index: number): string {
  return `${callId}:debug:${index}`;
}

function toolImageUrl(callId: string, index: number, source: ToolImageSource = "response"): string {
  const suffix = source === "debug" ? "?source=debug" : "";
  return `/api/tool-calls/${encodeURIComponent(callId)}/images/${index}${suffix}`;
}

export function toJsonValue(value: unknown): CompanionJsonValue {
  if (value === null) return null;
  if (typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "number") return Number.isFinite(value) ? value : String(value);
  if (Array.isArray(value)) return value.map((item) => toJsonValue(item));
  if (typeof value === "object") {
    const object: { [key: string]: CompanionJsonValue } = {};
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      object[key] = toJsonValue(item);
    }
    return object;
  }
  return String(value);
}

export function decodeImage(value: string): Buffer | undefined {
  const raw = value.trim();
  const base64 = compactBase64(parseBase64DataUrl(raw)?.base64 ?? raw);
  if (!base64) return undefined;
  const bytes = Buffer.from(base64, "base64");
  return bytes.length > 0 ? bytes : undefined;
}

export class ToolCallStore {
  private calls: CompanionToolCall[] = [];
  private readonly images = new Map<string, StoredToolImage>();

  list(): CompanionToolCall[] {
    return [...this.calls];
  }

  image(callId: string, index: number, source: ToolImageSource): StoredToolImage | undefined {
    return this.images.get(source === "debug"
      ? toolDebugImageKey(callId, index)
      : toolImageKey(callId, index));
  }

  clear(): void {
    this.calls = [];
    this.images.clear();
  }

  ingest(value: unknown): boolean {
    if (!isCompanionToolCallEvent(value) || !value.tool.startsWith("dhd_")) return false;
    const event: CompanionToolCallEvent = value;

    if (event.phase === "started") {
      this.upsert({
        id: event.callId,
        tool: event.tool,
        arguments: toJsonValue(event.arguments),
        ...(event.rawArguments ? { rawArguments: event.rawArguments } : {}),
        startedAt: event.timestamp,
        status: "running"
      });
      return true;
    }

    const existing = this.calls.find((call) => call.id === event.callId);
    const startedAt = existing?.startedAt ?? event.completedAt;
    let response: CompanionToolCallResponse | undefined;
    let conversionError: string | undefined;
    if (event.result) {
      try {
        response = this.toolResponse(event.callId, event.result);
      } catch (error) {
        conversionError = errorMessage(error);
      }
    }
    const error = event.error || conversionError;
    this.upsert({
      id: event.callId,
      tool: event.tool,
      arguments: existing?.arguments ?? {},
      ...(existing?.rawArguments ? { rawArguments: existing.rawArguments } : {}),
      startedAt,
      completedAt: event.completedAt,
      durationMs: Math.max(0, event.completedAt - startedAt),
      status: error || event.result?.isError === true ? "error" : "success",
      ...(response ? { response } : {}),
      ...(error ? { error } : {})
    });
    return true;
  }

  toolResponse(
    callId: string,
    value: unknown,
  ): CompanionToolCallResponse | undefined {
    if (!isRecord(value)) return undefined;
    const result = value;
    this.removeImages(callId);
    const images: CompanionToolCallResponse["images"] = [];
    const rawContent = Array.isArray(result.content) ? result.content : [];

    rawContent.forEach((item, index) => {
      if (!isRecord(item)) return;
      if (
        item.type !== "image" ||
        typeof item.data !== "string" ||
        typeof item.mimeType !== "string" ||
        !item.mimeType.startsWith("image/")
      ) {
        return;
      }
      const bytes = decodeImage(item.data);
      if (!bytes) return;
      this.images.set(toolImageKey(callId, index), {
        bytes,
        mimeType: item.mimeType
      });
      images.push({
        type: "image",
        imageUrl: toolImageUrl(callId, index),
        mimeType: item.mimeType,
        index
      });
    });

    const response: CompanionToolCallResponse = { images };
    const debugImages: NonNullable<CompanionToolCallResponse["debugImages"]> = [];
    const rawDebugImages = Array.isArray(result.debugImages) ? result.debugImages : [];
    rawDebugImages.forEach((item, index) => {
      if (!isRecord(item)) return;
      if (
        item.type !== "image" ||
        (item.label !== "before" && item.label !== "after") ||
        typeof item.data !== "string" ||
        typeof item.mimeType !== "string" ||
        !item.mimeType.startsWith("image/")
      ) {
        return;
      }
      const bytes = decodeImage(item.data);
      if (!bytes) return;
      this.images.set(toolDebugImageKey(callId, index), {
        bytes,
        mimeType: item.mimeType
      });
      debugImages.push({
        type: "image",
        label: item.label,
        imageUrl: toolImageUrl(callId, index, "debug"),
        mimeType: item.mimeType,
        index
      });
    });
    if (debugImages.length > 0) response.debugImages = debugImages;
    if (result.isError === true) response.isError = true;
    if (isPlainRecord(result.structuredContent)) {
      response.structuredContent = toJsonValue(result.structuredContent) as { [key: string]: CompanionJsonValue };
    }
    return response;
  }

  private upsert(entry: CompanionToolCall): void {
    const existingIndex = this.calls.findIndex((call) => call.id === entry.id);
    if (existingIndex >= 0) {
      this.calls = this.calls.map((call, index) => index === existingIndex ? entry : call);
    } else {
      this.calls = [...this.calls, entry];
    }

    while (this.calls.length > MAX_TOOL_CALLS) {
      const evicted = this.calls.shift();
      if (evicted) this.removeImages(evicted.id);
    }
  }

  private removeImages(callId: string): void {
    const prefix = `${callId}:`;
    for (const key of this.images.keys()) {
      if (key.startsWith(prefix)) this.images.delete(key);
    }
  }
}
