import { randomUUID } from "node:crypto";

import { requestBridge } from "../phone/bridge-client.js";
import { delay } from "../shared/delay.js";
import { errorMessage } from "../shared/errors.js";

const COMPANION_HEARTBEAT_INTERVAL_MS = 2_500;
const COMPANION_HEARTBEAT_TIMEOUT_MS = 4_000;

/** Keep phone-side companion presence alive independently of task polling. */
export async function maintainCompanionHeartbeat(
  isStopping: () => boolean,
): Promise<void> {
  let lastHealthy: boolean | undefined;
  while (!isStopping()) {
    try {
      const response = await requestBridge(
        { type: "heartbeat", requestId: randomUUID() },
        { timeoutMs: COMPANION_HEARTBEAT_TIMEOUT_MS },
      );
      if (response.ok !== true) {
        throw new Error(
          typeof response.message === "string"
            ? response.message
            : "The phone bridge rejected the companion heartbeat.",
        );
      }
      if (lastHealthy === false) {
        console.error("[phone-assistant-companion] phone bridge heartbeat restored");
      }
      lastHealthy = true;
    } catch (error) {
      if (lastHealthy !== false) {
        console.error(
          `[phone-assistant-companion] phone bridge heartbeat unavailable: ${errorMessage(error)}`,
        );
      }
      lastHealthy = false;
    }
    if (!isStopping()) await delay(COMPANION_HEARTBEAT_INTERVAL_MS);
  }
}
