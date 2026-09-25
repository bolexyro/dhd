package com.phonecontrol.assistant.ui.displays.surface

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface as AndroidSurface
import android.view.TextureView
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.semantics
import java.util.concurrent.atomic.AtomicBoolean

/** A scroll-safe decoder target with no control semantics. */
internal class ReadOnlyPreviewTextureView(context: Context) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private val surfaceRecordsLock = Any()
    private val pendingSurfaceReleases = java.util.IdentityHashMap<SurfaceTexture, DecoderSurface>()
    private var decoderSurface: DecoderSurface? = null
    private var availableTexture: SurfaceTexture? = null
    private var surfaceCallbackGeneration = 0L
    private var onAvailable: ((AndroidSurface) -> Unit)? = null
    private var onDestroyed: PreviewSurfaceDestroyed? = null

    init {
        surfaceTextureListener = this
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        setOnTouchListener { _, _ -> true }
    }

    fun setSurfaceCallbacks(
        onAvailable: (AndroidSurface) -> Unit,
        onDestroyed: PreviewSurfaceDestroyed,
    ) {
        var immediateSurface: AndroidSurface? = null
        var immediateCallback: ((AndroidSurface) -> Unit)? = null
        var pendingRelease: DecoderSurface? = null
        var pendingTexture: SurfaceTexture? = null
        var releaseImmediately: DecoderSurface? = null
        var generation = 0L
        synchronized(surfaceRecordsLock) {
            generation = ++surfaceCallbackGeneration
            this.onAvailable = onAvailable
            this.onDestroyed = onDestroyed

            val current = decoderSurface
            if (current != null && !current.destroyNotified && current.surface.isValid) {
                immediateSurface = current.surface
                immediateCallback = onAvailable
            } else {
                // A session-key change can dispose and recreate this effect
                // while TextureView keeps the same SurfaceTexture available.
                // Wait for the old asynchronous detach before wrapping that
                // texture again; otherwise its release can invalidate the
                // replacement decoder's target.
                val texture = availableTexture
                if (current != null) {
                    decoderSurface = null
                    if (!current.destroyNotified) {
                        notifySurfaceDestroyedLocked(current)
                        if (this.onDestroyed == null) releaseImmediately = current
                    }
                }
                if (texture != null) {
                    pendingSurfaceReleases[texture]?.let { pending ->
                        pendingRelease = pending
                        pendingTexture = texture
                    } ?: DecoderSurface(texture).also { created ->
                        decoderSurface = created
                        immediateSurface = created.surface
                        immediateCallback = onAvailable
                    }
                }
            }
        }
        releaseImmediately?.release()
        invokeAvailableIfCurrent(immediateSurface, immediateCallback, generation)
        if (pendingRelease != null && pendingTexture != null) {
            val texture = pendingTexture!!
            pendingRelease!!.whenReleased {
                announceAvailableAfterRelease(texture, generation)
            }
        }
    }

    fun clearSurfaceCallbacks() {
        var releaseImmediately = false
        val current: DecoderSurface?
        synchronized(surfaceRecordsLock) {
            ++surfaceCallbackGeneration
            current = decoderSurface?.also {
                decoderSurface = null
                if (!it.destroyNotified) {
                    notifySurfaceDestroyedLocked(it)
                    releaseImmediately = onDestroyed == null
                }
            }
            onAvailable = null
            onDestroyed = null
        }
        // If no callback was registered, the view still owns this record and
        // must release it. Normal callers always release it from their async
        // detach completion.
        if (current != null && releaseImmediately) current.release()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        var immediateSurface: AndroidSurface? = null
        var immediateCallback: ((AndroidSurface) -> Unit)? = null
        var pendingRelease: DecoderSurface? = null
        var releaseImmediately: DecoderSurface? = null
        var generation = 0L
        synchronized(surfaceRecordsLock) {
            generation = surfaceCallbackGeneration
            availableTexture = surface
            val current = decoderSurface
            if (current != null && current.texture === surface &&
                !current.destroyNotified && current.surface.isValid
            ) {
                immediateSurface = current.surface
                immediateCallback = onAvailable
            } else {
                current?.let { previous ->
                    decoderSurface = null
                    if (!previous.destroyNotified) {
                        notifySurfaceDestroyedLocked(previous)
                        if (onDestroyed == null) releaseImmediately = previous
                    }
                }
                pendingSurfaceReleases[surface]?.let { pending ->
                    pendingRelease = pending
                    generation = surfaceCallbackGeneration
                } ?: DecoderSurface(surface).also { created ->
                    decoderSurface = created
                    immediateSurface = created.surface
                    immediateCallback = onAvailable
                }
            }
        }
        releaseImmediately?.release()
        invokeAvailableIfCurrent(immediateSurface, immediateCallback, generation)
        if (pendingRelease != null) {
            pendingRelease!!.whenReleased {
                announceAvailableAfterRelease(surface, generation)
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        var releaseImmediately = false
        val current: DecoderSurface?
        synchronized(surfaceRecordsLock) {
            val record = decoderSurface?.takeIf { it.texture === surface }
                ?: pendingSurfaceReleases[surface]
            if (decoderSurface === record) decoderSurface = null
            if (availableTexture === surface) availableTexture = null
            val wasAlreadyNotified = record?.destroyNotified == true
            if (record != null) {
                record.markTextureDestroyed()
                if (!wasAlreadyNotified) {
                    notifySurfaceDestroyedLocked(record)
                    releaseImmediately = onDestroyed == null
                }
            }
            current = record
        }
        if (current == null) return true
        // A pending record was already handed to the asynchronous detach
        // callback. Clearing callbacks after that handoff must not turn this
        // later TextureView notification into an early release.
        if (releaseImmediately) current.release()
        // Returning false transfers SurfaceTexture ownership to us. The
        // release callback runs after the decoder has detached from this
        // surface, which prevents BufferQueue/MediaCodec races.
        return false
    }

    private fun announceAvailableAfterRelease(
        texture: SurfaceTexture,
        generation: Long,
    ) {
        var surface: AndroidSurface? = null
        var callback: ((AndroidSurface) -> Unit)? = null
        synchronized(surfaceRecordsLock) {
            if (generation != surfaceCallbackGeneration ||
                onAvailable == null ||
                availableTexture !== texture ||
                decoderSurface != null ||
                pendingSurfaceReleases.containsKey(texture)
            ) {
                return@synchronized
            }
            DecoderSurface(texture).also { created ->
                decoderSurface = created
                surface = created.surface
                callback = onAvailable
            }
        }
        invokeAvailableIfCurrent(surface, callback, generation)
    }

    private fun invokeAvailableIfCurrent(
        surface: AndroidSurface?,
        callback: ((AndroidSurface) -> Unit)?,
        generation: Long,
    ) {
        if (surface == null || callback == null) return
        val stillCurrent = synchronized(surfaceRecordsLock) {
            surfaceCallbackGeneration == generation && onAvailable === callback
        }
        if (stillCurrent) callback(surface)
    }

    private fun notifySurfaceDestroyedLocked(record: DecoderSurface) {
        if (!record.markDestroyed()) return
        pendingSurfaceReleases[record.texture] = record
        onDestroyed?.invoke(record.surface, record::release)
    }

    private inner class DecoderSurface(
        val texture: SurfaceTexture,
    ) {
        val surface = AndroidSurface(texture)
        private val released = AtomicBoolean(false)
        private val textureDestroyed = AtomicBoolean(false)
        private val releaseListeners = mutableListOf<() -> Unit>()
        private val releaseLease = PreviewSurfaceLease {
            runCatching { surface.release() }
            val releaseTexture: Boolean
            val listeners: List<() -> Unit>
            synchronized(surfaceRecordsLock) {
                releaseTexture = textureDestroyed.get()
                if (pendingSurfaceReleases[texture] === this@DecoderSurface) {
                    pendingSurfaceReleases.remove(texture)
                }
                released.set(true)
                listeners = releaseListeners.toList()
                releaseListeners.clear()
            }
            if (releaseTexture) runCatching { texture.release() }
            listeners.forEach { listener -> runCatching { listener() } }
        }
        private val destroyed = AtomicBoolean(false)

        val destroyNotified: Boolean
            get() = destroyed.get()

        fun markDestroyed(): Boolean = destroyed.compareAndSet(false, true)

        fun markTextureDestroyed() {
            textureDestroyed.set(true)
        }

        fun whenReleased(listener: () -> Unit) {
            val invokeImmediately = synchronized(surfaceRecordsLock) {
                if (released.get()) {
                    true
                } else {
                    releaseListeners += listener
                    false
                }
            }
            if (invokeImmediately) listener()
        }

        fun release() = releaseLease.release()
    }
}
