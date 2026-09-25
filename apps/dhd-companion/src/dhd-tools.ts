import { randomUUID } from "node:crypto";

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

import { BLOCKING_BRIDGE_TIMEOUT_MS, requestBridge } from "./phone/bridge-client.js";
import type { BridgeMessage } from "./phone/protocol.js";
import {
  DHD_ACTION_TYPES,
  DHD_KEYPRESS_KEYS,
  DHD_MAX_GUARD_REGIONS,
  DHD_MAX_SEQUENCE_ACTIONS,
  DHD_MAX_SWIPE_DURATION_MS,
  DHD_MAX_TEXT_CHARS,
  DHD_MAX_TYPE_TEXT_CHARS,
  DHD_MAX_WAIT_DURATION_MS,
  dhdToolDescription,
  isGuardRegionsEnabled,
} from "./tools/contract.js";
import type { ScreenshotMarkerPoint } from "@dhd/screenshot-markers";
import { errorMessage } from "./shared/errors.js";
import { isMainModule } from "./shared/is-main-module.js";
import { initialPointerPoint, type DhdMarkerContext } from "./tools/result/markers.js";
import { toMcpResult } from "./tools/result/to-tool-result.js";
import type { DhdToolInvocationOptions, PhoneAssistantToolResult } from "./tools/result/types.js";

export * from "./tools/contract.js";
export { normalizeScreenshot, type NormalizedScreenshot } from "./tools/result/screenshot.js";
export { toMcpResult } from "./tools/result/to-tool-result.js";
export type {
  DhdToolInvocationOptions,
  PhoneAssistantDebugImage,
  PhoneAssistantToolResult,
} from "./tools/result/types.js";

const PHONE_ACCESS_BRIDGE_OPTIONS = {
  timeoutMs: BLOCKING_BRIDGE_TIMEOUT_MS,
  keepOpenAfterAccepted: true,
};

const packageNameSchema = z
  .string()
  .min(1)
  .max(255)
  .regex(/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/);

const displayRefSchema = z.string().regex(/^dsp_[a-f0-9]{14}$/);

const displayTargetFields = {
  displayRef: displayRefSchema.optional(),
};

const guardRegionSchema = z
  .object({
    left: z.number().int().min(0),
    top: z.number().int().min(0),
    right: z.number().int().min(0),
    bottom: z.number().int().min(0)
  })
  .strict();

function createActionMetadataSchema(
  enableGuardRegions: boolean,
  requireObservationId = true
) {
  return z
    .object({
      purpose: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      targetDescription: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      ...(requireObservationId
        ? { observationId: z.string().min(1).max(DHD_MAX_TEXT_CHARS) }
        : {}),
      ...(enableGuardRegions
        ? { guardRegions: z.array(guardRegionSchema).max(DHD_MAX_GUARD_REGIONS).optional().default([]) }
        : {})
    })
    .strict();
}

