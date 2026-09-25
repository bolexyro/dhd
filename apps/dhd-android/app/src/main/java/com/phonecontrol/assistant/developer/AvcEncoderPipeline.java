package com.phonecontrol.assistant.developer;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

final class AvcEncoderPipeline {
    interface Sink {
        void onOutputFormatChanged();

        void onPacket(EncodedPacket packet);
    }

    private final Object codecLock = new Object();
    private final AtomicBoolean closed;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private volatile MediaFormat outputFormat;

    AvcEncoderPipeline(AtomicBoolean closed) {
        this.closed = closed;
    }

    Surface start(int width, int height, int bitRate, int frameRate) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(DhdNativeDisplayService.CODEC_MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfoCompat.COLOR_FORMAT_SURFACE);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder = MediaCodec.createEncoderByType(DhdNativeDisplayService.CODEC_MIME);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();
        return encoderSurface;
    }

    MediaFormat outputFormat() {
        return outputFormat;
    }

    void close() {
        synchronized (codecLock) {
            if (encoder != null) {
                try { encoder.stop(); } catch (Throwable ignored) {}
                try { encoder.release(); } catch (Throwable ignored) {}
                encoder = null;
            }
            if (encoderSurface != null) {
                closeQuietly(encoderSurface);
                encoderSurface = null;
            }
        }
    }

    void drain(Sink sink) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!closed.get()) {
                EncodedPacket packet = null;
                boolean endOfStream = false;
                boolean formatChanged = false;
                synchronized (codecLock) {
                    MediaCodec codec = encoder;
                    if (codec == null) return;
                    int index = codec.dequeueOutputBuffer(info, 100_000L);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputFormat = codec.getOutputFormat();
                        formatChanged = true;
                    } else if (index >= 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer != null && info.size > 0) {
                            ByteBuffer duplicate = buffer.duplicate();
                            duplicate.position(info.offset);
                            duplicate.limit(info.offset + info.size);
                            byte[] bytes = new byte[info.size];
                            duplicate.get(bytes);
                            packet = new EncodedPacket(info.flags, info.presentationTimeUs, bytes);
                        }
                        codec.releaseOutputBuffer(index, false);
                        endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    }
                }
                if (formatChanged) {
                    sink.onOutputFormatChanged();
                    continue;
                }
                if (packet != null) sink.onPacket(packet);
                if (endOfStream) return;
            }
        } catch (Throwable ignored) {
            // The task receives a stream EOF and can report the failed preview.
        }
    }

    void requestSyncFrame() throws IOException {
        synchronized (codecLock) {
            MediaCodec codec = encoder;
            if (codec == null || closed.get()) throw new IOException("DHD display encoder is closed.");
            try {
                android.os.Bundle parameters = new android.os.Bundle();
                parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                codec.setParameters(parameters);
            } catch (Throwable error) {
                throw new IOException("DHD display encoder could not request a key frame.", error);
            }
        }
    }

    private static void closeQuietly(Surface surface) {
        if (surface == null) return;
        try { surface.release(); } catch (Throwable ignored) {}
    }

    static final class EncodedPacket {
        final int flags;
        final long presentationTimeUs;
        final byte[] bytes;

        EncodedPacket(int flags, long presentationTimeUs, byte[] bytes) {
            this.flags = flags;
            this.presentationTimeUs = presentationTimeUs;
            this.bytes = bytes;
        }
    }

    /** Compatibility constants kept out of the public SDK surface. */
    private static final class MediaCodecInfoCompat {
        static final int COLOR_FORMAT_SURFACE = 0x7F000789;

        private MediaCodecInfoCompat() {}
    }
}
