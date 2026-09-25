import { SCREENSHOT_MARKER_GUIDANCE } from "@dhd/screenshot-markers";
import { z } from "zod";

import { DHD_MAX_TEXT_CHARS } from "../contract.js";
import { PHONE_ACCESS_BRIDGE_OPTIONS, defineDhdTool, displayTarget } from "../definition.js";
import { displayRefJsonSchema, objectJsonSchema, textJsonSchema } from "../json-schema.js";
import { displayTargetFields } from "../schemas.js";

export const observeTool = defineDhdTool({
  name: "dhd_observe",
  description: `Captures a selected task virtual display and returns its screenshot, foreground context, display details, screenProtection metadata, and a new observation ID. Pass displayRef when working with more than one display. If the display is expired, ended, unavailable, or another display must be selected, follow the returned message and use dhd_list_displays or dhd_open_app as directed; DHD never falls back to display 0. If screenProtection.requiresUserAttention is true, call dhd_request_attention and wait for the user's Done acknowledgement before sending more input. Use this when no usable observation is available, after an observation-related failure, or when the screen may have changed independently. Do not call it repeatedly for an unchanged screen or immediately after a successful dhd_open_app, dhd_execute, or dhd_execute_sequence; those tools already return a fresh observation. ${SCREENSHOT_MARKER_GUIDANCE}`,
  inputSchema: () =>
    z
      .object({
        purpose: z.string().min(1).max(DHD_MAX_TEXT_CHARS).optional(),
        targetDescription: z.string().min(1).max(DHD_MAX_TEXT_CHARS).optional(),
        ...displayTargetFields,
      })
      .strict(),
  jsonSchema: () =>
    objectJsonSchema({
      purpose: textJsonSchema(),
      targetDescription: textJsonSchema(),
      displayRef: displayRefJsonSchema,
    }),
  toBridgeRequest: (input) => ({
    type: "observe",
    ...(input.purpose ? { purpose: input.purpose } : {}),
    ...(input.targetDescription ? { targetDescription: input.targetDescription } : {}),
    ...displayTarget(input),
  }),
  bridgeOptions: PHONE_ACCESS_BRIDGE_OPTIONS,
  includesDebugImages: true,
});
