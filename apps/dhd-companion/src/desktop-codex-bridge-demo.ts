import { runDesktopCodexBridgeDemo } from "./dev/bridge-demo.js";
import { errorMessage } from "./shared/errors.js";
import { isMainModule } from "./shared/is-main-module.js";

export { runDesktopCodexBridgeDemo } from "./dev/bridge-demo.js";

if (isMainModule("desktop-codex-bridge-demo")) {
  runDesktopCodexBridgeDemo().catch((error: unknown) => {
    console.error(`[desktop-bridge] ${errorMessage(error)}`);
    process.exitCode = 1;
  });
}
