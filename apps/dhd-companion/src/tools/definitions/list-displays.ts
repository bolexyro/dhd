import { z } from "zod";

import { defineDhdTool } from "../definition.js";
import { objectJsonSchema } from "../json-schema.js";

export const listDisplaysTool = defineDhdTool({
  name: "dhd_list_displays",
  description:
    "Lists the active and retained DHD virtual displays. Each entry includes a generation-safe displayRef, app label, package, geometry, lifecycle status, current or last purpose, and remaining retention time. Use this when more than one display is available or when a displayRef has expired, ended, or become unavailable.",
  inputSchema: () => z.object({}).strict(),
  jsonSchema: () => objectJsonSchema({}),
  toBridgeRequest: () => ({ type: "list_displays" }),
});
