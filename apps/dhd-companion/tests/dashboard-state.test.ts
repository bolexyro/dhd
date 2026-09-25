import { afterEach, describe, expect, it, vi } from "vitest";

import { SseHub } from "../src/dashboard/server/sse.js";
import { DashboardState } from "../src/dashboard/server/state-store.js";

afterEach(() => {
  vi.useRealTimers();
});

function recordingHub(): { hub: SseHub; payloads: string[] } {
  const hub = new SseHub();
  const payloads: string[] = [];
  vi.spyOn(hub, "broadcast").mockImplementation((payload: string) => {
    payloads.push(payload);
  });
  return { hub, payloads };
}

describe("dashboard state broadcasts", () => {
  it("coalesces a burst of updates into one state frame", async () => {
    vi.useFakeTimers();
    const { hub, payloads } = recordingHub();
    const state = new DashboardState(hub);

    for (let index = 0; index < 100; index += 1) {
      state.appendLog(`line ${index}`, { level: "info", source: "companion" });
    }
    state.bridgeStatus = "connected";
    state.publish();
    expect(payloads).toEqual([]);

    await vi.advanceTimersByTimeAsync(100);

    expect(payloads).toEqual([`data: ${JSON.stringify(state.snapshot())}\n\n`]);
    expect(state.snapshot().logs).toHaveLength(100);
  });

  it("sends later updates in a new frame", async () => {
    vi.useFakeTimers();
    const { hub, payloads } = recordingHub();
    const state = new DashboardState(hub);

    state.appendLog("first", { level: "info", source: "companion" });
    await vi.advanceTimersByTimeAsync(100);
    state.appendLog("second", { level: "info", source: "companion" });
    await vi.advanceTimersByTimeAsync(100);

    expect(payloads).toHaveLength(2);
    expect(JSON.parse(payloads[1].slice("data: ".length)).logs.map((entry: { message: string }) => entry.message))
      .toEqual(["first", "second"]);
  });
});
