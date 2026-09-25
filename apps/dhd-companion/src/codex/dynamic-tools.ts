import { randomUUID } from "node:crypto";

import {
  emitCompanionToolCallEvent,
  type CompanionJsonValue,
  type CompanionToolCallEvent,
} from "../shared/companion-events.js";
import { isGuardRegionsEnabled } from "../config/env.js";
import { errorMessage } from "../shared/errors.js";
import { asRecord } from "../shared/guards.js";
import { isDhdToolName, type DhdToolName } from "../tools/contract.js";
import { invokeDhdTool } from "../tools/invoke.js";
import { DHD_TOOL_DEFINITIONS, dhdToolDescription } from "../tools/registry.js";
import { normalizeScreenshot } from "../tools/result/screenshot.js";
import type { PhoneAssistantToolResult } from "../tools/result/types.js";

export interface DynamicToolCallResponse {
  contentItems: Array<
    | { type: "inputText"; text: string }
    | { type: "inputImage"; imageUrl: string }
  >;
  success: boolean;
}

export interface PhoneToolFailure {
  tool: string;
  code?: string;
  outcome?: string;
  message: string;
}

export type DynamicToolSpec = Record<string, unknown>;

export interface DhdDynamicToolOptions {
  enableGuardRegions?: boolean;
}

/**
 * Register a small direct tool surface on the App Server thread. These are
 * intentionally separate names from the configured MCP tools. The App Server
 * delivers their calls to this companion; current builds normally reach that
 * request path through the bundled Code Mode host.
 */
export function buildDhdDynamicTools(
  options: DhdDynamicToolOptions = {},
): DynamicToolSpec[] {
  const enableGuardRegions =
    options.enableGuardRegions ?? isGuardRegionsEnabled();
  return DHD_TOOL_DEFINITIONS.map((definition) =>
    dynamicTool(
      definition.name,
      dhdToolDescription(definition.name, enableGuardRegions),
      definition.jsonSchema(enableGuardRegions),
    ),
  );
}

function dynamicTool(
  name: DhdToolName,
  description: string,
  inputSchema: Record<string, unknown>,
): DynamicToolSpec {
  return { type: "function", name, description, inputSchema };
}

interface DynamicToolCallOptions {
  invoke?: (name: string, input: unknown) => Promise<PhoneAssistantToolResult>;
  emit?: (event: CompanionToolCallEvent) => void;
}

export async function handleDynamicToolCall(
  value: unknown,
  options: DynamicToolCallOptions = {},
): Promise<DynamicToolCallResponse> {
  const params = asRecord(value) ?? {};
  const requestedName = extractDynamicToolName(value);
  const name = requestedName.includes(".")
    ? requestedName.slice(requestedName.lastIndexOf(".") + 1)
    : requestedName;
  const mappedName = isDhdToolName(name) ? name : undefined;
  if (!mappedName) {
    return dynamicToolFailure(
      `Unsupported dynamic phone tool: ${requestedName || "(missing tool name)"}`,
    );
  }

  const normalizedArguments = normalizeDynamicArguments(params.arguments);
  const callId = randomUUID();
  const emit = options.emit ?? emitCompanionToolCallEvent;
  const invoke = options.invoke ?? invokeDhdTool;
  emit({
    type: "dhd_tool_call",
    phase: "started",
    callId,
    tool: mappedName,
    arguments: normalizedArguments.value as CompanionJsonValue,
    ...(normalizedArguments.rawArguments
      ? { rawArguments: normalizedArguments.rawArguments }
      : {}),
    timestamp: Date.now(),
  });
  console.error(`[codex-app-server] invoking ${name}`);

  let result: PhoneAssistantToolResult | undefined;
  try {
    result = options.invoke
      ? await invoke(mappedName, normalizedArguments.value)
      : await invokeDhdTool(mappedName, normalizedArguments.value, { includeDebugImages: true });
    const response = toDynamicToolResponse(result);
    emit({
      type: "dhd_tool_call",
      phase: "completed",
      callId,
      tool: mappedName,
      result,
      completedAt: Date.now(),
    });
    return response;
  } catch (error) {
    emit({
      type: "dhd_tool_call",
      phase: "completed",
      callId,
      tool: mappedName,
      ...(result ? { result } : {}),
      error: errorMessage(error),
      completedAt: Date.now(),
    });
    throw error;
  }
}

interface NormalizedDynamicArguments {
  value: unknown;
  rawArguments?: string;
}

export function normalizeDynamicArguments(value: unknown): NormalizedDynamicArguments {
  if (value === undefined || value === null) return { value: {} };
  if (typeof value !== "string") return { value };
  try {
    return { value: JSON.parse(value) as unknown };
  } catch {
    return {
      value: { __invalidArguments: value.slice(0, 240) },
      rawArguments: value,
    };
  }
}

export function extractDynamicToolName(value: unknown): string {
  const params = asRecord(value);
  return typeof params?.tool === "string" ? params.tool : "";
}

export function extractDynamicToolFailure(
  result: DynamicToolCallResponse,
): Omit<PhoneToolFailure, "tool"> {
  for (const item of result.contentItems) {
    if (item.type !== "inputText") continue;
    try {
      const record = asRecord(JSON.parse(item.text));
      if (!record) continue;
      const failure: Omit<PhoneToolFailure, "tool"> = {
        message:
          typeof record.message === "string" && record.message.trim()
            ? record.message.trim()
            : "DHD phone tool failed.",
      };
      if (typeof record.code === "string" && record.code.trim()) {
        failure.code = record.code.trim();
      }
      if (typeof record.outcome === "string" && record.outcome.trim()) {
        failure.outcome = record.outcome.trim();
      }
      return failure;
    } catch {
      // The App Server still receives success=false. Keep a safe fallback for
      // an adapter response that is not JSON text.
    }
  }
  return { message: "DHD phone tool failed." };
}

export function toDynamicToolResponse(
  result: PhoneAssistantToolResult,
): DynamicToolCallResponse {
  const contentItems: DynamicToolCallResponse["contentItems"] = [];
  for (const item of result.content) {
    if (item.type === "text") {
      contentItems.push({ type: "inputText", text: item.text });
    } else if (item.type === "image") {
      const screenshot = normalizeScreenshot(item.data, item.mimeType);
      if (!screenshot) {
        throw new Error("The phone assistant returned an empty screenshot image.");
      }
      contentItems.push({ type: "inputImage", imageUrl: screenshot.dataUrl });
    }
  }
  if (contentItems.length === 0) {
    contentItems.push({
      type: "inputText",
      text: JSON.stringify(result.structuredContent ?? { ok: !result.isError }),
    });
  }
  return { contentItems, success: !result.isError };
}

function dynamicToolFailure(message: string): DynamicToolCallResponse {
  return {
    contentItems: [
      { type: "inputText", text: JSON.stringify({ ok: false, message }) },
    ],
    success: false,
  };
}
