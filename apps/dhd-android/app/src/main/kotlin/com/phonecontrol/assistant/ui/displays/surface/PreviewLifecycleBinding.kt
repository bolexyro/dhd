package com.phonecontrol.assistant.ui.displays.surface

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** A retained TextureView must reacquire the decoder when its host returns. */
internal class PreviewLifecycleBinding(
    private val lifecycle: Lifecycle,
    private val attach: () -> Unit,
    private val detach: () -> Unit,
) : LifecycleEventObserver, AutoCloseable {
    private var attached = false

    init {
        // addObserver replays ON_START when the host is already started.
        lifecycle.addObserver(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> if (!attached) {
                attached = true
                attach()
            }
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> detachIfAttached()
            else -> Unit
        }
    }

    private fun detachIfAttached() {
        if (attached) {
            attached = false
            detach()
        }
    }

    override fun close() {
        lifecycle.removeObserver(this)
        detachIfAttached()
    }
}
