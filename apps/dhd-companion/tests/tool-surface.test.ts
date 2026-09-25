import { afterEach, describe, expect, it } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";

import { buildDhdDynamicTools } from "../src/assistant-companion.js";
import { GUARD_REGIONS_FEATURE_FLAG, createDhdMcpServer } from "../src/dhd-tools.js";

const originalGuardRegionsFlag = process.env[GUARD_REGIONS_FEATURE_FLAG];

afterEach(() => {
  if (originalGuardRegionsFlag === undefined) delete process.env[GUARD_REGIONS_FEATURE_FLAG];
  else process.env[GUARD_REGIONS_FEATURE_FLAG] = originalGuardRegionsFlag;
});

async function listMcpTools(): Promise<unknown> {
  const server = createDhdMcpServer();
  const client = new Client({ name: "tool-surface-test", version: "0.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  await client.connect(clientTransport);
  try {
    return await client.listTools();
  } finally {
    await client.close();
    await server.close();
  }
}

describe("DHD tool surface", () => {
  it("keeps the App Server dynamic tools byte-identical without guard regions", async () => {
    const tools = buildDhdDynamicTools({ enableGuardRegions: false });

    await expect(JSON.stringify(tools, null, 2)).toMatchFileSnapshot(
      "./__snapshots__/dynamic-tools.guard-regions-off.json",
    );
  });

  it("keeps the App Server dynamic tools byte-identical with guard regions", async () => {
    const tools = buildDhdDynamicTools({ enableGuardRegions: true });

    await expect(JSON.stringify(tools, null, 2)).toMatchFileSnapshot(
      "./__snapshots__/dynamic-tools.guard-regions-on.json",
    );
  });

  it("reads the guard-region flag when no option is passed", () => {
    process.env[GUARD_REGIONS_FEATURE_FLAG] = "on";
    expect(buildDhdDynamicTools()).toEqual(buildDhdDynamicTools({ enableGuardRegions: true }));

    delete process.env[GUARD_REGIONS_FEATURE_FLAG];
    expect(buildDhdDynamicTools()).toEqual(buildDhdDynamicTools({ enableGuardRegions: false }));
  });

  it("keeps the MCP tools/list output byte-identical without guard regions", async () => {
    delete process.env[GUARD_REGIONS_FEATURE_FLAG];

    await expect(JSON.stringify(await listMcpTools(), null, 2)).toMatchFileSnapshot(
      "./__snapshots__/mcp-tools-list.guard-regions-off.json",
    );
  });

  it("keeps the MCP tools/list output byte-identical with guard regions", async () => {
    process.env[GUARD_REGIONS_FEATURE_FLAG] = "true";

    await expect(JSON.stringify(await listMcpTools(), null, 2)).toMatchFileSnapshot(
      "./__snapshots__/mcp-tools-list.guard-regions-on.json",
    );
  });
});
