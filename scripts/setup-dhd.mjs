import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { codexShellCommand } from "../apps/dhd-companion/scripts/codex-command.mjs";

const configuredHome = process.env.PHONE_ASSISTANT_CODEX_HOME?.trim();
const configuredRuntime = process.env.PHONE_ASSISTANT_CODEX_CWD?.trim();
const dhdRoot = join(homedir(), ".dhd");
const codexHome = resolve(configuredHome || join(dhdRoot, "codex-home"));
const runtimeCwd = resolve(
  configuredRuntime || join(dhdRoot, "codex-runtime"),
);
const skipLogin = process.argv.includes("--skip-login");
const deviceAuth = process.argv.includes("--device-auth");
const codexCommand = process.env.PHONE_ASSISTANT_CODEX_BIN?.trim() || "codex";
const shellCodexCommand = codexShellCommand(codexCommand);
const childEnvironment = {
  ...process.env,
  CODEX_HOME: codexHome,
};
const childOptions = {
  cwd: runtimeCwd,
  env: childEnvironment,
  shell: process.platform === "win32",
  stdio: "inherit",
  windowsHide: false,
};
const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const instructionsTemplate = join(
  repositoryRoot,
  "apps",
  "dhd-companion",
  "codex-home",
  "AGENTS.md",
);
const installedInstructions = join(codexHome, "AGENTS.md");

mkdirSync(codexHome, { recursive: true });
mkdirSync(runtimeCwd, { recursive: true });

if (!existsSync(installedInstructions)) {
  copyFileSync(instructionsTemplate, installedInstructions);
  console.log(`Installed DHD Codex instructions: ${installedInstructions}`);
} else {
  console.log(`Keeping existing DHD Codex instructions: ${installedInstructions}`);
}

console.log(`DHD Codex home: ${codexHome}`);
console.log(`DHD runtime directory: ${runtimeCwd}`);

if (skipLogin) {
  console.log("Skipping Codex login (--skip-login).");
  process.exit(0);
}

const status = spawnSync(shellCodexCommand, ["login", "status"], childOptions);
if (status.error) {
  console.error(
    `Could not run '${codexCommand}'. Install the Codex CLI and make sure it is on PATH.`,
  );
  console.error(status.error.message);
  process.exit(1);
}

if (status.status === 0) {
  console.log("DHD is already authenticated; no browser login is required.");
  process.exit(0);
}

if (deviceAuth) {
  console.log(
    "No DHD login was found. Codex will print a device-code link for ChatGPT sign-in.",
  );
  console.log("Open that link in your browser and enter the one-time code.");
} else {
  console.log(
    "No DHD login was found. Codex will open the default browser for ChatGPT sign-in.",
  );
  console.log(
    "Complete the sign-in there, then return here so the command can finish.",
  );
}

const loginArguments = ["login"];
if (deviceAuth) loginArguments.push("--device-auth");
const login = spawnSync(shellCodexCommand, loginArguments, childOptions);
if (login.error) {
  console.error(login.error.message);
  process.exit(1);
}
if (login.status !== 0) {
  process.exit(login.status ?? 1);
}

console.log("DHD Codex login completed.");
