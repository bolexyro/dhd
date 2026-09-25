import { SCREENSHOT_MARKER_GUIDANCE } from "@dhd/screenshot-markers";
import { z } from "zod";

import { initialPointerPoint } from "../result/markers.js";
import { PHONE_ACCESS_BRIDGE_OPTIONS, defineDhdTool, displayTarget } from "../definition.js";
import {
  displayRefJsonSchema,
  metadataJsonSchema,
  objectJsonSchema,
  packageNameJsonSchema,
} from "../json-schema.js";
import { createActionMetadataSchema, displayTargetFields, packageNameSchema } from "../schemas.js";

export const openAppTool = defineDhdTool({
  name: "dhd_open_app",
  description: `Launches one Android app on a task virtual display, reusing a valid matching display when possible and creating one when needed, without requiring a caller-supplied observation ID. Pass displayRef to choose a specific retained display. If dhd_set_app_display_layout changed the app layout, omit displayRef so DHD retires the incompatible display and creates a fresh one. If the session limit is reached, the failure response includes the current active and retained displays; close an unused display with dhd_close_display using its exact displayRef (stop its active run first if needed), then retry, or pass a retained displayRef to reuse it. On success, it returns the resulting screenshot and a fresh observation ID. In restricted mode, the requested package must be explicitly allowed; Full Access permits any launchable app. Inspect and reuse the returned observation for the next action unless the screen may have changed after the returned capture. ${SCREENSHOT_MARKER_GUIDANCE}`,
  inputSchema: () =>
    z
      .object({
        packageName: packageNameSchema,
        ...displayTargetFields,
        // App launch is setup, not an input against a model-supplied screen. The
        // phone establishes its own pre-launch baseline before executing it.
        metadata: createActionMetadataSchema(false, false)
      })
      .strict(),
  jsonSchema: () =>
    objectJsonSchema(
      {
        displayRef: displayRefJsonSchema,
        packageName: packageNameJsonSchema(),
        metadata: metadataJsonSchema({ requireObservationId: false, enableGuardRegions: false }),
      },
      ["packageName", "metadata"],
    ),
  toBridgeRequest: (input) => ({
    type: "execute_action",
    ...displayTarget(input),
    action: {
      type: "open_app",
      packageName: input.packageName,
      metadata: input.metadata
    }
  }),
  bridgeOptions: PHONE_ACCESS_BRIDGE_OPTIONS,
  includesDebugImages: true,
  markerContext: (input, response) => ({
    resetMarker: true,
    action: { type: "open_app", packageName: input.packageName },
    initialPointer: initialPointerPoint(response),
  }),
});
