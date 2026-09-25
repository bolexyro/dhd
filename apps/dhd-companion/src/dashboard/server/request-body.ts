import type http from "node:http";
import { StringDecoder } from "node:string_decoder";

export async function readRequestBody(req: http.IncomingMessage): Promise<unknown> {
  return new Promise((resolveBody, rejectBody) => {
    const decoder = new StringDecoder("utf8");
    let body = "";
    req.on("data", (chunk: Buffer) => {
      body += decoder.write(chunk);
      if (body.length > 1_000_000) {
        req.destroy();
        rejectBody(new Error("Request body too large."));
      }
    });
    req.on("end", () => {
      body += decoder.end();
      try {
        resolveBody(body ? JSON.parse(body) : {});
      } catch {
        rejectBody(new Error("Invalid JSON body."));
      }
    });
    req.on("error", rejectBody);
  });
}
