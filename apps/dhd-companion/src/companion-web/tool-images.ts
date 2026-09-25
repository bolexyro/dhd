import type { CompanionToolCallDebugImage, CompanionToolCallImageContent } from "./api.js";

export type ToolImage = CompanionToolCallImageContent | CompanionToolCallDebugImage;

export function toolImageLabel(item: ToolImage): string {
  return "label" in item
    ? item.label === "before" ? "Before action" : "After action"
    : `Response image ${item.index + 1}`;
}
