const tokenFormatter = new Intl.NumberFormat();
const usdFormatter = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 4,
  maximumFractionDigits: 6,
});

export function formatTime(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(timestamp);
}

export function formatJson(value: unknown): string {
  try {
    const formatted = JSON.stringify(value, null, 2);
    return formatted ?? String(value);
  } catch {
    return String(value);
  }
}

export function formatTokenCount(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : tokenFormatter.format(value);
}

export function formatUsd(value: number): string {
  return value === 0 ? "$0.00" : usdFormatter.format(value);
}
