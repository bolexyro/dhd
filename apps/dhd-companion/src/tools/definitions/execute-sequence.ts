import { SCREENSHOT_MARKER_GUIDANCE, STALE_OBSERVATION_GUIDANCE } from "@dhd/screenshot-markers";
import { z } from "zod";

import { DHD_MAX_SEQUENCE_ACTIONS, DHD_MAX_TEXT_CHARS } from "../contract.js";
import { PHONE_ACCESS_BRIDGE_OPTIONS, defineDhdTool, displayTarget } from "../definition.js";
import {
  actionJsonSchema,
  displayRefJsonSchema,
  metadataJsonSchema,
  objectJsonSchema,
  textJsonSchema,
} from "../json-schema.js";
import { createActionMetadataSchema, createActionUnion, displayTargetFields } from "../schemas.js";

export const executeSequenceTool = defineDhdTool({
  name: "dhd_execute_sequence",
  description: `Executes up to ${DHD_MAX_SEQUENCE_ACTIONS} typed phone interactions serially from one initial observationId. Optionally pass displayRef to select the observation's display explicitly. Each step uses the verified post-action observation from the previous step, so sequence steps must not include observationId. Supported actions are tap, type, swipe, back, keypress, and wait. Swipe can scroll or page by choosing coordinates inside the intended region. open_app, shell commands, semantic targets, and execution modes are not supported. Use this only when every later target is predictable without inspecting intermediate screenshots; use dhd_execute for adaptive or branching work. The phone captures and verifies the screen after every successful step, and the response includes the final screenshot and observation only when the full sequence succeeds. If the display is expired, ended, unavailable, or a post-action observation fails, follow the returned recovery message and call dhd_observe before deciding whether to retry or continue. ${STALE_OBSERVATION_GUIDANCE} ${SCREENSHOT_MARKER_GUIDANCE}`,
  acceptsGuardRegions: true,
  inputSchema: (enableGuardRegions) =>
    z
      .object({
        observationId: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
        ...displayTargetFields,
        actions: z
          .array(createActionUnion(createActionMetadataSchema(enableGuardRegions, false)))
          .min(1)
          .max(DHD_MAX_SEQUENCE_ACTIONS)
      })
      .strict(),
  jsonSchema: (enableGuardRegions) =>
    objectJsonSchema(
      {
        displayRef: displayRefJsonSchema,
        observationId: textJsonSchema(),
        actions: {
          type: "array",
          minItems: 1,
          maxItems: DHD_MAX_SEQUENCE_ACTIONS,
          items: actionJsonSchema(metadataJsonSchema({ requireObservationId: false, enableGuardRegions })),
        },
      },
      ["observationId", "actions"],
    ),
  toBridgeRequest: (input) => ({
    type: "execute_sequence",
    observationId: input.observationId,
    ...displayTarget(input),
    actions: input.actions
  }),
  bridgeOptions: PHONE_ACCESS_BRIDGE_OPTIONS,
  includesDebugImages: true,
  markerContext: (input) => ({ sequenceActions: input.actions }),
});
