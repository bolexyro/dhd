package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.core.CoordinatorCopy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

internal interface PhoneAccessRecoveryHost {
    fun activeSessionId(): String?
    fun isSessionActive(sessionId: String): Boolean
    fun requestAttentionWaiter(reason: String, actionLabel: String): CompletableDeferred<AttentionResolution>?
    fun attentionPending(): Boolean
    suspend fun awaitAttention(sessionId: String): AttentionResolution
    fun acknowledgeAttention(automatic: Boolean): Boolean
    fun conversationId(): String?
}

internal class PhoneAccessRecovery(
    private val host: PhoneAccessRecoveryHost,
    private val phoneAccessReadyProvider: () -> Boolean,
    private val onPhoneAccessAttentionRequested: (String, String?) -> Unit,
    private val onPhoneAccessAttentionResolved: () -> Unit,
) {
    suspend fun awaitPhoneAccess(reason: String): Boolean {
        val sessionId = host.activeSessionId() ?: return false
        while (true) {
            if (!host.isSessionActive(sessionId)) return false
            if (phoneAccessReadyProvider()) return true

            val attention = host.requestAttentionWaiter(
                reason = reason,
                actionLabel = PHONE_ACCESS_INSTRUCTIONS_ACTION_LABEL,
            )
            if (attention == null) {
                if (!host.attentionPending()) return false
                if (host.awaitAttention(sessionId) == AttentionResolution.Cancelled) return false
                continue
            }

            val conversationId = host.conversationId()
            runCatching {
                onPhoneAccessAttentionRequested(reason, conversationId)
            }
            try {
                while (host.isSessionActive(sessionId)) {
                    if (phoneAccessReadyProvider()) {
                        if (host.acknowledgeAttention(automatic = true) || phoneAccessReadyProvider()) {
                            return true
                        }
                    }
                    if (attention.isCompleted) {
                        when (attention.await()) {
                            AttentionResolution.Acknowledged -> break
                            AttentionResolution.Cancelled -> return false
                        }
                    }
                    delay(PHONE_ACCESS_STATUS_POLL_INTERVAL_MS)
                }
            } finally {
                runCatching { onPhoneAccessAttentionResolved() }
            }
            if (!host.isSessionActive(sessionId)) return false
        }
    }

    companion object {
        const val PHONE_ACCESS_RECOVERY_MESSAGE =
            "DHD paused this task because it needs phone access. Turn on Wi-Fi and Wireless debugging in Android Settings, then return to DHD. Your phone action has not been sent."
        private const val PHONE_ACCESS_INSTRUCTIONS_ACTION_LABEL = CoordinatorCopy.VIEW_INSTRUCTIONS
        private const val PHONE_ACCESS_STATUS_POLL_INTERVAL_MS = 500L
    }
}
