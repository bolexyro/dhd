import type { ChildProcessWithoutNullStreams, SpawnOptionsWithoutStdio } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

import {
  codexBinSetting,
  isCodeModeHostDisabled,
  windowsLocalAppDataDirectory,
} from "../config/env.js";

export type AppServerSpawner = (
  command: string,
  args: readonly string[],
  options: SpawnOptionsWithoutStdio,
) => ChildProcessWithoutNullStreams;

const MINIMAL_CODEX_CONFIG_OVERRIDES = [
  "mcp_servers={}",
  "features.apps=false",
  "features.browser_use=false",
  "features.computer_use=false",
  "features.goals=false",
  "features.hooks=false",
  "features.image_generation=false",
  "features.in_app_browser=false",
  "features.memories=false",
  "features.multi_agent=false",
  "features.plugins=false",
  "features.remote_plugin=false",
  "features.shell_snapshot=false",
  "features.shell_tool=false",
  "features.skill_mcp_dependency_install=false",
  "features.skill_search=false",
  "features.tool_suggest=false",
  "features.unified_exec=false",
  "features.view_image=false",
  "features.workspace_dependencies=false",
];

export function resolveCodexBin(): string {
  const configured = codexBinSetting();
  if (configured) return configured;
  if (process.platform === "win32") {
    const binDirectory = join(windowsLocalAppDataDirectory(), "OpenAI", "Codex", "bin");
    try {
      const installed = readdirSync(binDirectory, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => join(binDirectory, entry.name, "codex.exe"))
        .flatMap((path) => {
          try {
            return [{ path, modified: statSync(path).mtimeMs }];
          } catch {
            return [];
          }
        })
        .sort((left, right) => right.modified - left.modified);
      if (installed[0]) return installed[0].path;
    } catch {
      // Standalone CLI installs are still resolved through PATH below.
    }
  }
  // The unversioned desktop-app binary can lag behind the active CLI.
  return "codex";
}

/**
 * Codex merges table-valued `-c` overrides with the selected home config.
 * Explicitly disable each MCP server configured in that same home as well as
 * passing `mcp_servers={}` so a DHD child cannot start unrelated integrations
 * during a phone turn. Only section names are read; credentials and command
 * values never enter logs.
 */
export function disabledConfiguredMcpOverrides(codexHome: string): string[] {
  const configPath = join(codexHome, "config.toml");
  let config: string;
  try {
    config = readFileSync(configPath, "utf8");
  } catch {
    return [];
  }
  const names = new Set<string>();
  for (const line of config.split(/\r?\n/)) {
    const match = line.match(/^\s*\[mcp_servers\.([A-Za-z0-9_-]+)(?:[.\]])/);
    if (match?.[1]) names.add(match[1]);
  }
  return [...names].sort().map((name) => `mcp_servers.${name}.enabled=false`);
}

export function quoteWindowsCommand(command: string): string {
  if (
    /\s|[&|<>^]/.test(command) &&
    !(command.startsWith('"') && command.endsWith('"'))
  ) {
    return `"${command.replaceAll('"', '\\"')}"`;
  }
  return command;
}

export interface AppServerProcessOptions {
  codexHome: string;
  runtimeCwd: string;
}

export function spawnAppServerProcess(
  spawnAppServer: AppServerSpawner,
  options: AppServerProcessOptions,
): ChildProcessWithoutNullStreams {
  mkdirSync(options.codexHome, { recursive: true });
  mkdirSync(options.runtimeCwd, { recursive: true });
  const command = resolveCodexBin();
  const args = ["app-server", "--listen", "stdio://"];
  for (const override of [
    ...MINIMAL_CODEX_CONFIG_OVERRIDES,
    ...disabledConfiguredMcpOverrides(options.codexHome),
  ]) {
    args.push("-c", override);
  }
  // In the current Codex App Server builds, the model's dynamic-tool router
  // reaches these phone tools through the bundled Code Mode host. Keep that
  // host enabled by default; disabling it makes an otherwise healthy turn
  // fail closed with `code-mode host is disabled`. An explicit `false` is
  // still useful for diagnostics or environments that provide their own
  // tool-routing policy.
  if (isCodeModeHostDisabled()) {
    args.push("--disable", "code_mode_host");
  } else {
    args.push("--enable", "code_mode_host");
  }
  const windowsCommand =
    process.platform === "win32"
      ? `${quoteWindowsCommand(command)} ${args.map(quoteWindowsCommand).join(" ")}`
      : command;
  return spawnAppServer(
    windowsCommand,
    process.platform === "win32" ? [] : args,
    {
      stdio: ["pipe", "pipe", "pipe"],
      cwd: options.runtimeCwd,
      // On Windows Codex may be exposed as a .ps1/.cmd shim rather than a
      // native executable. Let cmd.exe resolve that user-installed command.
      shell: process.platform === "win32",
      windowsHide: true,
      env: {
        ...process.env,
        CODEX_HOME: options.codexHome,
      },
    },
  );
}
