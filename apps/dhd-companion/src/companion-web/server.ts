import { runDashboard } from "../dashboard/server/main.js";
import { isMainModule } from "../shared/is-main-module.js";

export { companionDashboard } from "../dashboard/server/dashboard.js";
export { startCompanionWebServer } from "../dashboard/server/main.js";
export { createCompanionWebServer } from "../dashboard/server/routes.js";

if (isMainModule("server")) {
  runDashboard();
}
