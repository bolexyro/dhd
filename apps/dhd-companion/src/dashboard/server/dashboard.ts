import { ConnectionMonitor } from "./connection-monitor.js";
import { DevReloadWatcher } from "./dev-reload.js";
import { PairingService } from "./pairing-service.js";
import { SseHub } from "./sse.js";
import { DashboardState } from "./state-store.js";
import { WorkerSupervisor } from "./worker-supervisor.js";

export class CompanionDashboard {
  readonly sse = new SseHub();
  readonly state = new DashboardState(this.sse);
  readonly supervisor = new WorkerSupervisor(this);
  readonly monitor = new ConnectionMonitor(this);
  readonly pairing = new PairingService(this);
  readonly devReload = new DevReloadWatcher(this.sse);
}

export const companionDashboard = new CompanionDashboard();
