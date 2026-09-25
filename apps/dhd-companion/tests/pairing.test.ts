import { describe, expect, it } from "vitest";
import dgram from "node:dgram";

import {
  discoverPhones,
  requestPairingApproval,
} from "../src/phone/pairing.js";

describe("phone pairing discovery", () => {
  it("lists multiple phones using metadata without exposing bridge tokens", async () => {
    const server = dgram.createSocket("udp4");
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.bind(0, "127.0.0.1", resolve);
    });
    const address = server.address();
    if (typeof address === "string") throw new Error("The test discovery socket did not expose a port.");
    server.on("message", (message, remote) => {
      const request = JSON.parse(message.toString()) as { type: string; requestId: string };
      if (request.type !== "dhd_discover_request") return;
      for (const phone of [
        { deviceId: "phone-a", deviceName: "Alice's S23", model: "SM-S911B", pairingNonce: "nonce-a" },
        { deviceId: "phone-b", deviceName: "Bob's S23", model: "SM-S911B", pairingNonce: "nonce-b" },
      ]) {
        const response = Buffer.from(JSON.stringify({
          type: "dhd_discover_offer",
          version: 1,
          requestId: request.requestId,
          ...phone,
          addresses: ["192.168.1.42"],
          port: 8765
        }));
        server.send(response, remote.port, remote.address);
      }
    });

    try {
      await expect(discoverPhones({
        discoveryPort: address.port,
        broadcastAddresses: ["127.0.0.1"],
        timeoutMs: 150
      })).resolves.toEqual([
        expect.objectContaining({
          deviceId: "phone-a",
          deviceName: "Alice's S23",
          host: "127.0.0.1",
          pairingNonce: "nonce-a"
        }),
        expect.objectContaining({
          deviceId: "phone-b",
          deviceName: "Bob's S23",
          host: "127.0.0.1",
          pairingNonce: "nonce-b"
        })
      ]);
    } finally {
      server.close();
    }
  });

  it("waits for phone approval before accepting the bridge token", async () => {
    const server = dgram.createSocket("udp4");
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.bind(0, "127.0.0.1", resolve);
    });
    const address = server.address();
    if (typeof address === "string") throw new Error("The test approval socket did not expose a port.");
    server.on("message", (message, remote) => {
      const request = JSON.parse(message.toString()) as {
        type: string;
        requestId: string;
        deviceId: string;
      };
      if (request.type !== "dhd_pair_approval_request") return;
      const pending = Buffer.from(JSON.stringify({
        type: "dhd_pair_approval_pending",
        version: 1,
        requestId: request.requestId,
        deviceId: request.deviceId
      }));
      server.send(pending, remote.port, remote.address);
      const response = Buffer.from(JSON.stringify({
        type: "dhd_pair_approval_offer",
        version: 1,
        requestId: request.requestId,
        deviceId: request.deviceId,
        addresses: ["127.0.0.1"],
        port: 8765,
        token: "released-after-approval"
      }));
      setTimeout(() => server.send(response, remote.port, remote.address), 20);
    });

    try {
      await expect(requestPairingApproval({
        deviceId: "phone-approved",
        deviceName: "Approved S23",
        host: "127.0.0.1",
        addresses: ["127.0.0.1"],
        port: 8765,
        pairingNonce: "fresh-nonce"
      }, {
        discoveryPort: address.port,
        timeoutMs: 500,
        desktopName: "Test computer"
      })).resolves.toMatchObject({
        host: "127.0.0.1",
        port: 8765,
        token: "released-after-approval",
        deviceId: "phone-approved"
      });
    } finally {
      server.close();
    }
  });
});
