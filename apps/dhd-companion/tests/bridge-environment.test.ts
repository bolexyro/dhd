import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe("bridge environment", () => {
  it("starts the dashboard with the default port when the bridge port is invalid", async () => {
    vi.stubEnv("PHONE_ASSISTANT_BRIDGE_PORT", "not-a-port");
    vi.resetModules();

    const { companionDashboard } = await import("../src/dashboard/server/dashboard.js");

    expect(companionDashboard.state.connection.port).toBe(8765);
  });

  it("reads the worker bridge target lazily and tolerates an invalid port", async () => {
    vi.stubEnv("PHONE_ASSISTANT_BRIDGE_PORT", "99999");
    vi.stubEnv("PHONE_ASSISTANT_BRIDGE_HOST", " 10.0.0.7 ");
    vi.stubEnv("PHONE_ASSISTANT_BRIDGE_TOKEN", " paired ");
    vi.resetModules();

    const { environmentBridgeTarget } = await import("../src/phone/bridge-client.js");
    expect(environmentBridgeTarget()).toEqual({ host: "10.0.0.7", port: 8765, token: "paired" });

    vi.stubEnv("PHONE_ASSISTANT_BRIDGE_PORT", "9100");
    expect(environmentBridgeTarget().port).toBe(9100);
  });
});
