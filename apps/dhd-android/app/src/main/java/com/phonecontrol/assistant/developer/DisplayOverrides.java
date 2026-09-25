package com.phonecontrol.assistant.developer;

import java.io.IOException;
import java.util.Locale;

final class DisplayOverrides {
    private final int width;
    private final int height;
    private final int densityDpi;
    private final int appDensityDpi;
    private final int appDisplayWidth;
    private final int appDisplayHeight;
    private boolean displayDensityOverridden;
    private boolean displaySizeOverridden;

    DisplayOverrides(int width, int height, int densityDpi, int appDensityDpi,
                     int appDisplayWidth, int appDisplayHeight) {
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.appDensityDpi = appDensityDpi;
        this.appDisplayWidth = appDisplayWidth;
        this.appDisplayHeight = appDisplayHeight;
    }

    /**
     * Keep the encoded buffer fixed while allowing an app profile to use
     * a larger logical canvas. Android then lays out normal dp-sized
     * controls instead of stretching them to fill the smaller buffer;
     * capture and input are mapped back at the DHD boundary.
     */
    void apply(int displayId) throws Exception {
        if (appDisplayWidth != width || appDisplayHeight != height) {
            ShellProcess.Result result = DisplayShell.run(new String[]{
                    "/system/bin/wm", "size",
                    appDisplayWidth + "x" + appDisplayHeight,
                    "-d", Integer.toString(displayId),
            }, DhdNativeDisplayService.COMMAND_TIMEOUT_MS);
            if (result.exitCode != 0 || result.stderr.toLowerCase(Locale.ROOT).contains("error")) {
                throw new IOException("Could not set app display size " + appDisplayWidth + "x" +
                        appDisplayHeight + " on display " + displayId + ": " + DisplayShell.diagnostic(result));
            }
            displaySizeOverridden = true;
        }

        if (appDensityDpi == densityDpi) return;
        ShellProcess.Result result = DisplayShell.run(new String[]{
                "/system/bin/wm", "density", Integer.toString(appDensityDpi),
                "-d", Integer.toString(displayId),
        }, DhdNativeDisplayService.COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0 || result.stderr.toLowerCase(Locale.ROOT).contains("error")) {
            throw new IOException("Could not set app density " + appDensityDpi +
                    " on display " + displayId + ": " + DisplayShell.diagnostic(result));
        }
        displayDensityOverridden = true;
    }

    void reset(int displayId) {
        if (displayId <= 0) {
            displaySizeOverridden = false;
            displayDensityOverridden = false;
            return;
        }
        if (displaySizeOverridden) {
            try {
                DisplayShell.run(new String[]{
                        "/system/bin/wm", "size", "reset", "-d", Integer.toString(displayId),
                }, DhdNativeDisplayService.COMMAND_TIMEOUT_MS);
            } catch (Throwable ignored) {
                // The display may already be gone while unwinding a failed
                // session; its per-display override dies with the display.
            } finally {
                displaySizeOverridden = false;
            }
        }
        if (displayDensityOverridden) {
            try {
                DisplayShell.run(new String[]{
                        "/system/bin/wm", "density", "reset", "-d", Integer.toString(displayId),
                }, DhdNativeDisplayService.COMMAND_TIMEOUT_MS);
            } catch (Throwable ignored) {
                // The display may already be gone while unwinding a failed
                // session; its per-display override dies with the display.
            } finally {
                displayDensityOverridden = false;
            }
        }
    }
}
