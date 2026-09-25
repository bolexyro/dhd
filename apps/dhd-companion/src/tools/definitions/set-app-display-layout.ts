import { z } from "zod";

import { defineDhdTool } from "../definition.js";
import { objectJsonSchema, packageNameJsonSchema } from "../json-schema.js";
import { packageNameSchema } from "../schemas.js";

export const setAppDisplayLayoutTool = defineDhdTool({
  name: "dhd_set_app_display_layout",
  description:
    "Use this when the app's task-display screenshot shows a clipped, squished, unexpectedly scaled, or large empty area layout. Set layout to full_size to give that app the larger logical canvas, or standard to restore the default. The setting applies the next time the app is opened on a task display; when the setting changes, immediately call dhd_open_app with the packageName and omit displayRef so DHD retires the incompatible display and returns a fresh one. It does not change the fixed screenshot/input pixel geometry. When you notice this scale issue, it's important that you fix it immediately instead of continuing to send input to a mis-scaled display",
  inputSchema: () =>
    z
      .object({
        packageName: packageNameSchema,
        layout: z.enum(["standard", "full_size"]),
      })
      .strict(),
  jsonSchema: () =>
    objectJsonSchema(
      {
        packageName: packageNameJsonSchema(),
        layout: { type: "string", enum: ["standard", "full_size"] },
      },
      ["packageName", "layout"],
    ),
  toBridgeRequest: (input) => ({
    type: "set_app_display_layout",
    packageName: input.packageName,
    layout: input.layout,
  }),
});
