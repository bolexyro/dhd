package com.phonecontrol.assistant.developer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.phonecontrol.assistant.developer.DhdNativeDisplayService.CommandResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class DisplayCommandHandler {
    private static final Pattern SESSION_KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}");
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");

    private final Map<String, DisplaySession> sessions = new HashMap<>();
    private final Object lock = new Object();

    void close() {
        DisplaySession[] active;
        synchronized (lock) {
            active = sessions.values().toArray(new DisplaySession[0]);
            sessions.clear();
        }
        for (DisplaySession session : active) {
            session.close();
        }
    }

    CommandResult create(List<String> command) throws Exception {
        if (command.size() != 9 && command.size() != 10 && command.size() != 12) {
            return CommandResult.failure(
                    "dhd-display create requires sessionKey, packageName, width, height, densityDpi, frameRate, bitRate, appDensityDpi, appDisplayWidth, appDisplayHeight.");
        }
        String sessionKey = command.get(2);
        String packageName = command.get(3);
        if (!SESSION_KEY_PATTERN.matcher(sessionKey).matches()) {
            return CommandResult.failure("DHD display session key is invalid.");
        }
        if (!PACKAGE_PATTERN.matcher(packageName).matches()) {
            return CommandResult.failure("DHD display package name is invalid.");
        }
        int width = boundedInt(command.get(4), 320, 2_160, "width");
        int height = boundedInt(command.get(5), 320, 3_840, "height");
        int densityDpi = boundedInt(command.get(6), 120, 640, "densityDpi");
        int frameRate = boundedInt(command.get(7), 1, 60, "frameRate");
        int bitRate = boundedInt(command.get(8), 128_000, 20_000_000, "bitRate");
        // Accept the old payload from an already-running app process, but make
        // new sessions explicit about the app-visible density.
        int appDensityDpi = command.size() == 10
                ? boundedInt(command.get(9), 120, 640, "appDensityDpi")
                : densityDpi;
        int appDisplayWidth = command.size() == 12
                ? boundedInt(command.get(10), 320, 4_096, "appDisplayWidth")
                : width;
        int appDisplayHeight = command.size() == 12
                ? boundedInt(command.get(11), 320, 4_096, "appDisplayHeight")
                : height;

        synchronized (lock) {
            if (sessions.containsKey(sessionKey)) {
                return CommandResult.failure("DHD display session is already active.");
            }
            if (sessions.size() >= DhdNativeDisplayService.MAX_SESSIONS) {
                return CommandResult.failure("DHD display session limit reached.");
            }
        }

        DisplaySession session = new DisplaySession(
                sessionKey, packageName, width, height, densityDpi, appDensityDpi,
                appDisplayWidth, appDisplayHeight, frameRate, bitRate);
        try {
            session.start();
            synchronized (lock) {
                if (sessions.containsKey(sessionKey)) {
                    throw new IOException("DHD display session was created concurrently.");
                }
                sessions.put(sessionKey, session);
            }
            return CommandResult.success(session.createdJson());
        } catch (Throwable error) {
            session.close();
            return CommandResult.failure("DHD display creation failed: " + DhdNativeDisplayService.safeMessage(error));
        }
    }

    CommandResult attach(List<String> command) {
        if (command.size() != 3) return CommandResult.failure("dhd-display attach requires sessionKey.");
        DisplaySession session = find(command.get(2));
        if (session == null) return CommandResult.failure("DHD display session is not active.");
        session.allowStreamClient();
        return CommandResult.success("{\"type\":\"dhd_display_attached\"}".getBytes(StandardCharsets.UTF_8));
    }

    CommandResult detach(List<String> command) {
        if (command.size() != 3) return CommandResult.failure("dhd-display detach requires sessionKey.");
        DisplaySession session = find(command.get(2));
        if (session == null) return CommandResult.success(new byte[0]);
        session.detachStreamClient();
        return CommandResult.success(new byte[0]);
    }

    CommandResult capture(List<String> command, boolean binaryOutput) throws Exception {
        if (command.size() != 3) return CommandResult.failure("dhd-display capture requires sessionKey.");
        if (!binaryOutput) return CommandResult.failure("DHD display capture requires binary output mode.");
        DisplaySession session = find(command.get(2));
        if (session == null) return CommandResult.failure("DHD display session is not active.");
        byte[] png = captureDisplay(
                session.displayId(),
                "DHD " + session.sessionKey,
                session.width,
                session.height);
        return CommandResult.success(png);
    }

    /** Return only the metadata required to reconcile a surviving daemon session. */
    CommandResult listSessions(List<String> command) {
        if (command.size() != 2) return CommandResult.failure("dhd-display list takes no arguments.");
        DisplaySession[] active;
        synchronized (lock) {
            active = sessions.values().toArray(new DisplaySession[0]);
        }
        StringBuilder json = new StringBuilder("{\"type\":\"dhd_display_sessions\",\"sessions\":[");
        for (int index = 0; index < active.length; index++) {
            if (index > 0) json.append(',');
            json.append(new String(active[index].createdJson(), StandardCharsets.UTF_8));
        }
        json.append("]}");
        return CommandResult.success(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    CommandResult closeSession(List<String> command) {
        if (command.size() != 3) return CommandResult.failure("dhd-display close requires sessionKey.");
        DisplaySession session;
        synchronized (lock) {
            session = sessions.remove(command.get(2));
        }
        if (session != null) session.close();
        return CommandResult.success(new byte[0]);
    }

    CommandResult closeAll() {
        DisplaySession[] active;
        synchronized (lock) {
            active = sessions.values().toArray(new DisplaySession[0]);
            sessions.clear();
        }
        for (DisplaySession session : active) session.close();
        return CommandResult.success(new byte[0]);
    }

    private DisplaySession find(String sessionKey) {
        synchronized (lock) {
            return sessions.get(sessionKey);
        }
    }

    private static int boundedInt(String value, int min, int max, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new IllegalArgumentException(name + " is out of range.");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " is not an integer.");
        }
    }

    private static byte[] captureDisplay(
            int logicalDisplayId,
            String displayName,
            int targetWidth,
            int targetHeight
    ) throws Exception {
        if (logicalDisplayId <= 0) throw new IOException("The default display is not a task display.");
        String displayInfo = DisplayShell.runText(new String[]{"/system/bin/cmd", "display", "get-displays"});
        String uniqueId = SurfaceFlingerIds.findLogicalUniqueId(displayInfo, logicalDisplayId);
        String sf = DisplayShell.runText(new String[]{"/system/bin/dumpsys", "SurfaceFlinger", "--display-id"});
        String sfId = SurfaceFlingerIds.findSurfaceFlingerId(sf, logicalDisplayId, uniqueId);
        if (sfId == null) {
            String displays = DisplayShell.runText(new String[]{"/system/bin/dumpsys", "SurfaceFlinger", "--displays"});
            sfId = SurfaceFlingerIds.findSurfaceFlingerId(displays, logicalDisplayId, uniqueId);
            if (sfId == null) {
                sfId = SurfaceFlingerIds.findUniqueSurfaceFlingerVirtualDisplayId(displays, displayName);
            }
        }
        if (sfId == null) {
            throw new IOException("SurfaceFlinger did not expose a capture id bound to logical display " + logicalDisplayId + ".");
        }
        ShellProcess.Result result = DisplayShell.run(new String[]{"/system/bin/screencap", "-d", sfId, "-p"}, DhdNativeDisplayService.COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0 || result.stdout.length == 0) {
            throw new IOException("DHD display capture failed: " + result.stderr);
        }
        return normalizeCapture(result.stdout, targetWidth, targetHeight);
    }

    /**
     * Keep the observation contract fixed even when Android lays the app out
     * on a larger logical canvas for a full-size app profile.
     */
    private static byte[] normalizeCapture(byte[] png, int targetWidth, int targetHeight)
            throws IOException {
        Bitmap source = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (source == null) throw new IOException("DHD display capture was not a valid PNG.");
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return png;
        }

        Bitmap scaled = null;
        try {
            scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("DHD display capture could not be normalized.");
            }
            return output.toByteArray();
        } finally {
            if (scaled != null && scaled != source) scaled.recycle();
            source.recycle();
        }
    }
}
