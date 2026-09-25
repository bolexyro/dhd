import { z } from "zod";

import { defineDhdTool } from "../definition.js";
import { displayRefJsonSchema, objectJsonSchema } from "../json-schema.js";
import { displayRefSchema } from "../schemas.js";

export const closeDisplayTool = defineDhdTool({
  name: "dhd_close_display",
  description:
    "Ends one DHD virtual display by its displayRef. Use dhd_list_displays first, then pass the exact reference. Closing an active display is rejected until its active DHD run is stopped; this never targets the physical display 0.",
  inputSchema: () =>
    z
      .object({
        displayRef: displayRefSchema,
      })
      .strict(),
  jsonSchema: () => objectJsonSchema({ displayRef: displayRefJsonSchema }, ["displayRef"]),
  toBridgeRequest: (input) => ({ type: "close_display", displayRef: input.displayRef }),
});
