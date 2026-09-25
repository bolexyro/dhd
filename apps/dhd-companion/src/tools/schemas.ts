import { z } from "zod";

import { isGuardRegionsEnabled } from "../config/env.js";
import {
  DHD_ACTION_TYPES,
  DHD_KEYPRESS_KEYS,
  DHD_MAX_GUARD_REGIONS,
  DHD_MAX_SEQUENCE_ACTIONS,
  DHD_MAX_SWIPE_DURATION_MS,
  DHD_MAX_TEXT_CHARS,
  DHD_MAX_TYPE_TEXT_CHARS,
  DHD_MAX_WAIT_DURATION_MS,
} from "./contract.js";

const packageNameSchema = z
  .string()
  .min(1)
  .max(255)
  .regex(/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/);

const displayRefSchema = z.string().regex(/^dsp_[a-f0-9]{14}$/);

const displayTargetFields = {
  displayRef: displayRefSchema.optional(),
};

const guardRegionSchema = z
  .object({
    left: z.number().int().min(0),
    top: z.number().int().min(0),
    right: z.number().int().min(0),
    bottom: z.number().int().min(0)
  })
  .strict();

function createActionMetadataSchema(
  enableGuardRegions: boolean,
  requireObservationId = true
) {
  return z
    .object({
      purpose: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      targetDescription: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      ...(requireObservationId
        ? { observationId: z.string().min(1).max(DHD_MAX_TEXT_CHARS) }
        : {}),
      ...(enableGuardRegions
        ? { guardRegions: z.array(guardRegionSchema).max(DHD_MAX_GUARD_REGIONS).optional().default([]) }
        : {})
    })
    .strict();
}

export function createDhdToolSchemas(enableGuardRegions: boolean = isGuardRegionsEnabled()) {
  const actionMetadataSchema = createActionMetadataSchema(enableGuardRegions);
  // App launch is setup, not an input against a model-supplied screen. The
  // phone establishes its own pre-launch baseline before executing it.
  const openAppMetadataSchema = createActionMetadataSchema(false, false);
  const sequenceActionMetadataSchema = createActionMetadataSchema(
    enableGuardRegions,
    false
  );

  const dhdOpenAppInputSchema = z
    .object({
      packageName: packageNameSchema,
      ...displayTargetFields,
      metadata: openAppMetadataSchema
    })
    .strict();

  const dhdListAllowedAppsInputSchema = z
    .object({
      includeAll: z.boolean().optional().default(false)
    })
    .strict();

  const dhdBrowseAppInputSchema = z
    .object({
      query: z.string().trim().min(1).max(120)
    })
    .strict();

  const dhdSetAppDisplayLayoutInputSchema = z
    .object({
      packageName: packageNameSchema,
      layout: z.enum(["standard", "full_size"]),
    })
    .strict();

  const dhdListDisplaysInputSchema = z.object({}).strict();

  const dhdCloseDisplayInputSchema = z
    .object({
      displayRef: displayRefSchema,
    })
    .strict();

  const dhdGetForegroundAppInputSchema = z.object(displayTargetFields).strict();

  const dhdExecuteActionSchema = z.discriminatedUnion("type", [
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.tap),
        x: z.number().int().min(0),
        y: z.number().int().min(0),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.type),
        text: z.string().min(1).max(DHD_MAX_TYPE_TEXT_CHARS),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.swipe),
        startX: z.number().int().min(0),
        startY: z.number().int().min(0),
        endX: z.number().int().min(0),
        endY: z.number().int().min(0),
        durationMs: z.number().int().min(1).max(DHD_MAX_SWIPE_DURATION_MS).optional(),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.back),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.keypress),
        key: z.enum(DHD_KEYPRESS_KEYS),
        metadata: actionMetadataSchema
      })
      .strict(),
    z
      .object({
        type: z.literal(DHD_ACTION_TYPES.wait),
        durationMs: z.number().int().min(1).max(DHD_MAX_WAIT_DURATION_MS),
        metadata: actionMetadataSchema
      })
      .strict()
  ]);

  const dhdExecuteInputSchema = z
    .object({
      ...displayTargetFields,
      action: dhdExecuteActionSchema,
    })
    .strict();

  const dhdExecuteSequenceInputSchema = z
    .object({
      observationId: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      ...displayTargetFields,
      actions: z.array(
        z.discriminatedUnion("type", [
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.tap),
              x: z.number().int().min(0),
              y: z.number().int().min(0),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.type),
              text: z.string().min(1).max(DHD_MAX_TYPE_TEXT_CHARS),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.swipe),
              startX: z.number().int().min(0),
              startY: z.number().int().min(0),
              endX: z.number().int().min(0),
              endY: z.number().int().min(0),
              durationMs: z.number().int().min(1).max(DHD_MAX_SWIPE_DURATION_MS).optional(),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.back),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.keypress),
              key: z.enum(DHD_KEYPRESS_KEYS),
              metadata: sequenceActionMetadataSchema
            })
            .strict(),
          z
            .object({
              type: z.literal(DHD_ACTION_TYPES.wait),
              durationMs: z.number().int().min(1).max(DHD_MAX_WAIT_DURATION_MS),
              metadata: sequenceActionMetadataSchema
            })
            .strict()
        ])
      ).min(1).max(DHD_MAX_SEQUENCE_ACTIONS)
    })
    .strict();

  const dhdObserveInputSchema = z
    .object({
      purpose: z.string().min(1).max(DHD_MAX_TEXT_CHARS).optional(),
      targetDescription: z.string().min(1).max(DHD_MAX_TEXT_CHARS).optional(),
      ...displayTargetFields,
    })
    .strict();

  const dhdRequestAttentionInputSchema = z
    .object({
      reason: z.string().min(1).max(DHD_MAX_TEXT_CHARS),
      ...displayTargetFields,
    })
    .strict();

  return {
    dhdOpenAppInputSchema,
    dhdListAllowedAppsInputSchema,
    dhdBrowseAppInputSchema,
    dhdSetAppDisplayLayoutInputSchema,
    dhdListDisplaysInputSchema,
    dhdCloseDisplayInputSchema,
    dhdGetForegroundAppInputSchema,
    dhdExecuteActionSchema,
    dhdExecuteInputSchema,
    dhdObserveInputSchema,
    dhdExecuteSequenceInputSchema,
    dhdRequestAttentionInputSchema,
  };
}

