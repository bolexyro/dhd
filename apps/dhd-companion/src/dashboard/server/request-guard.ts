import type http from "node:http";

const LOOPBACK_HOSTNAMES = new Set(["127.0.0.1", "localhost", "[::1]"]);
const HOST_HEADER = /^(\[[^\]]+\]|[^:]+)(?::(\d+))?$/;

function isLoopbackHost(host: string, port: number): boolean {
  const match = HOST_HEADER.exec(host);
  if (!match) return false;
  const [, hostname, portText] = match;
  const hostPort = portText === undefined ? 80 : Number(portText);
  return LOOPBACK_HOSTNAMES.has(hostname) && hostPort === port;
}

export function isTrustedDashboardRequest(req: http.IncomingMessage): boolean {
  const host = req.headers.host?.trim().toLowerCase();
  if (!host || !isLoopbackHost(host, req.socket.localPort ?? 0)) return false;
  const origin = req.headers.origin?.trim().toLowerCase();
  return origin === undefined || origin === `http://${host}`;
}