export function createDhdToolSchemas(enableGuardRegions: boolean = isGuardRegionsEnabled()) {
  const actionMetadataSchema = createActionMetadataSchema(enableGuardRegions);
  // App launch is setup, not an input against a model-supplied screen. The
  // phone establishes its own pre-launch baseline before executing it.
  const openAppMetadataSchema = createActionMetadataSchema(false, false);
  const sequenceActionMetadataSchema = createActionMetadataSchema(
    enableGuardRegions,
    false
  );

  const dhdOpenAppInputSchema = z
    .object({
      packageName: packageNameSchema,
      ...displayTargetFields,
      metadata: openAppMetadataSchema
    })
    .strict();

  const dhdListAllowedAppsInputSchema = z
    .object({
      includeAll: z.boolean().optional().default(false)
    })
    .strict();

  const dhdBrowseAppInputSchema = z
    .object({
      query: z.string().trim().min(1).max(120)
    })
    .strict();

  const dhdSetAppDisplayLayoutInputSchema = z
    .object({
      packageName: packageNameSchema,
      layout: z.enum(["standard", "full_size"]),
    })
    .strict();

  const dhdListDisplaysInputSchema = z.object({}).strict();

  const dhdCloseDisplayInputSchema = z
    .object({
      displayRef: displayRefSchema,
    })
    .strict();

  const dhdGetForegroundAppInputSchema = z.object(displayTargetFields).strict();

  const dhdExecuteActionSchema = z.discriminatedUnion("type", [
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.tap),
        x: z.number().int().min(0),
        y: z.number().int().min(0),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.type),
        text: z.string().min(1).max(DHD_MAX_TYPE_TEXT_CHARS),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.swipe),
        startX: z.number().int().min(0),
        startY: z.number().int().min(0),
        endX: z.number().int().min(0),
        endY: z.number().int().min(0),
        durationMs: z.number().int().min(1).max(DHD_MAX_SWIPE_DURATION_MS).optional(),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.back),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.keypress),
        key: z.enum(DHD_KEYPRESS_KEYS),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.wait),
        durationMs: z.number().int().min(1).max(DHD_MAX_WAIT_DURATION_MS),
        metadata: actionMetadataSchema
      })
      .strict()
  ]);

  const dhdExecuteInputSchema = z
    .object({
      ...displayTargetFields,
      action: dhdExecuteActionSchema,
    })
    .strict();

  const dhdExecuteSequenceInputSchema = z
    .object({
      observationId: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      ...displayTargetFields,
      actions: z.array(
        z.discriminatedUnion("type", [
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.tap),
              x: z.number().int().min(0),
              y: z.number().int().min(0),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.type),
              text: z.string().min(1).max(DHD_MAX_TYPE_TEXT_CHARS),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.swipe),
              startX: z.number().int().min(0),
              startY: z.number().int().min(0),
              endX: z.number().int().min(0),
              endY: z.number().int().min(0),
              durationMs: z.number().int().min(1).max(DHD_MAX_SWIPE_DURATION_MS).optional(),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.back),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.keypress),
              key: z.enum(DHD_KEYPRESS_KEYS),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.wait),
              durationMs: z.number().int().min(1).max(DHD_MAX_WAIT_DURATION_MS),
              metadata: sequenceActionMetadataSchema
            })
            .strict()
        ])
      ).min(1).max(DHD_MAX_SEQUENCE_ACTIONS)
    })
    .strict();

  const dhdObserveInputSchema = z
    .object({
      purpose: z.string().min(1).max(DHD_MAX_TEXT_CHARS).optional(),
      targetDescription: z.string().min(1).max(DHD_MAX_TEXT_CHARS).optional(),
      ...displayTargetFields,
    })
    .strict();

  const dhdRequestAttentionInputSchema = z
    .object({
      reason: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      ...displayTargetFields,
    })
    .strict();

  return {
    dhdOpenAppInputSchema,
    dhdListAllowedAppsInputSchema,
    dhdBrowseAppInputSchema,
    dhdSetAppDisplayLayoutInputSchema,
    dhdListDisplaysInputSchema,
    dhdCloseDisplayInputSchema,
    dhdGetForegroundAppInputSchema,
    dhdExecuteActionSchema,
    dhdExecuteInputSchema,
    dhdObserveInputSchema,
    dhdExecuteSequenceInputSchema,
    dhdRequestAttentionInputSchema,
  };
}

const defaultDhdToolSchemas = createDhdToolSchemas(isGuardRegionsEnabled());

/**
 * This is deliberately a phone-owned action contract. It does not include a
 * shell command, package-manager operation, or arbitrary code payload.
 * `metadata.purpose` is user-visible in the phone timeline/notification.
 */
export const dhdOpenAppInputSchema = defaultDhdToolSchemas.dhdOpenAppInputSchema;
export const dhdListAllowedAppsInputSchema = defaultDhdToolSchemas.dhdListAllowedAppsInputSchema;
export const dhdBrowseAppInputSchema = defaultDhdToolSchemas.dhdBrowseAppInputSchema;
export const dhdSetAppDisplayLayoutInputSchema = defaultDhdToolSchemas.dhdSetAppDisplayLayoutInputSchema;
export const dhdListDisplaysInputSchema = defaultDhdToolSchemas.dhdListDisplaysInputSchema;
export const dhdCloseDisplayInputSchema = defaultDhdToolSchemas.dhdCloseDisplayInputSchema;
export const dhdGetForegroundAppInputSchema = defaultDhdToolSchemas.dhdGetForegroundAppInputSchema;
export const dhdExecuteActionSchema = defaultDhdToolSchemas.dhdExecuteActionSchema;
export const dhdExecuteInputSchema = defaultDhdToolSchemas.dhdExecuteInputSchema;
export const dhdObserveInputSchema = defaultDhdToolSchemas.dhdObserveInputSchema;
export const dhdExecuteSequenceInputSchema = defaultDhdToolSchemas.dhdExecuteSequenceInputSchema;
export const dhdRequestAttentionInputSchema = defaultDhdToolSchemas.dhdRequestAttentionInputSchema;

function parseInput<T>(schema: z.ZodType<T>, input: unknown): T {
  const parsed = schema.safeParse(input);
  if (!parsed.success) {
    throw new Error(`Invalid phone assistant input: ${parsed.error.message}`);
  }
  return parsed.data;
}

