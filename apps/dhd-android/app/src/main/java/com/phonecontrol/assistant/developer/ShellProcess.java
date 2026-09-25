package com.phonecontrol.assistant.developer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ShellProcess {
    private ShellProcess() {}

    static Result run(
            List<String> command,
            long timeoutMs,
            int maxStderrBytes,
            String stdoutThreadName,
            String stderrThreadName
    ) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        Collector stdout = new Collector(process.getInputStream(), DhdMaintenanceProtocol.MAX_OUTPUT_BYTES);
        Collector stderr = new Collector(process.getErrorStream(), maxStderrBytes);
        Thread stdoutThread = new Thread(stdout, stdoutThreadName);
        Thread stderrThread = new Thread(stderr, stderrThreadName);
        stdoutThread.start();
        stderrThread.start();

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        stdoutThread.join(1_000L);
        stderrThread.join(1_000L);

        return new Result(
                finished ? process.exitValue() : DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE,
                !finished,
                stdout.overflowed || stderr.overflowed,
                stdout.bytes(),
                stderr.text());
    }

    static final class Result {
        final int exitCode;
        final boolean timedOut;
        final boolean overflowed;
        final byte[] stdout;
        final String stderr;

        Result(int exitCode, boolean timedOut, boolean overflowed, byte[] stdout, String stderr) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.overflowed = overflowed;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static final class Collector implements Runnable {
        private final InputStream input;
        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile boolean overflowed;

        Collector(InputStream input, int maxBytes) {
            this.input = input;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > maxBytes) {
                        overflowed = true;
                        // Keep draining so the child cannot block on a full
                        // pipe before its command timeout is reached.
                        continue;
                    }
                    output.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // The process may close its stream while the timeout handler
                // is terminating it; the captured bytes remain useful.
            }
        }

        byte[] bytes() {
            return output.toByteArray();
        }

        String text() {
            return new String(bytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
