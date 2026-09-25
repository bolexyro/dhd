export const WORKER_SHUTDOWN_TIMEOUT_MS = 10_000;

export function forceExitAfterShutdownTimeout(closeCodex: () => Promise<void>): () => void {
  const timer = setTimeout(() => {
    console.error(
      "[phone-assistant-companion] shutdown did not finish in time; closing Codex and exiting",
    );
    void closeCodex().finally(() => process.exit());
  }, WORKER_SHUTDOWN_TIMEOUT_MS);
  timer.unref();
  return () => clearTimeout(timer);
}
