import { spawn, type ChildProcess } from "node:child_process";

export function killProcessTree(child: ChildProcess, signal: NodeJS.Signals = "SIGTERM"): void {
  if (process.platform === "win32" && child.pid !== undefined) {
    spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore", windowsHide: true })
      .once("error", () => child.kill(signal));
    return;
  }
  child.kill(signal);
}
