import { errorMessage } from "../../../shared/errors.js";
import { SingleFlight } from "../../../shared/single-flight.js";
import type { CompanionClientApi, CompanionState, DiscoveredPhoneSnapshot } from "../api.js";
import { elements } from "../dom.js";
import { isCheckingSavedPhone, type ConnectionView } from "../session.js";
import { showToast } from "./toast.js";

const SPINNER_SVG = `<svg class="btn-svg spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10" stroke-opacity="0.25" stroke="currentColor" fill="none"/><path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" stroke-linecap="round"/></svg>`;

export class DiscoveryView {
  private discoveredPhoneList: DiscoveredPhoneSnapshot[] = [];
  private pairingDeviceId: string | undefined;
  private repairDeviceId: string | undefined;
  private readonly discovery = new SingleFlight<void>();
  private discoveryFinished = false;
  private readonly discoverButtonIdleHtml = elements.discoverPhones.innerHTML;

  constructor(
    private readonly api: CompanionClientApi,
    private readonly connection: ConnectionView,
    private readonly render: (state: CompanionState) => void,
  ) {}

  private updateDiscoverButton(): void {
    const searching = Boolean(this.discovery.inFlight) || isCheckingSavedPhone(this.connection);
    elements.discoverPhones.disabled = searching || this.connection.serverUnavailable;
    elements.discoverPhones.innerHTML = searching
      ? `${SPINNER_SVG}<span>Searching...</span>`
      : this.discoverButtonIdleHtml;
  }

  renderDiscoveredPhones(): void {
    elements.discoveredPhones.replaceChildren();
    if (this.discoveredPhoneList.length === 0) {
      if (!this.discoveryFinished) return;
      const empty = document.createElement("div");
      empty.className = "discovery-empty";
      const title = document.createElement("p");
      title.className = "discovery-empty-title";
      const checkingSavedPhone = isCheckingSavedPhone(this.connection);
      title.textContent = this.connection.latestBridgeStatus === "connected"
        ? "Phone connected"
        : checkingSavedPhone ? "Checking saved phone" : "No phones found";
      empty.append(title);
      if (this.connection.latestBridgeStatus === "connected" || checkingSavedPhone) {
        const message = document.createElement("p");
        message.className = "discovery-connected-copy";
        message.textContent = this.connection.latestBridgeStatus === "connected"
          ? "DHD is responding. Nearby search only lists phones available to pair."
          : "Confirming the saved connection. This may take a moment.";
        empty.append(message);
        elements.discoveredPhones.append(empty);
        return;
      }

      const steps = document.createElement("ul");
      const openApp = document.createElement("li");
      openApp.textContent = "Make sure DHD is open on your phone.";
      const sameNetwork = document.createElement("li");
      sameNetwork.textContent = "Connect both devices to the same Wi-Fi, or connect this computer to your phone's hotspot.";
      steps.append(openApp, sameNetwork);
      empty.append(steps);
      elements.discoveredPhones.append(empty);
      return;
    }

    for (const phone of this.discoveredPhoneList) {
      const card = document.createElement("div");
      card.className = "discovered-phone-card";

      const details = document.createElement("div");
      details.className = "discovered-phone-details";
      const name = document.createElement("div");
      name.className = "discovered-phone-name";
      name.textContent = phone.deviceName;
      const model = document.createElement("div");
      model.className = "discovered-phone-model font-mono";
      model.textContent = phone.model || "DHD phone on local network";
      details.append(name);
      if (!phone.model || !phone.deviceName.toLowerCase().includes(phone.model.toLowerCase())) {
        details.append(model);
      }

      const isSavedPhone = phone.deviceId === this.connection.pairedDeviceId;
      const isConnectedPhone = isSavedPhone && this.connection.latestBridgeStatus === "connected";
      const isCheckingPhone = isSavedPhone && isCheckingSavedPhone(this.connection);
      const pairButton = document.createElement("button");
      pairButton.type = "button";
      pairButton.className = `action-btn ${isConnectedPhone ? "secondary" : "primary"} small discovered-phone-pair`;
      pairButton.disabled = this.pairingDeviceId !== undefined || isConnectedPhone || isCheckingPhone;
      pairButton.textContent = isConnectedPhone
        ? "Connected"
        : isCheckingPhone
          ? "Checking..."
        : this.pairingDeviceId === phone.deviceId
          ? this.repairDeviceId === phone.deviceId || !isSavedPhone ? "Approve on phone" : "Reconnecting..."
          : this.repairDeviceId === phone.deviceId ? "Pair again" : isSavedPhone ? "Reconnect" : "Pair";
      if (!isConnectedPhone) {
        pairButton.addEventListener("click", () => {
          void this.pairDiscoveredPhone(phone);
        });
      }

      card.append(details, pairButton);
      elements.discoveredPhones.append(card);
    }
  }

