import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { isGuardRegionsEnabled } from "../config/env.js";
import { invokeDhdTool } from "./invoke.js";
import { DHD_TOOL_DEFINITIONS, dhdToolDescription } from "./registry.js";

export function createDhdMcpServer(
  serverName = "dhd",
  version = "0.1.0"
): McpServer {
  const enableGuardRegions = isGuardRegionsEnabled();
  const server = new McpServer({ name: serverName, version });
  for (const definition of DHD_TOOL_DEFINITIONS) {
    server.registerTool(
      definition.name,
      {
        description: dhdToolDescription(definition.name, enableGuardRegions),
        inputSchema: definition.inputSchema(enableGuardRegions).shape,
      },
      async (input: unknown) => invokeDhdTool(definition.name, input),
    );
  }
  return server;
}
