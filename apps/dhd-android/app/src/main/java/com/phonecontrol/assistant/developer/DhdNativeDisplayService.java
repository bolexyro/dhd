package com.phonecontrol.assistant.developer;

import java.io.Closeable;
import java.util.List;

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

    private final DisplayCommandHandler handler = new DisplayCommandHandler();

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
            return new CommandResult(DhdMaintenanceProtocol.EXIT_CODE_COMMAND_FAILED, false,
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
                    return handler.create(command);
                case "attach":
                    return handler.attach(command);
                case "detach":
                    return handler.detach(command);
                case "capture":
                    return handler.capture(command, binaryOutput);
                case "list":
                    return handler.listSessions(command);
                case "close":
                    return handler.closeSession(command);
                case "close-all":
                    return handler.closeAll();
                default:
                    return CommandResult.failure("DHD display operation is unsupported: " + operation);
            }
        } catch (Throwable error) {
            return CommandResult.failure("DHD display operation failed: " + safeMessage(error));
        }
    }

    static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null &&
                (current.getMessage() == null || current.getMessage().isEmpty())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isEmpty() ? current.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        handler.close();
    }
}
