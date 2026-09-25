package com.phonecontrol.assistant.ui.chat.composer

internal data class PendingSteerDraft(
    val text: String,
    val reasoningEffort: String,
    val fastMode: Boolean,
)

internal data class SteerDraftQueue(
    val drafts: List<PendingSteerDraft>,
    val sessionId: String?,
    val carryToNextRun: Boolean,
)

internal data class SteerDraftPromotion(
    val draft: PendingSteerDraft,
    val queue: SteerDraftQueue,
)

internal fun SteerDraftQueue.forActiveSession(activeSessionId: String?): SteerDraftQueue {
    if (sessionId == activeSessionId) return this
    return if (carryToNextRun && activeSessionId != null && drafts.isNotEmpty()) {
        // Keep the remaining queue attached to the auto-started run.
        copy(sessionId = activeSessionId, carryToNextRun = false)
    } else {
        SteerDraftQueue(drafts = emptyList(), sessionId = activeSessionId, carryToNextRun = false)
    }
}

internal fun SteerDraftQueue.promoteAfterCompletion(completedSessionId: String): SteerDraftPromotion? {
    if (drafts.isEmpty() || sessionId != completedSessionId) return null
    // Promote only the oldest held draft. Keep the rest in FIFO order for
    // later follow-up runs.
    val remaining = drafts.drop(1)
    return SteerDraftPromotion(
        draft = drafts.first(),
        queue = copy(drafts = remaining, carryToNextRun = remaining.isNotEmpty()),
    )
}

internal fun SteerDraftQueue.withDraft(draft: PendingSteerDraft, activeSessionId: String?): SteerDraftQueue =
    copy(drafts = drafts + draft, sessionId = activeSessionId)

internal fun SteerDraftQueue.without(index: Int): SteerDraftQueue {
    if (index !in drafts.indices) return this
    val remaining = drafts.toMutableList().also { it.removeAt(index) }
    return if (remaining.isEmpty()) {
        SteerDraftQueue(drafts = emptyList(), sessionId = null, carryToNextRun = false)
    } else {
        copy(drafts = remaining)
    }
}

internal fun encodeSteerDrafts(drafts: List<PendingSteerDraft>): ArrayList<String> =
    drafts.flatMapTo(ArrayList()) { draft ->
        listOf(draft.text, draft.reasoningEffort, draft.fastMode.toString())
    }

internal fun decodeSteerDrafts(saved: List<String>): List<PendingSteerDraft> =
    saved.chunked(3).mapNotNull { fields ->
        if (fields.size != 3) return@mapNotNull null
        PendingSteerDraft(
            text = fields[0],
            reasoningEffort = fields[1],
            fastMode = fields[2].toBoolean(),
        )
    }
