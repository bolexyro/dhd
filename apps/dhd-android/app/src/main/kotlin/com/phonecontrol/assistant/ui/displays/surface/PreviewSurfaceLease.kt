package com.phonecontrol.assistant.ui.displays.surface

import android.view.Surface as AndroidSurface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Surface destruction is asynchronous at the preview boundary. The owner
 * releases the TextureView surface only after the decoder has stopped using it.
 * The gate is idempotent because a TextureView can report destruction both
 * while its callbacks are being cleared and from its listener afterwards.
 */
internal class PreviewSurfaceLease(
    private val releaseAction: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

internal typealias PreviewSurfaceDestroyed = (AndroidSurface, () -> Unit) -> Unit
