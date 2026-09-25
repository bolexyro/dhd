import { z } from "zod";

import { PHONE_ACCESS_BRIDGE_OPTIONS, defineDhdTool, displayTarget } from "../definition.js";
import { displayRefJsonSchema, objectJsonSchema } from "../json-schema.js";
import { displayTargetFields } from "../schemas.js";

export const getForegroundAppTool = defineDhdTool({
  name: "dhd_get_foreground_app",
  description:
    "Reports the Android package, activity, display context, and screenProtection metadata in a task virtual display. Pass displayRef to choose among multiple displays. If a display is expired, ended, or unavailable, follow the returned recovery message; never fall back to physical display 0. This tool is read-only and does not authorize input.",
  inputSchema: () => z.object(displayTargetFields).strict(),
  jsonSchema: () => objectJsonSchema({ displayRef: displayRefJsonSchema }),
  toBridgeRequest: (input) => ({ type: "foreground_app", ...displayTarget(input) }),
  bridgeOptions: PHONE_ACCESS_BRIDGE_OPTIONS,
});
