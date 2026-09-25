import { CodexAppServerClient } from "../codex/app-server-client.js";
import { pollIntervalSetting } from "../config/env.js";
import { environmentBridgeTarget, isLoopbackBridgeHost } from "../phone/bridge-client.js";
import { delay } from "../shared/delay.js";
import { errorMessage } from "../shared/errors.js";
import { currentCodexTurn } from "./active-turn.js";
import { maintainCompanionHeartbeat } from "./heartbeat.js";
import { PhonePoller, parsePollInterval } from "./poll-loop.js";
import { CodexWarmup } from "./prewarm.js";
import { forceExitAfterShutdownTimeout } from "./shutdown.js";

const SHUTDOWN_EVENTS = ["SIGINT", "SIGTERM", "disconnect"] as const;

function logStartupBanner(): void {
  const target = environmentBridgeTarget();
  console.error(
    "[phone-assistant-companion] waiting for a request typed in the Android app",
  );
  console.error(
    `[phone-assistant-companion] phone bridge target ${target.host}:${target.port}`,
  );
  if (isLoopbackBridgeHost(target.host)) {
    console.error(
      "[phone-assistant-companion] loopback mode: adb forward tcp:8765 tcp:8765 works when PHONE_ASSISTANT_BRIDGE_TOKEN matches the paired phone",
    );
  } else {
    console.error(
      "[phone-assistant-companion] wireless mode: phone and laptop must share Wi-Fi and PHONE_ASSISTANT_BRIDGE_TOKEN must match DHD settings",
    );
  }
  console.error(
    "[phone-assistant-companion] a logged-in Codex CLI must be available on this companion host",
  );
}

export async function runAssistantCompanion(
  codexClient = new CodexAppServerClient(),
): Promise<void> {
  const pollIntervalMs = parsePollInterval(pollIntervalSetting());
  let stopping = false;
  let cancelForcedExit: (() => void) | undefined;
  const stop = () => {
    if (stopping) return;
    stopping = true;
    cancelForcedExit = forceExitAfterShutdownTimeout(() => codexClient.close());
    const active = currentCodexTurn();
    if (active) {
      void active.client.interrupt().catch((error) => {
        console.error(
          `[phone-assistant-companion] could not interrupt on shutdown: ${errorMessage(error)}`,
        );
      });
    }
  };
  for (const event of SHUTDOWN_EVENTS) process.once(event, stop);

  const warmup = new CodexWarmup(codexClient);
  const poller = new PhonePoller(codexClient, warmup);
  logStartupBanner();

  const heartbeatPromise = maintainCompanionHeartbeat(() => stopping);
  try {
    warmup.schedule("codex-prewarm");
    while (!stopping) {
      await poller.pollOnce();
      if (!stopping) await delay(pollIntervalMs);
    }
  } finally {
    stopping = true;
    await poller.waitForPendingRun();
    await heartbeatPromise;
    await codexClient.close();
    cancelForcedExit?.();
    for (const event of SHUTDOWN_EVENTS) process.off(event, stop);
  }
}
