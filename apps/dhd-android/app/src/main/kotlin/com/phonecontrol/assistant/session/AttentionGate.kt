package com.phonecontrol.assistant.session

import kotlinx.coroutines.CompletableDeferred

internal data class PendingAttention(
    val sessionId: String,
    val reason: String,
    val completion: CompletableDeferred<AttentionResolution>,
)

internal class AttentionGate {
    private var pending: PendingAttention? = null
    private val completed = mutableMapOf<String, AttentionResolution>()

    val isPending: Boolean
        get() = pending != null

    fun pendingOrNull(): PendingAttention? = pending

    fun open(sessionId: String, reason: String): CompletableDeferred<AttentionResolution> {
        val completion = CompletableDeferred<AttentionResolution>()
        completed.remove(sessionId)
        pending = PendingAttention(sessionId, reason, completion)
        return completion
    }

    fun dismissPending() {
        pending = null
    }

    fun acknowledge(attention: PendingAttention) {
        completed[attention.sessionId] = AttentionResolution.Acknowledged
        attention.completion.complete(AttentionResolution.Acknowledged)
    }

    fun cancelPending() {
        val cancelled = pending ?: return
        pending = null
        completed[cancelled.sessionId] = AttentionResolution.Cancelled
        cancelled.completion.complete(AttentionResolution.Cancelled)
    }

    fun settle(sessionId: String) {
        cancelPending()
        completed.remove(sessionId)
    }

    fun forgetCompleted() {
        completed.clear()
    }

    fun cancelAll() {
        cancelPending()
        completed.clear()
    }

    fun waiterFor(sessionId: String): CompletableDeferred<AttentionResolution>? =
        pending
            ?.takeIf { it.sessionId == sessionId }
            ?.completion
            ?: completed.remove(sessionId)?.let { resolution ->
                CompletableDeferred<AttentionResolution>().apply { complete(resolution) }
            }
}