async function safely(
  work: () => Promise<BridgeMessage>,
  markerContext?: () => DhdMarkerContext | undefined,
  options: DhdToolInvocationOptions = {},
) {
  try {
    return toMcpResult(await work(), undefined, markerContext?.(), options);
  } catch (error) {
    console.error(`[phone-assistant-mcp] ${errorMessage(error)}`);
    return toMcpResult({ ok: false }, error);
  }
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
  const schemas = createDhdToolSchemas();
  switch (name) {
    case "dhd_list_allowed_apps":
      return safely(() => {
        const parsed = parseInput(schemas.dhdListAllowedAppsInputSchema, input);
        return requestBridge({
          type: "allowed_apps",
          tool: "dhd_list_allowed_apps",
          requestId: randomUUID(),
          includeAll: parsed.includeAll
        });
      });
    case "dhd_browse_app":
      return safely(() => {
        const parsed = parseInput(schemas.dhdBrowseAppInputSchema, input);
        return requestBridge({
          type: "browse_apps",
          tool: "dhd_browse_app",
          requestId: randomUUID(),
          query: parsed.query
        });
        });
    case "dhd_set_app_display_layout":
      return safely(() => {
        const parsed = parseInput(schemas.dhdSetAppDisplayLayoutInputSchema, input);
        return requestBridge({
          type: "set_app_display_layout",
          tool: "dhd_set_app_display_layout",
          requestId: randomUUID(),
          packageName: parsed.packageName,
          layout: parsed.layout,
        });
      });
    case "dhd_list_displays":
      return safely(() => {
        parseInput(schemas.dhdListDisplaysInputSchema, input);
        return requestBridge({
          type: "list_displays",
          tool: "dhd_list_displays",
          requestId: randomUUID(),
        });
      });
    case "dhd_close_display":
      return safely(() => {
        const parsed = parseInput(schemas.dhdCloseDisplayInputSchema, input);
        return requestBridge({
          type: "close_display",
          tool: "dhd_close_display",
          requestId: randomUUID(),
          displayRef: parsed.displayRef,
        });
      });
    case "dhd_get_foreground_app":
      return safely(() => {
        const parsed = parseInput(schemas.dhdGetForegroundAppInputSchema, input);
        return requestBridge(
          {
            type: "foreground_app",
            tool: "dhd_get_foreground_app",
            requestId: randomUUID(),
            ...(parsed.displayRef !== undefined ? { displayRef: parsed.displayRef } : {}),
          },
          PHONE_ACCESS_BRIDGE_OPTIONS,
        );
      });
    case "dhd_observe":
      return safely(() => {
        const parsed = parseInput(schemas.dhdObserveInputSchema, input);
        return requestBridge(
          {
            type: "observe",
            tool: "dhd_observe",
            requestId: randomUUID(),
            ...(parsed.purpose ? { purpose: parsed.purpose } : {}),
            ...(parsed.targetDescription ? { targetDescription: parsed.targetDescription } : {}),
            ...(parsed.displayRef !== undefined ? { displayRef: parsed.displayRef } : {}),
          },
          PHONE_ACCESS_BRIDGE_OPTIONS,
        );
      }, undefined, options);
    case "dhd_open_app":
      let openedAction: Record<string, unknown> | undefined;
      let openedInitialPointer: ScreenshotMarkerPoint | undefined;
      return safely(async () => {
        const parsed = parseInput(schemas.dhdOpenAppInputSchema, input);
        openedAction = {
          type: "open_app",
          packageName: parsed.packageName,
        };
        const message = await requestBridge(
          {
            type: "execute_action",
            tool: "dhd_open_app",
            requestId: randomUUID(),
            ...(parsed.displayRef !== undefined ? { displayRef: parsed.displayRef } : {}),
            action: {
              type: "open_app",
              packageName: parsed.packageName,
              metadata: parsed.metadata
            }
          },
          PHONE_ACCESS_BRIDGE_OPTIONS,
        );
        openedInitialPointer = initialPointerPoint(message);
        return message;
      }, () => ({
        resetMarker: true,
        action: openedAction,
        initialPointer: openedInitialPointer,
      }), options);
    case "dhd_execute":
      let executedAction: Record<string, unknown> | undefined;
      return safely(() => {
        const parsed = parseInput(schemas.dhdExecuteInputSchema, input);
        const action = parsed.action;
        executedAction = action as unknown as Record<string, unknown>;
        return requestBridge(
          {
            type: "execute_action",
            tool: "dhd_execute",
            requestId: randomUUID(),
            ...(parsed.displayRef !== undefined ? { displayRef: parsed.displayRef } : {}),
            action,
          },
          PHONE_ACCESS_BRIDGE_OPTIONS,
        );
      }, () => ({ action: executedAction }), options);
    case "dhd_execute_sequence":
      let sequenceActions: readonly Record<string, unknown>[] | undefined;
      return safely(() => {
        const parsed = parseInput(schemas.dhdExecuteSequenceInputSchema, input);
        sequenceActions = parsed.actions as readonly Record<string, unknown>[];
        return requestBridge(
          {
            type: "execute_sequence",
            tool: "dhd_execute_sequence",
            requestId: randomUUID(),
            observationId: parsed.observationId,
            ...(parsed.displayRef !== undefined ? { displayRef: parsed.displayRef } : {}),
            actions: parsed.actions
          },
          PHONE_ACCESS_BRIDGE_OPTIONS,
        );
      }, () => ({ sequenceActions }), options);
    case "dhd_request_attention":
      return safely(() => {
        const parsed = parseInput(schemas.dhdRequestAttentionInputSchema, input);
        return requestBridge(
          {
            type: "request_attention",
            tool: "dhd_request_attention",
            requestId: randomUUID(),
            reason: parsed.reason,
            ...(parsed.displayRef !== undefined ? { displayRef: parsed.displayRef } : {}),
          },
          PHONE_ACCESS_BRIDGE_OPTIONS,
        );
      });
    default:
      throw new Error(`Unknown DHD tool: ${name}`);
  }
}

