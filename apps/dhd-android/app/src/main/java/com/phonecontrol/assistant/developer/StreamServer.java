package com.phonecontrol.assistant.developer;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class StreamServer implements AvcEncoderPipeline.Sink {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int width;
    private final int height;
    private final AvcEncoderPipeline encoder;
    private final AtomicBoolean closed;
    private final String streamToken = newToken();
    private final ArrayDeque<AvcEncoderPipeline.EncodedPacket> packets =
            new ArrayDeque<>(DhdNativeDisplayService.MAX_STREAM_QUEUE_PACKETS);
    private final Object streamLock = new Object();
    private volatile boolean streamClientAllowed;
    private volatile Socket streamClient;
    /** True until the queue contains a fresh, decodable IDR boundary. */
    private boolean awaitingKeyFrame = true;
    /** A reconnect must not accept a keyframe from before its reset point. */
    private long keyFrameRequiredAfterPts = Long.MIN_VALUE;
    private long lastEnqueuedPresentationTimeUs = Long.MIN_VALUE;
    /**
     * The first viewer may consume a keyframe that was encoded while the
     * display was starting. Keep that startup frame; only later viewers
     * need a queue reset and a fresh IDR boundary.
     */
    private boolean streamHasServedClient;
    private ServerSocket streamServer;
    // Keep the bound port as immutable session metadata. The daemon can
    // snapshot a session for LIST while close() is releasing the server;
    // reading ServerSocket.getLocalPort() after it is nulled would make
    // reconciliation fail spuriously.
    private volatile int streamPort = -1;

    StreamServer(int width, int height, AvcEncoderPipeline encoder, AtomicBoolean closed) {
        this.width = width;
        this.height = height;
        this.encoder = encoder;
        this.closed = closed;
    }

    String token() {
        return streamToken;
    }

    int port() {
        return streamPort;
    }

    void bind() throws IOException {
        streamServer = new ServerSocket();
        streamServer.setReuseAddress(true);
        streamServer.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        streamPort = streamServer.getLocalPort();
    }

    void allowClient() {
        streamClientAllowed = true;
    }

    void detachClient() {
        streamClientAllowed = false;
        synchronized (streamLock) {
            closeQuietly(streamClient);
            streamClient = null;
            streamLock.notifyAll();
        }
    }

    void close() {
        synchronized (streamLock) {
            closeQuietly(streamClient);
            closeQuietly(streamServer);
            streamClient = null;
            streamServer = null;
            streamLock.notifyAll();
        }
    }

    @Override
    public void onOutputFormatChanged() {
        synchronized (streamLock) { streamLock.notifyAll(); }
    }

    @Override
    public void onPacket(AvcEncoderPipeline.EncodedPacket packet) {
        if (packet.bytes.length == 0 || packet.bytes.length > DhdNativeDisplayService.MAX_STREAM_PACKET_BYTES) return;
        boolean requestSyncFrame = false;
        boolean accepted = true;
        synchronized (streamLock) {
            long previousLastPts = lastEnqueuedPresentationTimeUs;
            lastEnqueuedPresentationTimeUs = Math.max(
                    lastEnqueuedPresentationTimeUs,
                    packet.presentationTimeUs);
            if (packets.size() >= DhdNativeDisplayService.MAX_STREAM_QUEUE_PACKETS) {
                // Dropping an arbitrary AVC packet can discard a P-frame
                // that later frames reference. The decoder then renders a
                // blank surface until the next IDR. Reset the queue at a
                // GOP boundary and request a fresh sync frame instead.
                packets.clear();
                awaitingKeyFrame = true;
                keyFrameRequiredAfterPts = Math.max(
                        keyFrameRequiredAfterPts,
                        previousLastPts);
                requestSyncFrame = true;
            }
            if (awaitingKeyFrame) {
                boolean isFreshKeyFrame =
                        (packet.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 &&
                                packet.presentationTimeUs > keyFrameRequiredAfterPts;
                if (!isFreshKeyFrame) {
                    // Keep the queue empty until the encoder supplies the
                    // requested fresh IDR. Do not return before the sync-
                    // frame request below; otherwise an overflow would
                    // leave the decoder waiting forever on an old GOP.
                    accepted = false;
                } else {
                    awaitingKeyFrame = false;
                    keyFrameRequiredAfterPts = Long.MIN_VALUE;
                }
            }
            if (accepted) {
                packets.addLast(packet);
                streamLock.notifyAll();
            }
        }
        if (requestSyncFrame) {
            try {
                encoder.requestSyncFrame();
            } catch (Throwable ignored) {
                // The next encoder keyframe still provides a safe
                // recovery point if this best-effort request is rejected.
            }
        }
        if (!accepted) return;
    }

    void serve() {
        while (!closed.get()) {
            Socket client = null;
            try {
                ServerSocket server = streamServer;
                if (server == null) return;
                client = server.accept();
                client.setTcpNoDelay(true);
                // Authenticate the client before exposing the codec
                // configuration or a single video packet. A server-only
                // token in the response would allow any local process to
                // read the preview stream.
                client.setSoTimeout(2_000);
                DataInputStream input = new DataInputStream(client.getInputStream());
                if (!authenticateClient(input)) {
                    closeQuietly(client);
                    continue;
                }
                client.setSoTimeout(30_000);
                synchronized (streamLock) {
                    if (!streamClientAllowed) {
                        closeQuietly(client);
                        continue;
                    }
                    closeQuietly(streamClient);
                    streamClient = client;
                }
                writeStream(client);
            } catch (Throwable ignored) {
                if (closed.get()) return;
            } finally {
                synchronized (streamLock) {
                    if (streamClient == client) streamClient = null;
                }
                closeQuietly(client);
            }
        }
    }

    private boolean authenticateClient(DataInputStream input) throws IOException {
        if (input.readInt() != DhdNativeDisplayService.STREAM_MAGIC || input.readInt() != DhdNativeDisplayService.STREAM_VERSION) return false;
        int length = input.readInt();
        if (length < 0 || length > 128) return false;
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return DhdMaintenanceProtocol.tokensEqual(
                streamToken,
                new String(bytes, StandardCharsets.UTF_8));
    }

    private void writeStream(Socket client) throws Exception {
        DataOutputStream output = new DataOutputStream(client.getOutputStream());
        boolean requestInitialSync = false;
        synchronized (streamLock) {
            // The first client can use the keyframe already buffered
            // while the target activity was launching. Clearing it makes
            // a static display depend on a second sync frame, which some
            // hardware encoders do not emit until the pixels change.
            // Once a client has been served, a replacement decoder must
            // start at a fresh IDR so it cannot show an old display.
            if (shouldResetStreamQueue(streamHasServedClient)) {
                packets.clear();
                awaitingKeyFrame = true;
                keyFrameRequiredAfterPts = lastEnqueuedPresentationTimeUs;
                requestInitialSync = true;
            } else if (packets.isEmpty()) {
                // No startup frame is available yet. Preserve the queue
                // semantics and request one without discarding anything.
                awaitingKeyFrame = true;
                keyFrameRequiredAfterPts = Long.MIN_VALUE;
                requestInitialSync = true;
            }
            streamHasServedClient = true;
        }
        if (requestInitialSync) {
            // Request a fresh IDR so a static display does not wait for
            // the encoder's next periodic sync interval. The first
            // request never invalidates a frame already in the queue.
            encoder.requestSyncFrame();
        }
        MediaFormat format;
        synchronized (streamLock) {
            while (!closed.get() && encoder.outputFormat() == null) streamLock.wait(100L);
            format = encoder.outputFormat();
        }
        if (format == null) throw new IOException("Encoder format did not become available.");
        output.writeInt(DhdNativeDisplayService.STREAM_MAGIC);
        output.writeInt(DhdNativeDisplayService.STREAM_VERSION);
        writeString(output, streamToken);
        writeString(output, DhdNativeDisplayService.CODEC_MIME);
        output.writeInt(width);
        output.writeInt(height);
        writeBuffer(output, format, "csd-0");
        writeBuffer(output, format, "csd-1");
        output.flush();

        boolean keyFrameSeen = false;
        while (!closed.get() && !client.isClosed()) {
            AvcEncoderPipeline.EncodedPacket packet;
            synchronized (streamLock) {
                while (!closed.get() && packets.isEmpty() && streamClient == client) streamLock.wait(250L);
                if (closed.get() || streamClient != client) return;
                packet = packets.pollFirst();
            }
            if (packet == null) continue;
            if (!keyFrameSeen) {
                if ((packet.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) continue;
                keyFrameSeen = true;
            }
            output.writeInt(packet.flags);
            output.writeLong(packet.presentationTimeUs);
            output.writeInt(packet.bytes.length);
            output.write(packet.bytes);
            output.flush();
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128) throw new IOException("DHD display stream string is too long.");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeBuffer(DataOutputStream output, MediaFormat format, String key) throws IOException {
        ByteBuffer source = format.getByteBuffer(key);
        if (source == null) {
            output.writeInt(-1);
            return;
        }
        ByteBuffer buffer = source.duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (bytes.length > 1 << 20) throw new IOException("DHD codec config is too large.");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Throwable ignored) {}
    }

    /** Queue reset is safe only after the stream has served its first client. */
    static boolean shouldResetStreamQueue(boolean streamHasServedClient) {
        return streamHasServedClient;
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