  updateDiscoveryStatus(): void {
    this.updateDiscoverButton();
    if (this.connection.latestBridgeStatus === "connected") {
      elements.discoveryStatus.textContent = "Phone connected.";
      return;
    }
    if (!this.discoveryFinished || this.discovery.inFlight || this.pairingDeviceId) return;
    elements.discoveryStatus.textContent = this.discoveredPhoneList.length === 0
      ? isCheckingSavedPhone(this.connection) ? "Checking saved phone..." : "No phones answered."
      : `${this.discoveredPhoneList.length} phone${this.discoveredPhoneList.length === 1 ? "" : "s"} found.`;
  }

  async discoverPhonesOnNetwork(): Promise<void> {
    if (this.discovery.inFlight) return this.discovery.inFlight;
    const operation = this.discovery.run(async () => {
      elements.discoveryStatus.textContent = "Searching the local network...";
      try {
        this.discoveredPhoneList = await this.api.discoverPhones();
        this.discoveryFinished = true;
        this.renderDiscoveredPhones();
      } catch (error) {
        elements.discoveryStatus.textContent = "Discovery failed.";
        throw error;
      }
    });
    this.updateDiscoverButton();
    try {
      await operation;
    } finally {
      this.updateDiscoverButton();
    }
    this.updateDiscoveryStatus();
  }

  private async pairDiscoveredPhone(phone: DiscoveredPhoneSnapshot): Promise<void> {
    if (this.pairingDeviceId) return;
    if (phone.deviceId === this.connection.pairedDeviceId && this.connection.latestBridgeStatus === "connected") return;
    const reconnecting = phone.deviceId === this.connection.pairedDeviceId && this.repairDeviceId !== phone.deviceId;
    const replacePairing = this.repairDeviceId === phone.deviceId;
    this.pairingDeviceId = phone.deviceId;
    elements.discoveryStatus.textContent = reconnecting
      ? "Reconnecting with your saved pairing..."
      : "Approve the connection request on your phone.";
    this.renderDiscoveredPhones();
    try {
      const responseState = await this.api.pairWithDiscoveredPhone({
        deviceId: phone.deviceId,
        ...(replacePairing ? { replacePairing: true } : {}),
      });
      const currentState = await this.api.getState().catch(() => responseState);
      this.render(currentState);
      this.repairDeviceId = undefined;
      this.renderDiscoveredPhones();
      if (currentState.bridgeStatus === "connected") {
        elements.discoveryStatus.textContent = "Phone connected.";
        showToast(reconnecting ? `Reconnected to ${phone.deviceName}.` : `Paired with ${phone.deviceName}.`, "success");
      }
    } catch (error) {
      const currentState = await this.api.getState().catch(() => undefined);
      if (currentState?.settings.pairedDeviceId === phone.deviceId && currentState.bridgeStatus === "connected") {
        this.repairDeviceId = undefined;
        this.render(currentState);
        return;
      }
      if (reconnecting) this.repairDeviceId = phone.deviceId;
      showToast(errorMessage(error), "error");
    } finally {
      this.pairingDeviceId = undefined;
      this.renderDiscoveredPhones();
      this.updateDiscoveryStatus();
    }
  }
}
