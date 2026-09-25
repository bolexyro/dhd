import { describe, expect, it } from "vitest";

import { codexShellCommand } from "../scripts/codex-command.mjs";
import { quoteWindowsCommand } from "../src/codex/process.js";

describe("setup script Codex command", () => {
  it.each([
    ["codex", "codex"],
    ["C:\\Program Files\\OpenAI\\Codex\\bin\\codex.exe", '"C:\\Program Files\\OpenAI\\Codex\\bin\\codex.exe"'],
    ['"C:\\Program Files\\codex.cmd"', '"C:\\Program Files\\codex.cmd"'],
  ])("quotes %s for the Windows shell", (command, expected) => {
    expect(codexShellCommand(command, "win32")).toBe(expected);
    expect(codexShellCommand(command, "win32")).toBe(quoteWindowsCommand(command));
  });

  it("leaves the command untouched where no shell is used", () => {
    expect(codexShellCommand("/Applications/Codex Tools/codex", "darwin")).toBe("/Applications/Codex Tools/codex");
  });
});
