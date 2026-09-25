package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.data.ConversationStore

internal class CompanionHandoff(
    private val conversationStore: ConversationStore?,
) {
    private var claimedSessionId: String? = null

    fun isClaimed(sessionId: String): Boolean = claimedSessionId == sessionId

    fun claim(sessionId: String) {
        claimedSessionId = sessionId
    }

    fun release() {
        claimedSessionId = null
    }

    fun requestFor(running: SessionState.Running): PendingRequest = PendingRequest(
        sessionId = running.sessionId,
        request = running.request,
        conversationId = running.conversationId,
        codexThreadId = conversationStore?.codexThreadId(running.conversationId),
        reasoningEffort = running.reasoningEffort,
        fastMode = running.fastMode,
        isContinuation = running.isContinuation,
    )
}
