import { randomUUID } from "node:crypto";

import { z } from "zod";

import { isGuardRegionsEnabled } from "../config/env.js";
import { requestBridge } from "../phone/bridge-client.js";
import { errorMessage } from "../shared/errors.js";
import { findDhdToolDefinition } from "./registry.js";
import { toMcpResult } from "./result/to-tool-result.js";
import type { DhdToolInvocationOptions, PhoneAssistantToolResult } from "./result/types.js";

function parseInput<T>(schema: z.ZodType<T>, input: unknown): T {
  const parsed = schema.safeParse(input);
  if (!parsed.success) {
    throw new Error(`Invalid phone assistant input: ${parsed.error.message}`);
  }
  return parsed.data;
}

/**
 * Invoke one of the phone tools without going through a second MCP transport.
 *
 * The companion registers these same operations as App Server dynamic tools so
 * a Codex turn can call the phone directly. Keeping this dispatcher beside the
 * MCP registrations prevents the two tool surfaces from drifting apart.
 */
export async function invokeDhdTool(
  name: string,
  input: unknown,
  options: DhdToolInvocationOptions = {},
): Promise<PhoneAssistantToolResult> {
  const definition = findDhdToolDefinition(name);
  if (!definition) throw new Error(`Unknown DHD tool: ${name}`);
  try {
    const parsed = parseInput(definition.inputSchema(isGuardRegionsEnabled()), input);
    const { type, ...fields } = definition.toBridgeRequest(parsed);
    const message = await requestBridge(
      { type, tool: definition.name, requestId: randomUUID(), ...fields },
      definition.bridgeOptions,
    );
    return toMcpResult(
      message,
      undefined,
      definition.markerContext?.(parsed, message),
      definition.includesDebugImages ? options : {},
    );
  } catch (error) {
    console.error(`[phone-assistant-mcp] ${errorMessage(error)}`);
    return toMcpResult({ ok: false }, error);
  }
}
