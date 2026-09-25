import { describe, expect, it } from "vitest";

import { SingleFlight } from "../src/shared/single-flight.js";

describe("SingleFlight", () => {
  it("shares one in-flight operation and starts a new one after it settles", async () => {
    const flight = new SingleFlight<number>();
    let starts = 0;
    let finish!: (value: number) => void;
    const operation = () => {
      starts += 1;
      return new Promise<number>((resolve) => { finish = resolve; });
    };

    const first = flight.run(operation);
    const second = flight.run(operation);
    expect(second).toBe(first);
    expect(flight.inFlight).toBe(first);
    finish(7);

    await expect(first).resolves.toBe(7);
    expect(flight.inFlight).toBeUndefined();
    void flight.run(operation);
    expect(starts).toBe(2);
    finish(8);
  });

  it("clears a rejected operation without swallowing the rejection", async () => {
    const flight = new SingleFlight<void>();

    await expect(flight.run(() => Promise.reject(new Error("offline")))).rejects.toThrow("offline");
    expect(flight.inFlight).toBeUndefined();
  });
});
