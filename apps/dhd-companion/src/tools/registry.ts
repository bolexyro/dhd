import { isGuardRegionsEnabled } from "../config/env.js";
import type { DhdToolName } from "./contract.js";
import type { DhdToolDefinition } from "./definition.js";
import { browseAppTool } from "./definitions/browse-app.js";
import { closeDisplayTool } from "./definitions/close-display.js";
import { executeSequenceTool } from "./definitions/execute-sequence.js";
import { executeTool } from "./definitions/execute.js";
import { getForegroundAppTool } from "./definitions/get-foreground-app.js";
import { listAllowedAppsTool } from "./definitions/list-allowed-apps.js";
import { listDisplaysTool } from "./definitions/list-displays.js";
import { observeTool } from "./definitions/observe.js";
import { openAppTool } from "./definitions/open-app.js";
import { requestAttentionTool } from "./definitions/request-attention.js";
import { setAppDisplayLayoutTool } from "./definitions/set-app-display-layout.js";

export const DHD_TOOL_DEFINITIONS: readonly DhdToolDefinition[] = [
  listAllowedAppsTool,
  browseAppTool,
  setAppDisplayLayoutTool,
  listDisplaysTool,
  closeDisplayTool,
  getForegroundAppTool,
  observeTool,
  openAppTool,
  executeTool,
  executeSequenceTool,
  requestAttentionTool,
];

const GUARD_REGIONS_GUIDANCE =
  "When guardRegions are available, include them in the corresponding dhd_execute action or sequence step only when that target must remain visually unchanged; the phone compares them with the preceding observation screenshot.";

export function findDhdToolDefinition(name: string): DhdToolDefinition | undefined {
  return DHD_TOOL_DEFINITIONS.find((definition) => definition.name === name);
}

export function dhdToolDescription(
  name: DhdToolName,
  enableGuardRegions = false,
): string {
  const definition = DHD_TOOL_DEFINITIONS.find((candidate) => candidate.name === name);
  if (!definition) throw new Error(`Unknown DHD tool: ${name}`);
  return enableGuardRegions && definition.acceptsGuardRegions
    ? `${definition.description} ${GUARD_REGIONS_GUIDANCE}`
    : definition.description;
}

export function createDhdToolSchemas(enableGuardRegions: boolean = isGuardRegionsEnabled()) {
  const dhdExecuteInputSchema = executeTool.inputSchema(enableGuardRegions);
  return {
    dhdOpenAppInputSchema: openAppTool.inputSchema(enableGuardRegions),
    dhdListAllowedAppsInputSchema: listAllowedAppsTool.inputSchema(enableGuardRegions),
    dhdBrowseAppInputSchema: browseAppTool.inputSchema(enableGuardRegions),
    dhdSetAppDisplayLayoutInputSchema: setAppDisplayLayoutTool.inputSchema(enableGuardRegions),
    dhdListDisplaysInputSchema: listDisplaysTool.inputSchema(enableGuardRegions),
    dhdCloseDisplayInputSchema: closeDisplayTool.inputSchema(enableGuardRegions),
    dhdGetForegroundAppInputSchema: getForegroundAppTool.inputSchema(enableGuardRegions),
    dhdExecuteActionSchema: dhdExecuteInputSchema.shape.action,
    dhdExecuteInputSchema,
    dhdObserveInputSchema: observeTool.inputSchema(enableGuardRegions),
    dhdExecuteSequenceInputSchema: executeSequenceTool.inputSchema(enableGuardRegions),
    dhdRequestAttentionInputSchema: requestAttentionTool.inputSchema(enableGuardRegions),
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
