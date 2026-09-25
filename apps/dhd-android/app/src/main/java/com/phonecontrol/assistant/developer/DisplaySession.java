package com.phonecontrol.assistant.developer;

import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class DisplaySession implements Closeable {
    final String sessionKey;
    private final String packageName;
    final int width;
    final int height;
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
        String json = "{\"type\":\"" + DhdNativeDisplayService.CREATED_TYPE + "\"" +
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
                ",\"codecMime\":\"" + DhdNativeDisplayService.CODEC_MIME + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    int displayId() {
        return displayId;
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
        if (displayBridge != null) displayBridge.releaseVirtualDisplay();
        displayId = -1;
        executor.shutdownNow();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
