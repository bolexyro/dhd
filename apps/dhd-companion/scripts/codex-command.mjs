export function codexShellCommand(command, platform = process.platform) {
  if (
    platform === "win32" &&
    /\s|[&|<>^]/.test(command) &&
    !(command.startsWith('"') && command.endsWith('"'))
  ) {
    return `"${command.replaceAll('"', '\\"')}"`;
  }
  return command;
}