const defaultDhdToolSchemas = createDhdToolSchemas(isGuardRegionsEnabled());

/**
 * This is deliberately a phone-owned action contract. It does not include a
 * shell command, package-manager operation, or arbitrary code payload.
 * `metadata.purpose` is user-visible in the phone timeline/notification.
 */
export const dhdOpenAppInputSchema = defaultDhdToolSchemas.dhdOpenAppInputSchema;
export const dhdListAllowedAppsInputSchema = defaultDhdToolSchemas.dhdListAllowedAppsInputSchema;
export const dhdBrowseAppInputSchema = defaultDhdToolSchemas.dhdBrowseAppInputSchema;
export const dhdSetAppDisplayLayoutInputSchema = defaultDhdToolSchemas.dhdSetAppDisplayLayoutInputSchema;
export const dhdListDisplaysInputSchema = defaultDhdToolSchemas.dhdListDisplaysInputSchema;
export const dhdCloseDisplayInputSchema = defaultDhdToolSchemas.dhdCloseDisplayInputSchema;
export const dhdGetForegroundAppInputSchema = defaultDhdToolSchemas.dhdGetForegroundAppInputSchema;
export const dhdExecuteActionSchema = defaultDhdToolSchemas.dhdExecuteActionSchema;
export const dhdExecuteInputSchema = defaultDhdToolSchemas.dhdExecuteInputSchema;
export const dhdObserveInputSchema = defaultDhdToolSchemas.dhdObserveInputSchema;
export const dhdExecuteSequenceInputSchema = defaultDhdToolSchemas.dhdExecuteSequenceInputSchema;
export const dhdRequestAttentionInputSchema = defaultDhdToolSchemas.dhdRequestAttentionInputSchema;
