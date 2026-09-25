import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

vi.mock("typescript", () => {
  throw new Error("The production dashboard must not load the TypeScript compiler.");
});

const { companionDashboard } = await import("../src/dashboard/server/dashboard.js");
const { createCompanionWebServer } = await import("../src/dashboard/server/routes.js");
const { staticLayout } = await import("../src/dashboard/server/static.js");

const projectRoot = mkdtempSync(join(tmpdir(), "dhd-dashboard-dist-"));
let server: import("node:http").Server;
let baseUrl: string;

beforeAll(async () => {
  const client = join(projectRoot, "dist", "dashboard", "client");
  mkdirSync(join(client, "views"), { recursive: true });
  mkdirSync(join(projectRoot, "dist", "shared"), { recursive: true });
  mkdirSync(join(projectRoot, "src", "dashboard", "client"), { recursive: true });
  writeFileSync(join(client, "index.html"), "<!doctype html><title>built</title>");
  writeFileSync(join(client, "main.js"), "export const built = true;");
  writeFileSync(join(client, "views", "logs.js"), "export const view = true;");
  writeFileSync(join(projectRoot, "dist", "shared", "errors.js"), "export const shared = true;");
  writeFileSync(join(projectRoot, "src", "dashboard", "client", "main.ts"), "export const source: boolean = true;");

  server = createCompanionWebServer(companionDashboard, staticLayout(projectRoot, false));
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe("production dashboard assets", () => {
  it.each([
    ["/", "<!doctype html><title>built</title>"],
    ["/renderer.js", "export const built = true;"],
    ["/views/logs.js", "export const view = true;"],
    ["/shared/errors.js", "export const shared = true;"],
  ])("serves %s from the build output", async (path, body) => {
    const response = await fetch(`${baseUrl}${path}`);

    expect(response.status).toBe(200);
    expect(await response.text()).toBe(body);
  });

  it("does not serve source modules missing from the build output", async () => {
    const response = await fetch(`${baseUrl}/state-sync.js`);

    expect(response.status).toBe(404);
  });
});
