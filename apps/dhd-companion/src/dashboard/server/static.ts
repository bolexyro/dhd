import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import type http from "node:http";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const PROJECT_ROOT = resolve(fileURLToPath(new URL(".", import.meta.url)), "../../../");
export const RUNNING_FROM_SOURCE = import.meta.url.endsWith(".ts");
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
  source?: string;
  dist: string;
}

export interface StaticLayout {
  client: StaticRoot;
  shared: StaticRoot;
}

export interface StaticAsset {
  root: StaticRoot;
  fileName: string;
}

export function staticLayout(projectRoot: string, fromSource: boolean): StaticLayout {
  const root = (sourcePath: string, distPath: string): StaticRoot => ({
    ...(fromSource ? { source: resolve(projectRoot, sourcePath) } : {}),
    dist: resolve(projectRoot, distPath),
  });
  return {
    client: root("src/dashboard/client", "dist/dashboard/client"),
    shared: root("src/shared", "dist/shared"),
  };
}

export const defaultStaticLayout = staticLayout(PROJECT_ROOT, RUNNING_FROM_SOURCE);

function hasModule(root: StaticRoot, name: string): boolean {
  return (root.source !== undefined && existsSync(resolve(root.source, `${name}.ts`))) ||
    existsSync(resolve(root.dist, `${name}.js`));
}

export function staticAssetFor(pathname: string, layout: StaticLayout): StaticAsset | undefined {
  if (Object.hasOwn(STATIC_FILES, pathname)) return { root: layout.client, fileName: STATIC_FILES[pathname] };
  if (CLIENT_ENTRY_PATHS.has(pathname)) return { root: layout.client, fileName: "main.js" };
  const clientModule = CLIENT_MODULE_PATH.exec(pathname)?.[1];
  if (clientModule && clientModule !== "main" && hasModule(layout.client, clientModule)) {
    return { root: layout.client, fileName: `${clientModule}.js` };
  }
  const sharedModule = SHARED_MODULE_PATH.exec(pathname)?.[1];
  if (sharedModule && BROWSER_SHARED_MODULES.has(sharedModule)) {
    return { root: layout.shared, fileName: `${sharedModule}.js` };
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

async function transpileTsFile(tsCode: string): Promise<string> {
  const { default: ts } = await import("typescript");
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
  if (root.source !== undefined && fileName.endsWith(".js")) {
    const tsFileName = fileName.replace(/\.js$/, ".ts");
    const srcTsPath = resolve(root.source, tsFileName);
    if (existsSync(srcTsPath)) {
      try {
        const tsCode = await readFile(srcTsPath, "utf8");
        const jsCode = await transpileTsFile(tsCode);
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

  const candidates = [root.source, root.dist].filter((directory): directory is string => directory !== undefined);
  for (const filePath of candidates.map((directory) => resolve(directory, fileName))) {
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
