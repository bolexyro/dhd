import { EventEmitter } from "node:events";
import type http from "node:http";

import { describe, expect, it } from "vitest";

import { readRequestBody } from "../src/dashboard/server/request-body.js";

function requestWith(...chunks: Buffer[]): http.IncomingMessage {
  const request = new EventEmitter() as EventEmitter & { destroy: () => void };
  request.destroy = () => undefined;
  queueMicrotask(() => {
    for (const chunk of chunks) request.emit("data", chunk);
    request.emit("end");
  });
  return request as unknown as http.IncomingMessage;
}

describe("dashboard request bodies", () => {
  it("decodes a multibyte character split across chunks", async () => {
    const body = Buffer.from(JSON.stringify({ deviceId: "téléphone" }), "utf8");
    const split = body.indexOf(Buffer.from("é", "utf8")) + 1;

    await expect(readRequestBody(requestWith(body.subarray(0, split), body.subarray(split)))).resolves.toEqual({
      deviceId: "téléphone",
    });
  });

  it("treats an empty body as an empty object and rejects invalid JSON", async () => {
    await expect(readRequestBody(requestWith())).resolves.toEqual({});
    await expect(readRequestBody(requestWith(Buffer.from("{nope")))).rejects.toThrow("Invalid JSON body.");
  });
});
