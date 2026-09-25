export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export function asRecord(value: unknown): Record<string, unknown> | null {
  return isRecord(value) ? value : null;
}

export function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return isRecord(value) && !Array.isArray(value);
}

export function readRecord(value: unknown): Record<string, unknown> {
  return isPlainRecord(value) ? value : {};
}
