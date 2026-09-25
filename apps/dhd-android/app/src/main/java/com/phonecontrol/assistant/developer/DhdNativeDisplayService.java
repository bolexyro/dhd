package com.phonecontrol.assistant.developer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Native display backend used by the DHD shell-UID maintenance daemon.
 *
 * The daemon process owns both the virtual display callback token and the
 * encoder input surface. Only encoded AVC packets cross the authenticated
 * loopback stream; an Android Surface is never serialized through TCP.
 */
final class DhdNativeDisplayService implements Closeable {
    static final String COMMAND = "dhd-display";
    static final String CREATED_TYPE = "dhd_display_created";
    static final String CODEC_MIME = "video/avc";
    static final int STREAM_MAGIC = 0x44485631; // DHV1
    static final int STREAM_VERSION = 1;
    static final int MAX_SESSIONS = 2;
    static final int MAX_STREAM_PACKET_BYTES = 4 * 1024 * 1024;
    /** Keep only a short burst, but never resume from a partial AVC GOP. */
    static final int MAX_STREAM_QUEUE_PACKETS = 8;
    static final long COMMAND_TIMEOUT_MS = 15_000L;
    static final long LAUNCH_VERIFY_TIMEOUT_MS = 2_500L;

    private static final Pattern SESSION_KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}");
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");

    private final Map<String, DisplaySession> sessions = new HashMap<>();
    private final Object lock = new Object();

    /** Result shape kept separate from the maintenance daemon's shell result. */
    static final class CommandResult {
        final int exitCode;
        final boolean timedOut;
        final byte[] stdout;
        final String stderr;

        CommandResult(int exitCode, boolean timedOut, byte[] stdout, String stderr) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.stdout = stdout == null ? new byte[0] : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        static CommandResult success(byte[] stdout) {
            return new CommandResult(0, false, stdout, "");
        }

        static CommandResult failure(String stderr) {
            return new CommandResult(DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE, false,
                    new byte[0], stderr);
        }
    }

    CommandResult execute(List<String> command, boolean binaryOutput) {
        if (command == null || command.size() < 2 || !COMMAND.equals(command.get(0))) {
            return CommandResult.failure("DHD display command is invalid.");
        }
        String operation = command.get(1);
        try {
            switch (operation) {
                case "create":
                    return create(command);
                case "attach":
                    return attach(command);
                case "detach":
                    return detach(command);
                case "capture":
                    return capture(command, binaryOutput);
                case "list":
                    return listSessions(command);
                case "close":
                    return closeSession(command);
                case "close-all":
                    return closeAll();
                default:
                    return CommandResult.failure("DHD display operation is unsupported: " + operation);
            }
        } catch (Throwable error) {
            return CommandResult.failure("DHD display operation failed: " + safeMessage(error));
        }
    }

    @Override
    public void close() {
        DisplaySession[] active;
        synchronized (lock) {
            active = sessions.values().toArray(new DisplaySession[0]);
            sessions.clear();
        }
        for (DisplaySession session : active) {
            session.close();
        }
    }

    private CommandResult create(List<String> command) throws Exception {
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
            if (sessions.size() >= MAX_SESSIONS) {
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
            return CommandResult.failure("DHD display creation failed: " + safeMessage(error));
        }
    }

    private CommandResult attach(List<String> command) {
        if (command.size() != 3) return CommandResult.failure("dhd-display attach requires sessionKey.");
        DisplaySession session = find(command.get(2));
        if (session == null) return CommandResult.failure("DHD display session is not active.");
        session.allowStreamClient();
        return CommandResult.success("{\"type\":\"dhd_display_attached\"}".getBytes(StandardCharsets.UTF_8));
    }

    private CommandResult detach(List<String> command) {
        if (command.size() != 3) return CommandResult.failure("dhd-display detach requires sessionKey.");
        DisplaySession session = find(command.get(2));
        if (session == null) return CommandResult.success(new byte[0]);
        session.detachStreamClient();
        return CommandResult.success(new byte[0]);
    }

    private CommandResult capture(List<String> command, boolean binaryOutput) throws Exception {
        if (command.size() != 3) return CommandResult.failure("dhd-display capture requires sessionKey.");
        if (!binaryOutput) return CommandResult.failure("DHD display capture requires binary output mode.");
        DisplaySession session = find(command.get(2));
        if (session == null) return CommandResult.failure("DHD display session is not active.");
        byte[] png = captureDisplay(
                session.displayId,
                "DHD " + session.sessionKey,
                session.width,
                session.height);
        return CommandResult.success(png);
    }

    /** Return only the metadata required to reconcile a surviving daemon session. */
    private CommandResult listSessions(List<String> command) {
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

    private CommandResult closeSession(List<String> command) {
        if (command.size() != 3) return CommandResult.failure("dhd-display close requires sessionKey.");
        DisplaySession session;
        synchronized (lock) {
            session = sessions.remove(command.get(2));
        }
        if (session != null) session.close();
        return CommandResult.success(new byte[0]);
    }

    private CommandResult closeAll() {
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

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null &&
                (current.getMessage() == null || current.getMessage().isEmpty())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isEmpty() ? current.getClass().getSimpleName() : message;
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
        ShellProcess.Result result = DisplayShell.run(new String[]{"/system/bin/screencap", "-d", sfId, "-p"}, COMMAND_TIMEOUT_MS);
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

    private static final class DisplaySession implements Closeable {
        private final String sessionKey;
        private final String packageName;
        private final int width;
        private final int height;
        private final int densityDpi;
        private final int appDensityDpi;
        private final int appDisplayWidth;
        private final int appDisplayHeight;
        private final int frameRate;
        private final int bitRate;
        private final ExecutorService executor = Executors.newFixedThreadPool(2);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AvcEncoderPipeline encoder = new AvcEncoderPipeline(closed);
        private final StreamServer stream;
        private final DisplayOverrides overrides;
        private HiddenDisplayManager displayBridge;
        private int displayId = -1;

        DisplaySession(String sessionKey, String packageName, int width, int height,
                       int densityDpi, int appDensityDpi,
                       int appDisplayWidth, int appDisplayHeight,
                       int frameRate, int bitRate) {
            this.sessionKey = sessionKey;
            this.packageName = packageName;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.appDensityDpi = appDensityDpi;
            this.appDisplayWidth = appDisplayWidth;
            this.appDisplayHeight = appDisplayHeight;
            this.frameRate = frameRate;
            this.bitRate = bitRate;
            this.stream = new StreamServer(width, height, encoder, closed);
            this.overrides = new DisplayOverrides(
                    width, height, densityDpi, appDensityDpi, appDisplayWidth, appDisplayHeight);
        }

        void start() throws Exception {
            Surface encoderSurface = encoder.start(width, height, bitRate, frameRate);

            displayBridge = new HiddenDisplayManager();
            displayId = displayBridge.createVirtualDisplay(
                    "DHD " + sessionKey, width, height, densityDpi, encoderSurface);
            if (displayId <= 0) throw new IOException("Android created an invalid task display id.");
            overrides.apply(displayId);

            stream.bind();
            executor.submit(() -> encoder.drain(stream));
            executor.submit(stream::serve);
            TaskLauncher.launch(packageName, displayId);
        }

        byte[] createdJson() {
            String json = "{\"type\":\"" + CREATED_TYPE + "\"" +
                    ",\"sessionKey\":\"" + escape(sessionKey) + "\"" +
                    ",\"packageName\":\"" + escape(packageName) + "\"" +
                    ",\"displayId\":" + displayId +
                    ",\"width\":" + width +
                    ",\"height\":" + height +
                    ",\"densityDpi\":" + densityDpi +
                    ",\"appDensityDpi\":" + appDensityDpi +
                    ",\"appDisplayWidth\":" + appDisplayWidth +
                    ",\"appDisplayHeight\":" + appDisplayHeight +
                    ",\"frameRate\":" + frameRate +
                    ",\"bitRate\":" + bitRate +
                    ",\"streamPort\":" + stream.port() +
                    ",\"streamToken\":\"" + escape(stream.token()) + "\"" +
                    ",\"codecMime\":\"" + CODEC_MIME + "\"}";
            return json.getBytes(StandardCharsets.UTF_8);
        }

        void allowStreamClient() {
            stream.allowClient();
        }

        void detachStreamClient() {
            stream.detachClient();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            stream.close();
            encoder.close();
            overrides.reset(displayId);
            if (displayBridge != null && displayId > 0) displayBridge.releaseVirtualDisplay();
            displayId = -1;
            executor.shutdownNow();
        }

        private static String escape(String text) {
            return text.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
