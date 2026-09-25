import dgram from "node:dgram";
import { hostname } from "node:os";

import { afterEach, describe, expect, it } from "vitest";

import { discoverPhones, requestPairingApproval, type DiscoveredPhone } from "../src/phone/pairing.js";

interface ReceivedDatagram {
  text: string;
  at: number;
  reply: (message: Record<string, unknown>) => void;
}

const phones: dgram.Socket[] = [];

afterEach(() => {
  for (const socket of phones.splice(0)) socket.close();
});

async function fakePhone(onDatagram: (datagram: ReceivedDatagram) => void = () => undefined) {
  const socket = dgram.createSocket("udp4");
  phones.push(socket);
  await new Promise<void>((resolve) => socket.bind(0, "127.0.0.1", resolve));
  const received: ReceivedDatagram[] = [];
  socket.on("message", (message, remote) => {
    const datagram = {
      text: message.toString("utf8"),
      at: Date.now(),
      reply: (reply: Record<string, unknown>) =>
        socket.send(Buffer.from(JSON.stringify(reply)), remote.port, remote.address),
    };
    received.push(datagram);
    onDatagram(datagram);
  });
  return { port: (socket.address() as { port: number }).port, received };
}

const selectedPhone: DiscoveredPhone = {
  deviceId: "phone-1",
  deviceName: "Pixel",
  host: "127.0.0.1",
  addresses: ["127.0.0.1", "fe80::1", "not-an-ip"],
  port: 8765,
  pairingNonce: "nonce-1",
};

describe("pairing protocol v1", () => {
  it("broadcasts one discovery request three times, 350 ms apart", async () => {
    const phone = await fakePhone();

    await expect(
      discoverPhones({ discoveryPort: phone.port, broadcastAddresses: ["127.0.0.1", "::1"], timeoutMs: 1_000 }),
    ).resolves.toEqual([]);

    expect(phone.received).toHaveLength(3);
    const [first] = phone.received;
    const requestId = (JSON.parse(first.text) as { requestId: string }).requestId;
    expect(first.text).toBe(JSON.stringify({ type: "dhd_discover_request", version: 1, requestId }));
    expect(phone.received.map(({ text }) => text)).toEqual([first.text, first.text, first.text]);
    const gaps = phone.received.slice(1).map((datagram, index) => datagram.at - phone.received[index].at);
    for (const gap of gaps) expect(gap).toBeGreaterThanOrEqual(300);
  });

  it("merges valid offers per device and ignores malformed ones", async () => {
    const phone = await fakePhone((datagram) => {
      const { requestId } = JSON.parse(datagram.text) as { requestId: string };
      const offer = {
        type: "dhd_discover_offer",
        version: 1,
        requestId,
        deviceId: " phone-1 ",
        deviceName: " Pixel ",
        pairingNonce: " nonce-1 ",
        port: 8765,
      };
      datagram.reply({ ...offer, model: " Pixel 9 ", addresses: ["192.168.1.2", "fe80::1", 7] });
      datagram.reply({ ...offer, pairingNonce: "nonce-2", model: "  ", addresses: ["192.168.1.3"] });
      datagram.reply({ ...offer, deviceId: "phone-2", requestId: "other-request" });
      datagram.reply({ ...offer, deviceId: "phone-3", version: 2 });
      datagram.reply({ ...offer, deviceId: "phone-4", port: 70_000 });
      datagram.reply({ ...offer, deviceId: "phone-5", deviceName: "  " });
    });

    const discovered = await discoverPhones({
      discoveryPort: phone.port,
      broadcastAddresses: ["127.0.0.1"],
      timeoutMs: 200,
    });

    expect(discovered).toEqual([
      {
        deviceId: "phone-1",
        deviceName: "Pixel",
        model: "Pixel 9",
        host: "127.0.0.1",
        addresses: ["127.0.0.1", "192.168.1.2", "192.168.1.3"],
        port: 8765,
        pairingNonce: "nonce-2",
      },
    ]);
  });

  it("sends the approval request with a trimmed, bounded desktop name", async () => {
    const phone = await fakePhone((datagram) => {
      const request = JSON.parse(datagram.text) as { requestId: string };
      datagram.reply({
        type: "dhd_pair_approval_offer",
        version: 1,
        requestId: request.requestId,
        deviceId: " phone-1 ",
        addresses: ["192.168.1.9"],
        port: 9001,
        token: " approved-token ",
      });
    });

    const pairing = await requestPairingApproval(selectedPhone, {
      discoveryPort: phone.port,
      timeoutMs: 1_000,
      desktopName: `  ${"d".repeat(100)}  `,
    });

    const requestId = (JSON.parse(phone.received[0].text) as { requestId: string }).requestId;
    expect(phone.received[0].text).toBe(
      JSON.stringify({
        type: "dhd_pair_approval_request",
        version: 1,
        requestId,
        deviceId: "phone-1",
        pairingNonce: "nonce-1",
        desktopName: "d".repeat(80),
      }),
    );
    expect(pairing).toEqual({
      host: "127.0.0.1",
      port: 9001,
      token: "approved-token",
      deviceId: "phone-1",
      addresses: ["127.0.0.1", "192.168.1.9"],
    });
  });

  it("names the desktop after the host by default", async () => {
    const phone = await fakePhone();

    await expect(
      requestPairingApproval(selectedPhone, { discoveryPort: phone.port, timeoutMs: 100 }),
    ).rejects.toThrow("Timed out waiting for approval on the selected phone.");

    const request = JSON.parse(phone.received[0].text) as { desktopName: string };
    expect(request.desktopName).toBe((hostname() || "DHD Companion").slice(0, 80));
  });

  it.each([
    [{ message: "  Declined on the phone  " }, "Declined on the phone"],
    [{ message: "  " }, "The phone declined the desktop companion pairing request."],
    [{}, "The phone declined the desktop companion pairing request."],
  ])("surfaces a phone rejection %j", async (fields, message) => {
    const phone = await fakePhone((datagram) => {
      const request = JSON.parse(datagram.text) as { requestId: string };
      datagram.reply({ type: "dhd_pair_approval_rejected", version: 1, requestId: request.requestId, deviceId: "phone-1", ...fields });
    });

    await expect(
      requestPairingApproval(selectedPhone, { discoveryPort: phone.port, timeoutMs: 1_000 }),
    ).rejects.toThrow(message);
  });

  it("ignores responses for another device or request and keeps waiting", async () => {
    const phone = await fakePhone((datagram) => {
      const request = JSON.parse(datagram.text) as { requestId: string };
      const offer = { type: "dhd_pair_approval_offer", version: 1, port: 8765, token: "t", addresses: [] };
      datagram.reply({ ...offer, requestId: request.requestId, deviceId: "phone-2" });
      datagram.reply({ ...offer, requestId: "other", deviceId: "phone-1" });
      datagram.reply({ ...offer, requestId: request.requestId, deviceId: "phone-1", token: " " });
    });

    await expect(
      requestPairingApproval(selectedPhone, { discoveryPort: phone.port, timeoutMs: 150 }),
    ).rejects.toThrow("Timed out waiting for approval on the selected phone.");
    expect(phone.received.length).toBeGreaterThanOrEqual(1);
  });

  it("rejects a phone without a usable IPv4 address", async () => {
    await expect(
      requestPairingApproval({ ...selectedPhone, host: "fe80::1", addresses: ["phone.local"] }),
    ).rejects.toThrow("The selected phone did not advertise a usable local address.");
  });
});
