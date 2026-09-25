import { SCREENSHOT_MARKER_GUIDANCE, STALE_OBSERVATION_GUIDANCE } from "@dhd/screenshot-markers";
import { z } from "zod";

import { PHONE_ACCESS_BRIDGE_OPTIONS, defineDhdTool, displayTarget } from "../definition.js";
import { actionJsonSchema, displayRefJsonSchema, metadataJsonSchema, objectJsonSchema } from "../json-schema.js";
import { createActionMetadataSchema, createActionUnion, displayTargetFields } from "../schemas.js";

export const executeTool = defineDhdTool({
  name: "dhd_execute",
  description: `Executes one typed phone interaction against the screen identified by metadata.observationId. Optionally pass displayRef to select a display explicitly; the observation must belong to that same display. Supported actions are tap, type, swipe, scroll, back, keypress, and wait. A scroll may include both x and y task-display coordinates to choose the center of the gesture; omit both to scroll at the display center. Use swipe for scrolling or horizontal paging by choosing startX/startY inside the intended scrollable or carousel region and endX/endY in the desired direction. On success, the response includes the resulting screenshot and a fresh observation ID; inspect and reuse that observation for the next action. If the display is expired, ended, unavailable, or the pre-action state is stale, no input is sent; follow the recovery message and obtain a fresh observation. If the post-action observation fails, the outcome is unknown; call dhd_observe before deciding whether to retry or continue. Raw shell commands are not supported. ${STALE_OBSERVATION_GUIDANCE} ${SCREENSHOT_MARKER_GUIDANCE}`,
  acceptsGuardRegions: true,
  inputSchema: (enableGuardRegions) =>
    z
      .object({
        ...displayTargetFields,
        action: createActionUnion(createActionMetadataSchema(enableGuardRegions)),
      })
      .strict(),
  jsonSchema: (enableGuardRegions) =>
    objectJsonSchema(
      {
        displayRef: displayRefJsonSchema,
        action: actionJsonSchema(metadataJsonSchema({ requireObservationId: true, enableGuardRegions })),
      },
      ["action"],
    ),
  toBridgeRequest: (input) => ({
    type: "execute_action",
    ...displayTarget(input),
    action: input.action,
  }),
  bridgeOptions: PHONE_ACCESS_BRIDGE_OPTIONS,
  includesDebugImages: true,
  markerContext: (input) => ({ action: input.action }),
});
