import { performance } from "node:perf_hooks";

/**
 * Human-readable, millisecond-resolution lifecycle timing written to stderr.
 * The companion never includes request text, tool arguments, screenshots, or
 * model output in these diagnostics.
 */
export class PhaseTimer {
  private readonly startedAt = performance.now();

  constructor(private readonly scope: string) {}

  log(phase: string, details?: string): void {
    const elapsedMs = Math.round(performance.now() - this.startedAt);
    const suffix = details ? ` ${details}` : "";
    console.error(
      `[dhd-timing] scope=${this.scope} phase=${phase} tsMs=${Date.now()} elapsedMs=${elapsedMs}${suffix}`,
    );
  }
}

export function logCompanionPhase(phase: string, details?: string): void {
  const suffix = details ? ` ${details}` : "";
  console.error(
    `[dhd-timing] scope=companion phase=${phase} tsMs=${Date.now()}${suffix}`,
  );
}
