import http from "node:http";

import type { DiscoveredPhoneSnapshot } from "../client/api.js";
import { errorMessage } from "../../shared/errors.js";
import type { CompanionDashboard } from "./dashboard.js";
import { discoveredPhoneSnapshot } from "./pairing-service.js";
import { readRequestBody } from "./request-body.js";
import { isTrustedDashboardRequest } from "./request-guard.js";
import { serveStaticFile, staticAssetFor } from "./static.js";

const TOOL_IMAGE_PATH = /^\/api\/tool-calls\/([^/]+)\/images\/(\d+)$/;

type JsonAction = (req: http.IncomingMessage) => unknown;

interface JsonRoute {
  run: JsonAction;
  failureStatus: number;
  failureBody?: (message: string) => unknown;
}

function writeJson(
  res: http.ServerResponse,
  status: number,
  body: unknown,
  headers: http.OutgoingHttpHeaders = {},
): void {
  res.writeHead(status, { "Content-Type": "application/json", ...headers });
  res.end(JSON.stringify(body));
}

function writeText(res: http.ServerResponse, status: number, text: string): void {
  res.writeHead(status, { "Content-Type": "text/plain" });
  res.end(text);
}

function jsonPostRoutes(dashboard: CompanionDashboard): Record<string, JsonRoute> {
  const { state, pairing, supervisor, monitor } = dashboard;
  return {
    "/api/discover": {
      failureStatus: 500,
      run: async () => {
        const phones = await pairing.discoverPhonesOnNetwork();
        const response: { phones: DiscoveredPhoneSnapshot[] } = {
          phones: phones.map(discoveredPhoneSnapshot)
        };
        return response;
      },
    },
    "/api/pair-device": {
      failureStatus: 400,
      run: async (req) => pairing.pairWithDiscoveredDevice(await readRequestBody(req)),
    },
    "/api/check": {
      failureStatus: 500,
      failureBody: (message) => ({ ok: false, message }),
      run: () => {
        // The dashboard is the worker's owner. A manual health check should
        // also recover a worker that exited while the dashboard stayed open.
        supervisor.ensureRunning();
        return monitor.checkConnection();
      },
    },
    "/api/clear-logs": { failureStatus: 500, run: () => state.clearLogs() },
    "/api/clear-tool-calls": { failureStatus: 500, run: () => state.clearToolCalls() },
  };
}

async function runJsonRoute(route: JsonRoute, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
  try {
    writeJson(res, 200, await route.run(req));
  } catch (err) {
    const message = errorMessage(err);
    writeJson(res, route.failureStatus, route.failureBody ? route.failureBody(message) : { message });
  }
}

function openEventStream(dashboard: CompanionDashboard, req: http.IncomingMessage, res: http.ServerResponse): void {
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    "Connection": "keep-alive"
  });
  res.write(`data: ${JSON.stringify(dashboard.state.snapshot())}\n\n`);
  dashboard.sse.add(res);

  req.on("close", () => {
    dashboard.sse.remove(res);
  });
}

function serveToolImage(
  dashboard: CompanionDashboard,
  res: http.ServerResponse,
  match: RegExpMatchArray,
  url: URL,
): void {
  let callId: string;
  try {
    callId = decodeURIComponent(match[1]);
  } catch {
    writeText(res, 404, "Image not found");
    return;
  }
  const imageIndex = Number(match[2]);
  const source = url.searchParams.get("source") === "debug" ? "debug" : "response";
  const image = Number.isSafeInteger(imageIndex)
    ? dashboard.state.toolCalls.image(callId, imageIndex, source)
    : undefined;
  if (!image) {
    writeText(res, 404, "Image not found");
    return;
  }
  res.writeHead(200, {
    "Content-Type": image.mimeType,
    "Content-Length": image.bytes.length,
    "Cache-Control": "no-store"
  });
  res.end(image.bytes);
}

function requestUrl(req: http.IncomingMessage): URL | undefined {
  try {
    return new URL(req.url ?? "/", `http://${req.headers.host || "localhost"}`);
  } catch {
    return undefined;
  }
}

async function handleRequest(
  dashboard: CompanionDashboard,
  routes: Record<string, JsonRoute>,
  req: http.IncomingMessage,
  res: http.ServerResponse,
): Promise<void> {
  const url = requestUrl(req);
  if (!url) {
    writeText(res, 400, "Bad Request");
    return;
  }
  const pathname = url.pathname;

  if (!isTrustedDashboardRequest(req)) {
    writeText(res, 403, "Forbidden");
    return;
  }

  if (req.method === "OPTIONS") {
    res.writeHead(204, { "Allow": "GET, POST, OPTIONS" });
    res.end();
    return;
  }

  if (pathname === "/api/events" && req.method === "GET") {
    openEventStream(dashboard, req, res);
    return;
  }

  const toolImageMatch = pathname.match(TOOL_IMAGE_PATH);
  if (toolImageMatch && req.method === "GET") {
    serveToolImage(dashboard, res, toolImageMatch, url);
    return;
  }

  if (pathname === "/api/state" && req.method === "GET") {
    writeJson(res, 200, dashboard.state.snapshot(), { "Cache-Control": "no-store" });
    return;
  }

  const jsonRoute = req.method === "POST" && Object.hasOwn(routes, pathname) ? routes[pathname] : undefined;
  if (jsonRoute) {
    await runJsonRoute(jsonRoute, req, res);
    return;
  }

  const staticAsset = staticAssetFor(pathname);
  if (staticAsset) return serveStaticFile(res, staticAsset);
  writeText(res, 404, "Not Found");
}

export function createCompanionWebServer(dashboard: CompanionDashboard): http.Server {
  const routes = jsonPostRoutes(dashboard);
  return http.createServer((req, res) => {
    handleRequest(dashboard, routes, req, res).catch((error: unknown) => {
      console.error(`Companion dashboard request failed: ${errorMessage(error)}`);
      if (!res.headersSent) writeText(res, 500, "Internal Server Error");
      else res.end();
    });
  });
}
