const DATA_URL_PATTERN = /^data:([^;,]+);base64,([\s\S]*)$/i;
const CANONICAL_BASE64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/;

export interface Base64DataUrl {
  mimeType: string;
  base64: string;
}

export function parseBase64DataUrl(value: string): Base64DataUrl | null {
  const match = DATA_URL_PATTERN.exec(value);
  return match ? { mimeType: match[1], base64: match[2] } : null;
}

export function compactBase64(value: string): string | null {
  const compact = value.replace(/\s+/g, "");
  return compact && CANONICAL_BASE64_PATTERN.test(compact) && compact.length % 4 === 0 ? compact : null;
}
