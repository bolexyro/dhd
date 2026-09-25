import { existsSync, watch, type FSWatcher } from "node:fs";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

import type { SseHub } from "./sse.js";
import { defaultStaticLayout } from "./static.js";

export class DevReloadWatcher {
  private fileWatcher: FSWatcher | undefined;
  private reloadDebounceTimer: NodeJS.Timeout | undefined;

  constructor(private readonly sse: SseHub) {}

  start(): void {
    const srcWebDir = defaultStaticLayout.client.source;
    if (this.fileWatcher || !srcWebDir || !existsSync(srcWebDir)) return;

    try {
      this.fileWatcher = watch(srcWebDir, { recursive: true }, (_eventType, filename) => {
        if (!filename) return;
        if (filename.includes("tsconfig") || filename.endsWith(".tmp")) return;

        if (this.reloadDebounceTimer) clearTimeout(this.reloadDebounceTimer);
        this.reloadDebounceTimer = setTimeout(async () => {
          const isCss = filename.endsWith(".css");
          const isHtml = filename.endsWith(".html");

          // Sync static assets to dist if dist exists
          const distWebDir = defaultStaticLayout.client.dist;
          if (existsSync(distWebDir)) {
            try {
              const srcFile = resolve(srcWebDir, filename);
              if (existsSync(srcFile) && (isCss || isHtml || filename.endsWith(".png"))) {
                await writeFile(resolve(distWebDir, filename), await readFile(srcFile));
              }
            } catch {
              // best effort copy
            }
          }

          this.notifyClientsReload(isCss ? "css" : "full", filename);
        }, 80);
      });
    } catch (err) {
      console.error("Failed to start file watcher:", err);
    }
  }

  private notifyClientsReload(type: "css" | "full", file?: string): void {
    this.sse.broadcast(`event: reload\ndata: ${JSON.stringify({ type, file, timestamp: Date.now() })}\n\n`);
  }
}
