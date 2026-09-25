import { z } from "zod";

import { DHD_MAX_TEXT_CHARS } from "../contract.js";
import { PHONE_ACCESS_BRIDGE_OPTIONS, defineDhdTool, displayTarget } from "../definition.js";
import { displayRefJsonSchema, objectJsonSchema, textJsonSchema } from "../json-schema.js";
import { displayTargetFields } from "../schemas.js";

export const requestAttentionTool = defineDhdTool({
  name: "dhd_request_attention",
  description:
    "Blocks the Codex turn until the user reviews the selected task display and taps Done in DHD. Pass displayRef when multiple displays exist. Use this when observation.screenProtection.requiresUserAttention is true, when the task preview is blank because of protected content, or when the app requires a biometric, PIN, passcode, or other user-only step. The task display stays alive while waiting. Never guess or send typed input for biometric/PIN authentication. If the display is unavailable, follow the recovery message. After this tool returns, use its fresh observation when present or call dhd_observe before continuing.",
  inputSchema: () =>
    z
      .object({
        reason: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
        ...displayTargetFields,
      })
      .strict(),
  jsonSchema: () =>
    objectJsonSchema(
      {
        reason: textJsonSchema(),
        displayRef: displayRefJsonSchema,
      },
      ["reason"],
    ),
  toBridgeRequest: (input) => ({
    type: "request_attention",
    reason: input.reason,
    ...displayTarget(input),
  }),
  bridgeOptions: PHONE_ACCESS_BRIDGE_OPTIONS,
});
