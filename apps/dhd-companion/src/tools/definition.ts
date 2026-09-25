import type { z } from "zod";

import { BLOCKING_BRIDGE_TIMEOUT_MS, type BridgeRequestOptions } from "../phone/bridge-client.js";
import type { BridgeMessage } from "../phone/protocol.js";
import type { DhdToolName } from "./contract.js";
import type { JsonSchema } from "./json-schema.js";
import type { DhdMarkerContext } from "./result/markers.js";

export const PHONE_ACCESS_BRIDGE_OPTIONS: BridgeRequestOptions = {
  timeoutMs: BLOCKING_BRIDGE_TIMEOUT_MS,
  keepOpenAfterAccepted: true,
};

export interface DhdBridgeRequest {
  type: string;
  [key: string]: unknown;
}

export interface DhdToolDefinition<Schema extends z.AnyZodObject = z.AnyZodObject> {
  name: DhdToolName;
  description: string;
  acceptsGuardRegions?: boolean;
  inputSchema(enableGuardRegions: boolean): Schema;
  jsonSchema(enableGuardRegions: boolean): JsonSchema;
  toBridgeRequest(input: z.output<Schema>): DhdBridgeRequest;
  bridgeOptions?: BridgeRequestOptions;
  includesDebugImages?: boolean;
  markerContext?(input: z.output<Schema>, response: BridgeMessage): DhdMarkerContext;
}

export function defineDhdTool<Schema extends z.AnyZodObject>(
  definition: DhdToolDefinition<Schema>,
): DhdToolDefinition<Schema> {
  return definition;
}

export function displayTarget(input: { displayRef?: string }): { displayRef?: string } {
  return input.displayRef !== undefined ? { displayRef: input.displayRef } : {};
}
