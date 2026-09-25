import type { BridgeStatus } from "./api.js";

export interface ConnectionView {
  pairedDeviceId: string | undefined;
  latestBridgeStatus: BridgeStatus;
  serverUnavailable: boolean;
}

export function isCheckingSavedPhone(view: ConnectionView): boolean {
  return Boolean(view.pairedDeviceId) &&
    (view.latestBridgeStatus === "checking" || view.latestBridgeStatus === "unknown");
}
