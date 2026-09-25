package com.phonecontrol.assistant.developer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class DisplayShell {
    private DisplayShell() {}

    static String runText(String[] command) throws Exception {
        ShellProcess.Result result = run(command, DhdNativeDisplayService.COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0) throw new IOException(result.stderr);
        return new String(result.stdout, StandardCharsets.UTF_8);
    }

    static ShellProcess.Result run(String[] command, long timeoutMs) throws Exception {
        return ShellProcess.run(
                Arrays.asList(command),
                timeoutMs,
                256 * 1024,
                "dhd-display-capture-out",
                "dhd-display-capture-err");
    }

    static String diagnostic(ShellProcess.Result result) {
        String stderr = result.stderr == null ? "" : result.stderr.trim();
        String stdout = new String(result.stdout, StandardCharsets.UTF_8).trim();
        if (!stderr.isEmpty() && !stdout.isEmpty()) return stderr + " | " + stdout;
        if (!stderr.isEmpty()) return stderr;
        if (!stdout.isEmpty()) return stdout;
        return "exit " + result.exitCode;
    }
}