export function createDhdMcpServer(
  serverName = "dhd",
  version = "0.1.0"
): McpServer {
  const enableGuardRegions = isGuardRegionsEnabled();
  const schemas = createDhdToolSchemas(enableGuardRegions);
  const server = new McpServer({ name: serverName, version });

  server.registerTool(
    "dhd_list_allowed_apps",
    {
      description: dhdToolDescription("dhd_list_allowed_apps", enableGuardRegions),
      inputSchema: schemas.dhdListAllowedAppsInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_list_allowed_apps", input)
  );

  server.registerTool(
    "dhd_browse_app",
    {
      description: dhdToolDescription("dhd_browse_app", enableGuardRegions),
      inputSchema: schemas.dhdBrowseAppInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_browse_app", input)
  );

  server.registerTool(
    "dhd_set_app_display_layout",
    {
      description: dhdToolDescription("dhd_set_app_display_layout", enableGuardRegions),
      inputSchema: schemas.dhdSetAppDisplayLayoutInputSchema.shape,
    },
    async (input) => invokeDhdTool("dhd_set_app_display_layout", input),
  );

  server.registerTool(
    "dhd_list_displays",
    {
      description: dhdToolDescription("dhd_list_displays", enableGuardRegions),
      inputSchema: schemas.dhdListDisplaysInputSchema.shape,
    },
    async (input) => invokeDhdTool("dhd_list_displays", input),
  );

  server.registerTool(
    "dhd_close_display",
    {
      description: dhdToolDescription("dhd_close_display", enableGuardRegions),
      inputSchema: schemas.dhdCloseDisplayInputSchema.shape,
    },
    async (input) => invokeDhdTool("dhd_close_display", input),
  );

  server.registerTool(
    "dhd_get_foreground_app",
    {
      description: dhdToolDescription("dhd_get_foreground_app", enableGuardRegions),
      inputSchema: schemas.dhdGetForegroundAppInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_get_foreground_app", input)
  );

  server.registerTool(
    "dhd_observe",
    {
      description: dhdToolDescription("dhd_observe", enableGuardRegions),
      inputSchema: schemas.dhdObserveInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_observe", input)
  );

  server.registerTool(
    "dhd_open_app",
    {
      description: dhdToolDescription("dhd_open_app", enableGuardRegions),
      inputSchema: schemas.dhdOpenAppInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_open_app", input)
  );

  server.registerTool(
    "dhd_execute",
    {
      description: dhdToolDescription("dhd_execute", enableGuardRegions),
      inputSchema: schemas.dhdExecuteInputSchema.shape,
    },
    async (input) => invokeDhdTool("dhd_execute", input)
  );

  server.registerTool(
    "dhd_execute_sequence",
    {
      description: dhdToolDescription("dhd_execute_sequence", enableGuardRegions),
      inputSchema: schemas.dhdExecuteSequenceInputSchema.shape
    },
    async (input) => invokeDhdTool("dhd_execute_sequence", input)
  );

  server.registerTool(
    "dhd_request_attention",
    {
      description: dhdToolDescription("dhd_request_attention", enableGuardRegions),
      inputSchema: schemas.dhdRequestAttentionInputSchema.shape,
    },
    async (input) => invokeDhdTool("dhd_request_attention", input)
  );

  return server;
}

if (isMainModule("dhd-tools")) {
  const server = createDhdMcpServer();
  void server.connect(new StdioServerTransport()).catch((error: unknown) => {
    console.error(`[phone-assistant-mcp] startup failed: ${errorMessage(error)}`);
    process.exitCode = 1;
  });
}
