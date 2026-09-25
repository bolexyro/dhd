import { errorMessage } from "./shared/errors.js";
import { isMainModule } from "./shared/is-main-module.js";
import { runAssistantCompanion } from "./worker/main.js";

export { CodexAppServerClient } from "./codex/app-server-client.js";
export {
  buildDhdDynamicTools,
  handleDynamicToolCall,
  toDynamicToolResponse,
  type DhdDynamicToolOptions,
  type DynamicToolCallResponse,
} from "./codex/dynamic-tools.js";
export {
  extractCompanionPlanUpdatedEvent,
  extractCompanionTokenUsageEvent,
} from "./codex/extract.js";
export { runAssistantCompanion } from "./worker/main.js";
export { shouldInterruptForPhoneStop } from "./worker/steer.js";

if (isMainModule("assistant-companion")) {
  runAssistantCompanion().catch((error: unknown) => {
    console.error(
      `[phone-assistant-companion] ${errorMessage(error)}`,
    );
    process.exitCode = 1;
  });
}
