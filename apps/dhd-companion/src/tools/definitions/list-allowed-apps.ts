import { z } from "zod";

import { defineDhdTool } from "../definition.js";
import { objectJsonSchema } from "../json-schema.js";

export const listAllowedAppsTool = defineDhdTool({
  name: "dhd_list_allowed_apps",
  description:
    "Reports the phone's current app-access mode. In restricted mode, the response includes the explicitly allowed package names. With Full Access, the default response confirms that any launchable app may be used without enumerating every app. Set includeAll=true to list every launchable app available under the current access mode: the complete phone catalog with Full Access, or the complete allowlist in restricted mode.",
  inputSchema: () =>
    z
      .object({
        includeAll: z.boolean().optional().default(false)
      })
      .strict(),
  jsonSchema: () => objectJsonSchema({ includeAll: { type: "boolean", default: false } }),
  toBridgeRequest: (input) => ({ type: "allowed_apps", includeAll: input.includeAll }),
});
