package com.phonecontrol.assistant.session

import java.util.UUID

internal class SteerQueue {
    private val pending = mutableListOf<PendingSteer>()
    private val claimed = mutableMapOf<String, PendingSteer>()

    fun enqueue(sessionId: String, text: String): PendingSteer? {
        val safeText = text.trim().take(MAX_STEER_CHARS).ifBlank { return null }
        if (pending.size >= MAX_PENDING_STEERS) return null

        val steer = PendingSteer(
            steerId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            text = safeText,
        )
        pending += steer
        return steer
    }

    fun next(sessionId: String): PendingSteer? = pending.firstOrNull { it.sessionId == sessionId }

    fun claim(sessionId: String, steerId: String): PendingSteer? {
        val index = pending.indexOfFirst {
            it.sessionId == sessionId && it.steerId == steerId
        }
        if (index < 0) return null
        val steer = pending.removeAt(index)
        claimed[steer.steerId] = steer
        return steer
    }

    fun release(expectedSessionId: String, steerId: String, runningSessionId: String?): Boolean {
        val steer = claimed[steerId] ?: return false
        if (steer.sessionId != expectedSessionId) return false
        if (runningSessionId != steer.sessionId) return false
        claimed.remove(steerId)
        if (pending.none { it.steerId == steer.steerId }) pending.add(0, steer)
        return true
    }

    fun complete(expectedSessionId: String, steerId: String): Boolean {
        val steer = claimed[steerId] ?: return false
        if (steer.sessionId != expectedSessionId) return false
        return claimed.remove(steerId) != null
    }

    fun clear(sessionId: String) {
        pending.removeAll { it.sessionId == sessionId }
        claimed.entries.removeIf { it.value.sessionId == sessionId }
    }

    fun clearAll() {
        pending.clear()
        claimed.clear()
    }

    private companion object {
        const val MAX_STEER_CHARS = 4_000
        const val MAX_PENDING_STEERS = 8
    }
}
