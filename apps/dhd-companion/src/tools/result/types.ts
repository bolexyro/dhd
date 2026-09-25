import type { DHD_SCREENSHOT_MIME_TYPE } from "./screenshot.js";

export type AssistantTextContent = { type: "text"; text: string };
export type AssistantImageContent = {
  type: "image";
  data: string;
  mimeType: typeof DHD_SCREENSHOT_MIME_TYPE;
};

export type PhoneAssistantDebugImage = {
  type: "image";
  label: "before" | "after";
  data: string;
  mimeType: typeof DHD_SCREENSHOT_MIME_TYPE;
};

export interface DhdToolInvocationOptions {
  /** Include dashboard-only before/after screenshots in the diagnostic event. */
  includeDebugImages?: boolean;
}

export interface PhoneAssistantToolResult {
  [key: string]: unknown;
  isError?: boolean;
  content: Array<AssistantTextContent | AssistantImageContent>;
  structuredContent?: Record<string, unknown>;
  /** Never consumed by the model-facing dynamic-tool response. */
  debugImages?: PhoneAssistantDebugImage[];
}
