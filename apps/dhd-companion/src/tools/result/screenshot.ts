export const DHD_SCREENSHOT_MIME_TYPE = "image/png" as const;
const SCREENSHOT_DATA_URL_PATTERN = /^data:([^;,]+);base64,([\s\S]*)$/i;

export interface NormalizedScreenshot {
  base64: string;
  mimeType: typeof DHD_SCREENSHOT_MIME_TYPE;
  dataUrl: string;
}

/**
 * Keep the two transport representations explicit:
 *
 * - MCP image content carries bare base64 in `data`.
 * - App Server dynamic-tool content carries a `data:` URL in `imageUrl`.
 *
 * The phone bridge currently sends bare base64, but accepting an already
 * prefixed data URL here prevents an accidental double prefix if another
 * bridge adapter is introduced later.
 */
export function normalizeScreenshot(
  value: unknown,
  declaredMimeType: unknown = DHD_SCREENSHOT_MIME_TYPE,
): NormalizedScreenshot | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") {
    throw new Error("The phone assistant returned a non-string screenshot payload.");
  }

  const raw = value.trim();
  if (!raw) return undefined;

  const declared = typeof declaredMimeType === "string" && declaredMimeType.trim()
    ? declaredMimeType.trim().toLowerCase()
    : DHD_SCREENSHOT_MIME_TYPE;
  let mimeType = declared;
  let base64 = raw;
  if (raw.startsWith("data:")) {
    const match = SCREENSHOT_DATA_URL_PATTERN.exec(raw);
    if (!match) {
      throw new Error("The phone assistant returned an invalid screenshot data URL.");
    }
    mimeType = match[1].toLowerCase();
    base64 = match[2];
    if (declared !== DHD_SCREENSHOT_MIME_TYPE && declared !== mimeType) {
      throw new Error("The screenshot MIME type does not match its data URL.");
    }
  }
  if (mimeType !== DHD_SCREENSHOT_MIME_TYPE) {
    throw new Error(`Unsupported phone screenshot MIME type: ${mimeType}.`);
  }

  base64 = base64.replace(/\s+/g, "");
  if (!base64 || !/^[A-Za-z0-9+/]+={0,2}$/.test(base64) || base64.length % 4 !== 0) {
    throw new Error("The phone assistant returned invalid base64 screenshot data.");
  }

  return {
    base64,
    mimeType: DHD_SCREENSHOT_MIME_TYPE,
    dataUrl: `data:${DHD_SCREENSHOT_MIME_TYPE};base64,${base64}`,
  };
}
