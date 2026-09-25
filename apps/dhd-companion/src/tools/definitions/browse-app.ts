import { z } from "zod";

import { defineDhdTool } from "../definition.js";
import { objectJsonSchema } from "../json-schema.js";

export const browseAppTool = defineDhdTool({
  name: "dhd_browse_app",
  description:
    "Searches the phone's launchable app catalog by app names or package names and returns each match's app label, package name, and whether DHD can use it. Full Access makes every match usable; restricted mode makes only explicitly allowlisted packages usable. Use this to identify a specific package for dhd_open_app. This tool does not launch or interact with an app.",
  inputSchema: () =>
    z
      .object({
        query: z.string().trim().min(1).max(120)
      })
      .strict(),
  jsonSchema: () =>
    objectJsonSchema({ query: { type: "string", minLength: 1, maxLength: 120 } }, ["query"]),
  toBridgeRequest: (input) => ({ type: "browse_apps", query: input.query }),
});
