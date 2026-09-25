import { errorMessage } from "../../shared/errors.js";
import { SingleFlight } from "../../shared/single-flight.js";
import type { BridgeCheckResult, CompanionClientApi, CompanionState } from "./api.js";
import { elements, setText } from "./dom.js";
import { isCheckingSavedPhone, type ConnectionView } from "./session.js";
import { DiscoveryView } from "./views/discovery.js";
import { renderLogs } from "./views/logs.js";
import { renderPlan } from "./views/plan.js";
import { renderTokenUsage } from "./views/token-usage.js";
import { renderToolCalls } from "./views/tool-calls.js";

export class StateSync {
  readonly connection: ConnectionView = {
    pairedDeviceId: undefined,
    latestBridgeStatus: "unknown",
    serverUnavailable: false,
  };
  readonly discovery: DiscoveryView;
  private stateRenderVersion = 0;
  private lastRenderedState: string | undefined;
  private stateSyncFailures = 0;
  private readonly connectionCheck = new SingleFlight<BridgeCheckResult>();
  private readonly stateSyncFlight = new SingleFlight<void>();

  constructor(private readonly api: CompanionClientApi) {
    this.discovery = new DiscoveryView(api, this.connection, (state) => this.render(state));
  }

  readonly render = (next: CompanionState): void => {
    this.connection.serverUnavailable = false;
    this.stateSyncFailures = 0;
    const serializedState = JSON.stringify(next);
    if (serializedState === this.lastRenderedState) return;
    this.stateRenderVersion += 1;
    this.connection.pairedDeviceId = next.settings.pairedDeviceId;
    this.connection.latestBridgeStatus = next.bridgeStatus;

    if (elements.connectionStatusPill) {
      if (next.bridgeStatus === "connected") {
        elements.connectionStatusPill.textContent = "PHONE CONNECTED";
        elements.connectionStatusPill.className = "state-badge font-mono connected";
      } else if (next.bridgeStatus === "checking" || isCheckingSavedPhone(this.connection)) {
        elements.connectionStatusPill.textContent = "CHECKING PHONE";
        elements.connectionStatusPill.className = "state-badge font-mono checking";
      } else {
        elements.connectionStatusPill.textContent = "PHONE NOT CONNECTED";
        elements.connectionStatusPill.className = "state-badge font-mono stopped";
      }
    }

    const phoneState = next.phone;
    const isPhoneActive = phoneState?.active === true;
    setText(elements.phonePurpose, phoneState?.currentPurpose || (isPhoneActive ? "Active Phone Session" : "Waiting for phone session..."));
    setText(elements.phoneRequest, phoneState?.request || "No active request reported by the phone.");
    elements.activePhoneRequest.hidden = !isPhoneActive;

    elements.sessionBadge.textContent = isPhoneActive ? (phoneState?.state ?? "ACTIVE").toUpperCase() : "IDLE";
    elements.sessionBadge.className = `state-badge font-mono ${isPhoneActive ? "active" : ""}`;

    this.discovery.renderDiscoveredPhones();
    this.discovery.updateDiscoveryStatus();

    renderLogs(next.logs);
    renderPlan(next.plan);
    renderToolCalls(next.toolCalls);
    renderTokenUsage(next.tokenUsage, isPhoneActive);
    this.lastRenderedState = serializedState;
  };

  async refreshState(options: { verifyConnection?: boolean } = {}): Promise<void> {
    const version = this.stateRenderVersion;
    const state = await this.api.getState();
    if (version !== this.stateRenderVersion) return;
    this.render(state);
    // Verify once on every page load so a previous offline result cannot remain
    // visible forever after the phone comes back. Calls made after an explicit
    // action keep the existing behavior and only probe an unknown connection.
    const shouldVerify = state.bridgeStatus === "unknown" ||
      (options.verifyConnection === true && state.bridgeStatus !== "connected");
    if (shouldVerify && (state.settings.tokenConfigured || state.settings.pairingConfigured)) {
      const checkingState = { ...state, bridgeStatus: "checking" as const, lastError: undefined };
      if (state.bridgeStatus !== "connected") this.render(checkingState);
      const pendingRenderVersion = this.stateRenderVersion;
      void this.runConnectionCheck()
        .then(async (result) => {
          try {
            const checkedState = await this.api.getState();
            if (this.stateRenderVersion === pendingRenderVersion) this.render(checkedState);
          } catch {
            if (this.stateRenderVersion === pendingRenderVersion) {
              this.render({
                ...checkingState,
                bridgeStatus: result.ok ? "connected" : "offline",
                ...(result.ok ? {} : { lastError: result.message }),
              });
            }
          }
        })
        .catch((error: unknown) => {
          if (this.stateRenderVersion === pendingRenderVersion) {
            this.render({
              ...checkingState,
              bridgeStatus: "offline",
              lastError: errorMessage(error),
            });
          }
        });
    }
  }

  syncState(): Promise<void> {
    return this.stateSyncFlight.run(() => this.fetchAndRenderState());
  }

  private fetchAndRenderState(): Promise<void> {
    const version = this.stateRenderVersion;
    return this.api.getState().then((state) => {
      this.stateSyncFailures = 0;
      // An event or action can render a newer state while this request is on the
      // wire. The next sync can reconcile it without repainting stale data now.
      if (version === this.stateRenderVersion) this.render(state);
    }).catch((error: unknown) => {
      if (version === this.stateRenderVersion && ++this.stateSyncFailures >= 2 && !this.connection.serverUnavailable) {
        this.connection.serverUnavailable = true;
        this.stateRenderVersion += 1;
        this.lastRenderedState = undefined;
        if (elements.connectionStatusPill) {
          elements.connectionStatusPill.textContent = "COMPANION UNAVAILABLE";
          elements.connectionStatusPill.className = "state-badge font-mono stopped";
        }
        elements.discoverPhones.disabled = true;
        elements.discoveryStatus.textContent = "Reconnecting to the desktop companion...";
        elements.discoveredPhones.replaceChildren();
      }
      throw error;
    });
  }

  private runConnectionCheck(): Promise<BridgeCheckResult> {
    return this.connectionCheck.run(() => this.api.checkConnection());
  }
}
