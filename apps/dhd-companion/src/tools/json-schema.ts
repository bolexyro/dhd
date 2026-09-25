import {
  DHD_ACTION_TYPES,
  DHD_KEYPRESS_KEYS,
  DHD_MAX_GUARD_REGIONS,
  DHD_MAX_SWIPE_DURATION_MS,
  DHD_MAX_TEXT_CHARS,
  DHD_MAX_TYPE_TEXT_CHARS,
  DHD_MAX_WAIT_DURATION_MS,
} from "./contract.js";

export type JsonSchema = Record<string, unknown>;

export const displayRefJsonSchema: JsonSchema = { type: "string", pattern: "^dsp_[a-f0-9]{14}$" };

export function textJsonSchema(): JsonSchema {
  return { type: "string", minLength: 1, maxLength: DHD_MAX_TEXT_CHARS };
}

export function objectJsonSchema(properties: Record<string, JsonSchema>, required?: string[]): JsonSchema {
  return {
    type: "object",
    properties,
    ...(required ? { required } : {}),
    additionalProperties: false,
  };
}

const guardRegionJsonSchema = objectJsonSchema(
  {
    left: { type: "integer", minimum: 0 },
    top: { type: "integer", minimum: 0 },
    right: { type: "integer", minimum: 0 },
    bottom: { type: "integer", minimum: 0 },
  },
  ["left", "top", "right", "bottom"],
);

export function metadataJsonSchema(options: {
  requireObservationId: boolean;
  enableGuardRegions: boolean;
}): JsonSchema {
  const required = ["purpose", "targetDescription"];
  return objectJsonSchema(
    {
      purpose: textJsonSchema(),
      targetDescription: textJsonSchema(),
      ...(options.requireObservationId ? { observationId: textJsonSchema() } : {}),
      ...(options.enableGuardRegions
        ? { guardRegions: { type: "array", maxItems: DHD_MAX_GUARD_REGIONS, items: guardRegionJsonSchema } }
        : {}),
    },
    options.requireObservationId ? [...required, "observationId"] : required,
  );
}

const actionVariants: Array<{ properties: Record<string, JsonSchema>; required: string[] }> = [
  {
    properties: {
      type: { const: DHD_ACTION_TYPES.tap },
      x: { type: "integer", minimum: 0 },
      y: { type: "integer", minimum: 0 },
    },
    required: ["type", "x", "y"],
  },
  {
    properties: {
      type: { const: DHD_ACTION_TYPES.type },
      text: { type: "string", minLength: 1, maxLength: DHD_MAX_TYPE_TEXT_CHARS },
    },
    required: ["type", "text"],
  },
  {
    properties: {
      type: { const: DHD_ACTION_TYPES.swipe },
      startX: { type: "integer", minimum: 0 },
      startY: { type: "integer", minimum: 0 },
      endX: { type: "integer", minimum: 0 },
      endY: { type: "integer", minimum: 0 },
      durationMs: { type: "integer", minimum: 1, maximum: DHD_MAX_SWIPE_DURATION_MS },
    },
    required: ["type", "startX", "startY", "endX", "endY"],
  },
  { properties: { type: { const: DHD_ACTION_TYPES.back } }, required: ["type"] },
  {
    properties: {
      type: { const: DHD_ACTION_TYPES.keypress },
      key: { type: "string", enum: [...DHD_KEYPRESS_KEYS] },
    },
    required: ["type", "key"],
  },
  {
    properties: {
      type: { const: DHD_ACTION_TYPES.wait },
      durationMs: { type: "integer", minimum: 1, maximum: DHD_MAX_WAIT_DURATION_MS },
    },
    required: ["type", "durationMs"],
  },
];

export function actionJsonSchema(metadata: JsonSchema): JsonSchema {
  return {
    oneOf: actionVariants.map((variant) =>
      objectJsonSchema({ ...variant.properties, metadata }, [...variant.required, "metadata"]),
    ),
  };
}
