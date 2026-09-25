import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import type http from "node:http";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const MODULE_DIRECTORY = fileURLToPath(new URL(".", import.meta.url));
const PROJECT_ROOT = resolve(MODULE_DIRECTORY, "../../../");
export const CLIENT_SOURCE_DIRECTORY = resolve(PROJECT_ROOT, "src/companion-web");
export const CLIENT_DIST_DIRECTORY = resolve(PROJECT_ROOT, "dist/companion-web");
const BROWSER_MODULES = new Set(["renderer", "api", "pricing", "tool-images"]);
const NO_CACHE_HEADERS = {
  "Cache-Control": "no-cache, no-store, must-revalidate",
  "Pragma": "no-cache",
  "Expires": "0"
};

function getContentType(path: string): string {
  if (path.endsWith(".html")) return "text/html; charset=utf-8";
  if (path.endsWith(".css")) return "text/css; charset=utf-8";
  if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
  if (path.endsWith(".json")) return "application/json; charset=utf-8";
  if (path.endsWith(".png")) return "image/png";
  if (path.endsWith(".svg")) return "image/svg+xml";
  return "text/plain; charset=utf-8";
}

export function browserModuleName(pathname: string): string | undefined {
  const name = /^\/([a-z-]+)\.(?:js|ts)$/.exec(pathname)?.[1];
  return name && BROWSER_MODULES.has(name) ? name : undefined;
}

function transpileTsFile(tsCode: string): string {
  return ts.transpileModule(tsCode, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      esModuleInterop: true,
      sourceMap: false
    }
  }).outputText;
}

export async function serveStaticFile(res: http.ServerResponse, fileName: string): Promise<void> {
  // If a JS module is requested, check if a corresponding TS source exists and transpile on-the-fly
  if (fileName.endsWith(".js")) {
    const tsFileName = fileName.replace(/\.js$/, ".ts");
    const srcTsPath = resolve(CLIENT_SOURCE_DIRECTORY, tsFileName);
    if (existsSync(srcTsPath)) {
      try {
        const tsCode = await readFile(srcTsPath, "utf8");
        const jsCode = transpileTsFile(tsCode);
        res.writeHead(200, {
          "Content-Type": "application/javascript; charset=utf-8",
          ...NO_CACHE_HEADERS
        });
        res.end(jsCode);
        return;
      } catch (err) {
        console.error(`Failed to transpile ${tsFileName}:`, err);
      }
    }
  }

  for (const filePath of [resolve(CLIENT_SOURCE_DIRECTORY, fileName), resolve(CLIENT_DIST_DIRECTORY, fileName)]) {
    if (existsSync(filePath)) {
      try {
        const content = await readFile(filePath);
        res.writeHead(200, {
          "Content-Type": getContentType(fileName),
          ...NO_CACHE_HEADERS
        });
        res.end(content);
        return;
      } catch {
        // try next
      }
    }
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("File not found");
}
