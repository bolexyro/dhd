package com.phonecontrol.assistant.developer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TaskLauncher {
    private TaskLauncher() {}

    static void launch(String packageName, int displayId) throws Exception {
        String component = resolveLaunchComponent(packageName);
        String[] launchCommand = new String[]{
                "/system/bin/am", "start", "-W", "--display", Integer.toString(displayId),
                "-f", "0x18080000", "-n", component,
        };
        ShellProcess.Result result = DisplayShell.run(launchCommand, DhdNativeDisplayService.COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0 || result.stderr.toLowerCase(Locale.ROOT).contains("error") ||
                new String(result.stdout, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("error:")) {
            throw new IOException("Could not launch " + packageName + " on display " + displayId +
                    ": " + DisplayShell.diagnostic(result));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DhdNativeDisplayService.LAUNCH_VERIFY_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (focusedPackageOnDisplay(displayId, packageName)) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new IOException("Android did not verify " + packageName + " on display " + displayId + ".");
    }

    private static String resolveLaunchComponent(String packageName) throws Exception {
        ShellProcess.Result result = DisplayShell.run(new String[]{
                "/system/bin/cmd", "package", "resolve-activity", "--brief",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                packageName,
        }, DhdNativeDisplayService.COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0) {
            throw new IOException("Could not resolve a launcher for " + packageName +
                    ": " + DisplayShell.diagnostic(result));
        }
        String output = new String(result.stdout, StandardCharsets.UTF_8);
        Pattern componentPattern = Pattern.compile(
                "(?m)^\\s*(" + Pattern.quote(packageName) + "/[^\\s]+)\\s*$");
        Matcher match = componentPattern.matcher(output);
        if (!match.find()) {
            throw new IOException("No launcher activity was resolved for " + packageName +
                    ": " + output.trim());
        }
        return match.group(1);
    }

    private static boolean focusedPackageOnDisplay(int expectedDisplayId, String expectedPackage) {
        try {
            String output = DisplayShell.runText(new String[]{"/system/bin/dumpsys", "activity", "activities"});
            if (DaemonActivityDumpParser.hasFocusedPackage(output, expectedDisplayId, expectedPackage)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Verification failure is handled by the caller as unsafe.
        }
        return false;
    }
}
