package com.phonecontrol.assistant.overlay

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OverlayHideReason {
    OBSERVATION,
    DHD_ACTIVITY,
}

/**
 * Shared visibility gate for the display-only overlay. Multiple independent
 * owners may temporarily hide it; the overlay is released only after every
 * owner closes its token.
 */
class OverlayVisibilityGate {
    private val lock = Any()
    private val nextTokenId = AtomicLong(0L)
    private val activeTokens = mutableSetOf<Long>()
    private val _hidden = MutableStateFlow(false)

    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    fun acquire(reason: OverlayHideReason): Token = synchronized(lock) {
        val token = nextTokenId.incrementAndGet()
        activeTokens += token
        _hidden.value = activeTokens.isNotEmpty()
        Token(this, token, reason)
    }

    private fun release(tokenId: Long) = synchronized(lock) {
        activeTokens.remove(tokenId)
        _hidden.value = activeTokens.isNotEmpty()
    }

    suspend fun <T> withHidden(
        reason: OverlayHideReason,
        block: suspend () -> T,
    ): T {
        val token = acquire(reason)
        return try {
            block()
        } finally {
            token.close()
        }
    }

    class Token internal constructor(
        private val gate: OverlayVisibilityGate,
        private val tokenId: Long,
        val reason: OverlayHideReason,
    ) : Closeable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            gate.release(tokenId)
        }
    }
}
