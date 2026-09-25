import type http from "node:http";

import { dashboardHostSetting, dashboardPortSetting } from "../../config/env.js";
import { errorMessage } from "../../shared/errors.js";
import { companionDashboard, type CompanionDashboard } from "./dashboard.js";
import { createCompanionWebServer } from "./routes.js";
import { loadConnection } from "./settings-store.js";

const DEFAULT_WEB_PORT = 8766;
const DEFAULT_WEB_HOST = "127.0.0.1";

export async function startCompanionWebServer(
  port = DEFAULT_WEB_PORT,
  host = DEFAULT_WEB_HOST,
  dashboard: CompanionDashboard = companionDashboard,
): Promise<http.Server> {
  dashboard.state.connection = await loadConnection();
  dashboard.monitor.startHeartbeat();
  dashboard.devReload.start();
  const server = createCompanionWebServer(dashboard);

  return new Promise((resolveReady, rejectReady) => {
    server.once("error", rejectReady);
    server.listen(port, host, () => {
      // The dashboard owns the worker lifecycle. Users only need to launch
      // the dashboard; worker start/stop is intentionally not a dashboard
      // action.
      dashboard.supervisor.active = true;
      dashboard.supervisor.start();
      console.log(`\n  ======================================================`);
      console.log(`  DHD Companion Web App running at:`);
      console.log(`  http://${host}:${port}`);
      console.log(`  ======================================================\n`);
      resolveReady(server);
    });
  });
}

let dashboardShutdownPromise: Promise<void> | undefined;

function shutdownDashboard(exitCode: number, dashboard: CompanionDashboard): void {
  if (dashboardShutdownPromise) return;
  dashboard.supervisor.active = false;
  dashboardShutdownPromise = dashboard.supervisor.stop("dashboard shutdown")
    .catch((error: unknown) => {
      console.error(
        "Failed to stop the companion worker during dashboard shutdown:",
        errorMessage(error),
      );
    })
    .then(() => {
      process.exitCode = exitCode;
      process.exit();
    });
}

export function runDashboard(dashboard: CompanionDashboard = companionDashboard): void {
  const port = Number(dashboardPortSetting() || DEFAULT_WEB_PORT);
  const host = dashboardHostSetting() || DEFAULT_WEB_HOST;
  process.once("SIGINT", () => shutdownDashboard(0, dashboard));
  process.once("SIGTERM", () => shutdownDashboard(0, dashboard));
  startCompanionWebServer(port, host, dashboard).catch((err) => {
    console.error("Failed to start companion web server:", err);
    process.exit(1);
  });
}
