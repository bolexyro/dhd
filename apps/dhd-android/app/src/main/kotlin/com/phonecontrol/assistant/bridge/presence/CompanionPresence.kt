package com.phonecontrol.assistant.bridge.presence

import com.phonecontrol.assistant.core.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

internal class CompanionPresence(private val clock: Clock) {
    @Volatile private var lastCompanionSeenElapsedMs: Long = 0L
    private val _companionConnected = MutableStateFlow(false)
    private val codexWarmupRequested = AtomicBoolean(false)

    val companionConnected: StateFlow<Boolean> = _companionConnected.asStateFlow()

    suspend fun monitor() {
        while (currentCoroutineContext().isActive) {
            val lastSeen = lastCompanionSeenElapsedMs
            val connected = lastSeen > 0L &&
                clock.elapsedMillis() - lastSeen <= COMPANION_PRESENCE_TIMEOUT_MS
            if (_companionConnected.value != connected) {
                _companionConnected.value = connected
            }
            delay(COMPANION_PRESENCE_CHECK_INTERVAL_MS)
        }
    }

    fun markSeen() {
        lastCompanionSeenElapsedMs = clock.elapsedMillis()
        _companionConnected.value = true
    }

    fun release() {
        lastCompanionSeenElapsedMs = 0L
        _companionConnected.value = false
    }

    fun requestCodexWarmup() {
        codexWarmupRequested.set(true)
    }

    fun consumeCodexWarmupRequest(): Boolean = codexWarmupRequested.getAndSet(false)

    companion object {
        // The companion uses short-lived TCP polls. Allow several missed
        // polls before showing a disconnect so one Wi-Fi/scheduler hiccup
        // does not flap the phone UI offline.
        const val COMPANION_PRESENCE_TIMEOUT_MS = 15_000L
        const val COMPANION_PRESENCE_CHECK_INTERVAL_MS = 1_000L
    }
}
