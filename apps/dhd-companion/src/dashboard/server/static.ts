import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import type http from "node:http";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const MODULE_DIRECTORY = fileURLToPath(new URL(".", import.meta.url));
const PROJECT_ROOT = resolve(MODULE_DIRECTORY, "../../../");
export const CLIENT_SOURCE_DIRECTORY = resolve(PROJECT_ROOT, "src/dashboard/client");
export const CLIENT_DIST_DIRECTORY = resolve(PROJECT_ROOT, "dist/dashboard/client");
const CLIENT_ROOT: StaticRoot = { source: CLIENT_SOURCE_DIRECTORY, dist: CLIENT_DIST_DIRECTORY };
const SHARED_ROOT: StaticRoot = {
  source: resolve(PROJECT_ROOT, "src/shared"),
  dist: resolve(PROJECT_ROOT, "dist/shared"),
};
const STATIC_FILES: Record<string, string> = {
  "/": "index.html",
  "/index.html": "index.html",
  "/styles.css": "styles.css",
  "/favicon.png": "favicon.png",
};
const CLIENT_ENTRY_PATHS = new Set(["/renderer.js", "/renderer.ts"]);
const CLIENT_MODULE_PATH = /^\/((?:views\/)?[a-z][a-z-]*)\.(?:js|ts)$/;
const SHARED_MODULE_PATH = /^\/shared\/([a-z][a-z-]*)\.(?:js|ts)$/;
const BROWSER_SHARED_MODULES = new Set(["default-model", "errors", "single-flight"]);
const NO_CACHE_HEADERS = {
  "Cache-Control": "no-cache, no-store, must-revalidate",
  "Pragma": "no-cache",
  "Expires": "0"
};

export interface StaticRoot {
  source: string;
  dist: string;
}

export interface StaticAsset {
  root: StaticRoot;
  fileName: string;
}

function hasModule(root: StaticRoot, name: string): boolean {
  return existsSync(resolve(root.source, `${name}.ts`)) || existsSync(resolve(root.dist, `${name}.js`));
}

export function staticAssetFor(pathname: string): StaticAsset | undefined {
  if (Object.hasOwn(STATIC_FILES, pathname)) return { root: CLIENT_ROOT, fileName: STATIC_FILES[pathname] };
  if (CLIENT_ENTRY_PATHS.has(pathname)) return { root: CLIENT_ROOT, fileName: "main.js" };
  const clientModule = CLIENT_MODULE_PATH.exec(pathname)?.[1];
  if (clientModule && clientModule !== "main" && hasModule(CLIENT_ROOT, clientModule)) {
    return { root: CLIENT_ROOT, fileName: `${clientModule}.js` };
  }
  const sharedModule = SHARED_MODULE_PATH.exec(pathname)?.[1];
  if (sharedModule && BROWSER_SHARED_MODULES.has(sharedModule)) {
    return { root: SHARED_ROOT, fileName: `${sharedModule}.js` };
  }
  return undefined;
}

function getContentType(path: string): string {
  if (path.endsWith(".html")) return "text/html; charset=utf-8";
  if (path.endsWith(".css")) return "text/css; charset=utf-8";
  if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
  if (path.endsWith(".json")) return "application/json; charset=utf-8";
  if (path.endsWith(".png")) return "image/png";
  if (path.endsWith(".svg")) return "image/svg+xml";
  return "text/plain; charset=utf-8";
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

export async function serveStaticFile(res: http.ServerResponse, { root, fileName }: StaticAsset): Promise<void> {
  // If a JS module is requested, check if a corresponding TS source exists and transpile on-the-fly
  if (fileName.endsWith(".js")) {
    const tsFileName = fileName.replace(/\.js$/, ".ts");
    const srcTsPath = resolve(root.source, tsFileName);
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

  for (const filePath of [resolve(root.source, fileName), resolve(root.dist, fileName)]) {
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
