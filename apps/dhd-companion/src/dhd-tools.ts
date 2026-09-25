import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { errorMessage } from "./shared/errors.js";
import { isMainModule } from "./shared/is-main-module.js";
import { createDhdMcpServer } from "./tools/mcp-server.js";

export * from "./tools/contract.js";
export * from "./tools/registry.js";
export { invokeDhdTool } from "./tools/invoke.js";
export { createDhdMcpServer } from "./tools/mcp-server.js";
export { normalizeScreenshot, type NormalizedScreenshot } from "./tools/result/screenshot.js";
export { toMcpResult } from "./tools/result/to-tool-result.js";
export type {
  DhdToolInvocationOptions,
  PhoneAssistantDebugImage,
  PhoneAssistantToolResult,
} from "./tools/result/types.js";

if (isMainModule("dhd-tools")) {
  const server = createDhdMcpServer();
  void server.connect(new StdioServerTransport()).catch((error: unknown) => {
    console.error(`[phone-assistant-mcp] startup failed: ${errorMessage(error)}`);
    process.exitCode = 1;
  });
}
