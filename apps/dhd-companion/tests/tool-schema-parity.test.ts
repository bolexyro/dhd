import { describe, expect, it } from "vitest";
import { z } from "zod";

import { DHD_TOOL_DEFINITIONS } from "../src/tools/registry.js";

type JsonSchema = Record<string, unknown>;

function unwrap(schema: z.ZodTypeAny): { schema: z.ZodTypeAny; optional: boolean } {
  let current = schema;
  let optional = false;
  while (current instanceof z.ZodOptional || current instanceof z.ZodDefault) {
    optional = true;
    current = current instanceof z.ZodOptional ? current.unwrap() : current.removeDefault();
  }
  return { schema: current, optional };
}

function stringConstraints(schema: z.ZodString): JsonSchema {
  const constraints: JsonSchema = { type: "string" };
  const trims = schema._def.checks.some((check) => check.kind === "trim");
  for (const check of schema._def.checks) {
    if (check.kind === "min") constraints.minLength = check.value;
    if (check.kind === "max") constraints.maxLength = check.value;
    if (check.kind === "regex") constraints.pattern = check.regex.source;
  }
  if (trims && constraints.minLength !== undefined && constraints.pattern === undefined) {
    constraints.pattern = "\\S";
  }
  return constraints;
}

function numberConstraints(schema: z.ZodNumber): JsonSchema {
  const constraints: JsonSchema = { type: "number" };
  for (const check of schema._def.checks) {
    if (check.kind === "int") constraints.type = "integer";
    if (check.kind === "min") constraints.minimum = check.value;
    if (check.kind === "max") constraints.maximum = check.value;
  }
  return constraints;
}

function pickValidationKeywords(json: JsonSchema, keys: string[]): JsonSchema {
  return Object.fromEntries(keys.filter((key) => key in json).map((key) => [key, json[key]]));
}

function expectParity(zodSchema: z.ZodTypeAny, json: JsonSchema, path: string): void {
  const { schema } = unwrap(zodSchema);
  if (schema instanceof z.ZodObject) {
    const shape = schema.shape as Record<string, z.ZodTypeAny>;
    const properties = (json.properties ?? {}) as Record<string, JsonSchema>;
    expect(json.type, path).toBe("object");
    expect(Object.keys(properties).sort(), `${path} properties`).toEqual(Object.keys(shape).sort());
    const required = Object.entries(shape)
      .filter(([, field]) => !unwrap(field).optional)
      .map(([key]) => key)
      .sort();
    expect([...((json.required as string[] | undefined) ?? [])].sort(), `${path} required`).toEqual(required);
    expect(json.additionalProperties, `${path} additionalProperties`).toBe(
      schema._def.unknownKeys === "strict" ? false : undefined,
    );
    for (const [key, field] of Object.entries(shape)) {
      expectParity(field, properties[key], `${path}.${key}`);
    }
    return;
  }
  if (schema instanceof z.ZodDiscriminatedUnion) {
    const options = [...schema.options] as z.ZodTypeAny[];
    const variants = (json.oneOf ?? []) as JsonSchema[];
    expect(variants, `${path} variants`).toHaveLength(options.length);
    options.forEach((option, index) => expectParity(option, variants[index], `${path}[${index}]`));
    return;
  }
  if (schema instanceof z.ZodArray) {
    expect(pickValidationKeywords(json, ["type", "minItems", "maxItems"]), path).toEqual({
      type: "array",
      ...(schema._def.minLength ? { minItems: schema._def.minLength.value } : {}),
      ...(schema._def.maxLength ? { maxItems: schema._def.maxLength.value } : {}),
    });
    expectParity(schema.element, json.items as JsonSchema, `${path}[]`);
    return;
  }
  if (schema instanceof z.ZodString) {
    expect(pickValidationKeywords(json, ["type", "minLength", "maxLength", "pattern"]), path).toEqual(
      stringConstraints(schema),
    );
    return;
  }
  if (schema instanceof z.ZodNumber) {
    expect(pickValidationKeywords(json, ["type", "minimum", "maximum"]), path).toEqual(numberConstraints(schema));
    return;
  }
  if (schema instanceof z.ZodEnum) {
    expect(pickValidationKeywords(json, ["type", "enum"]), path).toEqual({ type: "string", enum: schema.options });
    return;
  }
  if (schema instanceof z.ZodLiteral) {
    expect(json.const, path).toBe(schema.value);
    return;
  }
  if (schema instanceof z.ZodBoolean) {
    expect(json.type, path).toBe("boolean");
    return;
  }
  throw new Error(`No parity rule for ${path}`);
}

describe("DHD tool schema parity", () => {
  for (const enableGuardRegions of [false, true]) {
    for (const definition of DHD_TOOL_DEFINITIONS) {
      it(`${definition.name} JSON schema enforces the zod constraints (guard regions ${enableGuardRegions ? "on" : "off"})`, () => {
        expectParity(
          definition.inputSchema(enableGuardRegions),
          definition.jsonSchema(enableGuardRegions),
          definition.name,
        );
      });
    }
  }
});
