import type { CodexAppServerClient } from "../codex/app-server-client.js";
import { delay } from "../shared/delay.js";
import { errorMessage } from "../shared/errors.js";
import { PhaseTimer } from "../shared/timing.js";

const PREWARM_ATTEMPTS = 2;
const PREWARM_RETRY_DELAY_MS = 500;

export async function prewarmCodexClient(
  codexClient: CodexAppServerClient,
  scope: string,
): Promise<boolean> {
  for (let attempt = 1; attempt <= PREWARM_ATTEMPTS; attempt += 1) {
    const timing = new PhaseTimer(scope);
    try {
      timing.log("start", `attempt=${attempt}`);
      await codexClient.start(timing);
      timing.log("complete", `attempt=${attempt}`);
      return true;
    } catch (error) {
      timing.log("error", `attempt=${attempt}`);
      console.error(
        `[phone-assistant-companion] Codex prewarm attempt ${attempt}/${PREWARM_ATTEMPTS} failed: ` +
          `${errorMessage(error)}`,
      );
      if (attempt < PREWARM_ATTEMPTS) await delay(PREWARM_RETRY_DELAY_MS);
    }
  }
  console.error(
    "[phone-assistant-companion] continuing without a warm Codex connection; the next request will retry startup",
  );
  return false;
}

export class CodexWarmup {
  private inFlight: Promise<boolean> | null = null;

  constructor(private readonly codexClient: CodexAppServerClient) {}

  schedule(scope: string): void {
    // Codex startup can take longer than the phone presence lease. Keep the
    // bridge poll loop alive while warming the App Server in the background.
    if (this.inFlight) return;
    const operation = prewarmCodexClient(this.codexClient, scope);
    this.inFlight = operation;
    void operation.then(
      () => {
        if (this.inFlight === operation) this.inFlight = null;
      },
      (error) => {
        console.error(
          `[phone-assistant-companion] Codex warmup runner failed: ${errorMessage(error)}`,
        );
        if (this.inFlight === operation) this.inFlight = null;
      },
    );
  }
}
