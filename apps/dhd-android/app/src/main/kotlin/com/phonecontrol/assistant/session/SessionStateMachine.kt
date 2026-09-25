package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.core.conversationIdOrNull
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.data.RunStatus
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.execution.TaskDisplayStatus

internal sealed interface SessionEvent {
    data class Start(
        val sessionId: String,
        val request: String,
        val conversationId: String?,
        val reasoningEffort: String,
        val fastMode: Boolean,
        val nowEpochMs: Long,
    ) : SessionEvent

    data class Pause(val nowEpochMs: Long) : SessionEvent

    data class Resume(val nowEpochMs: Long) : SessionEvent

    data class ContinueStopped(val sessionId: String, val nowEpochMs: Long) : SessionEvent

    data class Stop(val reason: String, val nowEpochMs: Long) : SessionEvent

    data class Fail(val reason: String, val nowEpochMs: Long) : SessionEvent

    data class Complete(
        val message: String,
        val agentFeedback: String?,
        val agentMessageId: String?,
        val nowEpochMs: Long,
    ) : SessionEvent

    data object Reset : SessionEvent
}

internal sealed interface SessionEffect {
    data object CommitState : SessionEffect
    data object ForgetCompletedAttentions : SessionEffect
    data class SettleAttention(val sessionId: String) : SessionEffect
    data object CancelAllAttention : SessionEffect
    data object ClearPointer : SessionEffect
    data object ReleaseClaim : SessionEffect
    data object ClearToolCalls : SessionEffect
    data object ClearEvents : SessionEffect
    data class ClearSteers(val sessionId: String) : SessionEffect
    data object ClearAllSteers : SessionEffect
    data class CancelTransport(val sessionId: String) : SessionEffect
    data class RetainDisplay(
        val sessionId: String,
        val status: TaskDisplayStatus,
        val error: String?,
    ) : SessionEffect
    data class UpdateDisplayStatus(val sessionId: String, val status: TaskDisplayStatus) : SessionEffect
    data class OpenRun(val sessionId: String, val request: String, val conversationId: String?) : SessionEffect
    data class OpenContinuationRun(val sessionId: String, val conversationId: String?) : SessionEffect
    data class SetRunStatus(val sessionId: String, val status: RunStatus) : SessionEffect
    data class CompleteRun(
        val sessionId: String,
        val status: RunStatus,
        val assistantText: String?,
    ) : SessionEffect
    data class AppendEvent(
        val kind: ActivityEventKind,
        val message: String,
        val sessionId: String,
        val eventId: String? = null,
    ) : SessionEffect
}

internal data class SessionTransition(
    val state: SessionState,
    val effects: List<SessionEffect>,
)

internal object SessionStateMachine {
    const val MAX_TEXT_CHARS = 240
    const val MAX_AGENT_FEEDBACK_CHARS = 4_000

    fun reduce(state: SessionState, event: SessionEvent): SessionTransition? = when (event) {
        is SessionEvent.Start -> start(state, event)
        is SessionEvent.Pause -> pause(state, event)
        is SessionEvent.Resume -> resume(state, event)
        is SessionEvent.ContinueStopped -> continueStopped(state, event)
        is SessionEvent.Stop -> stop(state, event)
        is SessionEvent.Fail -> fail(state, event)
        is SessionEvent.Complete -> complete(state, event)
        SessionEvent.Reset -> reset(state)
    }

    fun withPurpose(state: SessionState, purpose: String, metadataPurpose: String?): SessionState? =
        when (state) {
            is SessionState.Running -> state.copy(
                currentPurpose = purpose,
                currentToolMetadataPurpose = metadataPurpose,
            )
            is SessionState.Paused -> state.copy(
                currentPurpose = purpose,
                currentToolMetadataPurpose = metadataPurpose,
            )
            else -> null
        }

    fun withAttention(state: SessionState, reason: String, actionLabel: String): SessionState? =
        when (state) {
            is SessionState.Running -> state.copy(
                currentPurpose = CoordinatorCopy.NEEDS_ATTENTION,
                currentToolMetadataPurpose = null,
                attentionReason = reason,
                attentionActionLabel = actionLabel,
            )
            is SessionState.Paused -> state.copy(
                currentPurpose = CoordinatorCopy.NEEDS_ATTENTION,
                currentToolMetadataPurpose = null,
                attentionReason = reason,
                attentionActionLabel = actionLabel,
            )
            else -> null
        }

    fun withoutAttention(state: SessionState): SessionState = when (state) {
        is SessionState.Running -> state.copy(
            currentPurpose = CoordinatorCopy.DHD_PLANNING,
            currentToolMetadataPurpose = null,
            attentionReason = null,
            attentionActionLabel = null,
        )
        is SessionState.Paused -> state.copy(
            currentPurpose = "Paused",
            currentToolMetadataPurpose = null,
            attentionReason = null,
            attentionActionLabel = null,
        )
        else -> state
    }

    private fun start(state: SessionState, event: SessionEvent.Start): SessionTransition? {
        if (event.request.isBlank() || state.isActive) return null
        val normalizedReasoningEffort = ReasoningEffort.fromCodexValue(event.reasoningEffort)?.codexValue
            ?: ReasoningEffort.default.codexValue
        return SessionTransition(
            state = SessionState.Running(
                sessionId = event.sessionId,
                request = event.request.trim(),
                currentPurpose = CoordinatorCopy.PREPARING_REQUEST,
                startedAtEpochMs = event.nowEpochMs,
                conversationId = event.conversationId,
                reasoningEffort = normalizedReasoningEffort,
                fastMode = event.fastMode,
                isContinuation = false,
                elapsedBeforeStartMs = 0L,
            ),
            effects = listOf(
                SessionEffect.ForgetCompletedAttentions,
                SessionEffect.ClearPointer,
                SessionEffect.OpenRun(event.sessionId, event.request, event.conversationId),
                SessionEffect.ReleaseClaim,
                SessionEffect.ClearToolCalls,
                SessionEffect.CommitState,
                SessionEffect.AppendEvent(
                    ActivityEventKind.SESSION_STARTED,
                    "Request accepted. Waiting for the desktop Codex bridge.",
                    event.sessionId,
                ),
            ),
        )
    }

    private fun pause(state: SessionState, event: SessionEvent.Pause): SessionTransition? {
        val running = state as? SessionState.Running ?: return null
        return SessionTransition(
            state = SessionState.Paused(
                sessionId = running.sessionId,
                request = running.request,
                currentPurpose = running.currentPurpose,
                currentToolMetadataPurpose = running.currentToolMetadataPurpose,
                startedAtEpochMs = event.nowEpochMs,
                conversationId = running.conversationId,
                reasoningEffort = running.reasoningEffort,
                fastMode = running.fastMode,
                isContinuation = running.isContinuation,
                attentionReason = running.attentionReason,
                attentionActionLabel = running.attentionActionLabel,
                elapsedBeforeStartMs = running.elapsedAt(event.nowEpochMs),
            ),
            effects = listOf(
                SessionEffect.CommitState,
                SessionEffect.SetRunStatus(running.sessionId, RunStatus.PAUSED),
                SessionEffect.UpdateDisplayStatus(running.sessionId, TaskDisplayStatus.PAUSED),
                SessionEffect.AppendEvent(ActivityEventKind.SESSION_PAUSED, "Session paused.", running.sessionId),
            ),
        )
    }

    private fun resume(state: SessionState, event: SessionEvent.Resume): SessionTransition? {
        val paused = state as? SessionState.Paused ?: return null
        return SessionTransition(
            state = SessionState.Running(
                sessionId = paused.sessionId,
                request = paused.request,
                currentPurpose = paused.currentPurpose,
                currentToolMetadataPurpose = paused.currentToolMetadataPurpose,
                startedAtEpochMs = event.nowEpochMs,
                conversationId = paused.conversationId,
                reasoningEffort = paused.reasoningEffort,
                fastMode = paused.fastMode,
                isContinuation = paused.isContinuation,
                attentionReason = paused.attentionReason,
                attentionActionLabel = paused.attentionActionLabel,
                elapsedBeforeStartMs = paused.elapsedBeforeStartMs,
            ),
            effects = listOf(
                SessionEffect.CommitState,
                SessionEffect.SetRunStatus(paused.sessionId, RunStatus.RUNNING),
                SessionEffect.UpdateDisplayStatus(paused.sessionId, TaskDisplayStatus.RUNNING),
                SessionEffect.AppendEvent(ActivityEventKind.SESSION_RESUMED, "Session resumed.", paused.sessionId),
            ),
        )
    }

    private fun continueStopped(
        state: SessionState,
        event: SessionEvent.ContinueStopped,
    ): SessionTransition? {
        val stopped = state as? SessionState.Stopped ?: return null
        if (state.isActive) return null
        return SessionTransition(
            state = SessionState.Running(
                sessionId = event.sessionId,
                request = stopped.request,
                currentPurpose = "Preparing continuation",
                startedAtEpochMs = event.nowEpochMs,
                conversationId = stopped.conversationId,
                reasoningEffort = stopped.reasoningEffort,
                fastMode = stopped.fastMode,
                isContinuation = true,
                elapsedBeforeStartMs = stopped.workedDurationMs,
            ),
            effects = listOf(
                SessionEffect.OpenContinuationRun(event.sessionId, stopped.conversationId),
                SessionEffect.ForgetCompletedAttentions,
                SessionEffect.ClearPointer,
                SessionEffect.ReleaseClaim,
                SessionEffect.CommitState,
                SessionEffect.AppendEvent(
                    ActivityEventKind.SESSION_STARTED,
                    "Continuation accepted. Waiting for the desktop Codex bridge.",
                    event.sessionId,
                ),
            ),
        )
    }

    private fun stop(state: SessionState, event: SessionEvent.Stop): SessionTransition? {
        val sessionId = state.sessionIdOrNull ?: return null
        val continuationSettings = state.continuationSettings()
        return SessionTransition(
            state = SessionState.Stopped(
                sessionId = sessionId,
                reason = event.reason,
                conversationId = state.conversationIdOrNull,
                reasoningEffort = continuationSettings.first,
                fastMode = continuationSettings.second,
                request = state.requestOrNull() ?: "",
                workedDurationMs = state.elapsedAt(event.nowEpochMs),
            ),
            effects = listOf(
                SessionEffect.SettleAttention(sessionId),
                SessionEffect.CancelTransport(sessionId),
                SessionEffect.RetainDisplay(sessionId, TaskDisplayStatus.STOPPED, event.reason),
                SessionEffect.ReleaseClaim,
                SessionEffect.ClearSteers(sessionId),
                SessionEffect.CommitState,
                SessionEffect.ClearPointer,
                SessionEffect.CompleteRun(sessionId, RunStatus.STOPPED, assistantText = null),
                SessionEffect.AppendEvent(ActivityEventKind.SESSION_STOPPED, event.reason, sessionId),
            ),
        )
    }

    private fun reset(state: SessionState): SessionTransition {
        val sessionId = state.sessionIdOrNull
        return SessionTransition(
            state = SessionState.Idle,
            effects = buildList {
                add(SessionEffect.CancelAllAttention)
                if (sessionId != null) {
                    add(SessionEffect.CancelTransport(sessionId))
                    add(SessionEffect.ClearSteers(sessionId))
                    add(SessionEffect.CompleteRun(sessionId, RunStatus.STOPPED, assistantText = null))
                }
                add(SessionEffect.ClearAllSteers)
                add(SessionEffect.ReleaseClaim)
                add(SessionEffect.ClearPointer)
                add(SessionEffect.ClearToolCalls)
                add(SessionEffect.ClearEvents)
                add(SessionEffect.CommitState)
            },
        )
    }

    private fun fail(state: SessionState, event: SessionEvent.Fail): SessionTransition? {
        val sessionId = state.sessionIdOrNull ?: return null
        if (!state.isActive) return null
        val safeReason = event.reason.trim().take(MAX_AGENT_FEEDBACK_CHARS)
            .ifBlank { "The desktop Codex turn failed." }
        val continuationSettings = state.continuationSettings()
        return SessionTransition(
            state = SessionState.Stopped(
                sessionId = sessionId,
                reason = "Failed: $safeReason",
                conversationId = state.conversationIdOrNull,
                reasoningEffort = continuationSettings.first,
                fastMode = continuationSettings.second,
                request = state.requestOrNull() ?: "",
                workedDurationMs = state.elapsedAt(event.nowEpochMs),
            ),
            effects = listOf(
                SessionEffect.SettleAttention(sessionId),
                SessionEffect.CancelTransport(sessionId),
                SessionEffect.ReleaseClaim,
                SessionEffect.ClearSteers(sessionId),
                SessionEffect.RetainDisplay(sessionId, TaskDisplayStatus.FAILED, safeReason),
                SessionEffect.CommitState,
                SessionEffect.ClearPointer,
                SessionEffect.CompleteRun(sessionId, RunStatus.FAILED, assistantText = null),
                SessionEffect.AppendEvent(
                    ActivityEventKind.AGENT_MESSAGE,
                    "DHD could not complete this request: $safeReason",
                    sessionId,
                ),
            ),
        )
    }

    private fun complete(state: SessionState, event: SessionEvent.Complete): SessionTransition? {
        val sessionId = state.sessionIdOrNull ?: return null
        val feedback = event.agentFeedback
            ?.trim()
            ?.take(MAX_AGENT_FEEDBACK_CHARS)
            ?.ifBlank { null }
        val safeAgentMessageId = event.agentMessageId
            ?.trim()
            ?.take(MAX_TEXT_CHARS)
            ?.ifBlank { null }
        val displayMessage = feedback ?: event.message.trim().take(MAX_TEXT_CHARS).ifBlank { "Session completed." }
        return SessionTransition(
            state = SessionState.Completed(
                sessionId = sessionId,
                message = displayMessage,
                conversationId = state.conversationIdOrNull,
                workedDurationMs = state.elapsedAt(event.nowEpochMs),
            ),
            effects = buildList {
                add(SessionEffect.SettleAttention(sessionId))
                add(SessionEffect.CancelTransport(sessionId))
                add(SessionEffect.RetainDisplay(sessionId, TaskDisplayStatus.COMPLETED, error = null))
                add(SessionEffect.ReleaseClaim)
                add(SessionEffect.ClearSteers(sessionId))
                add(SessionEffect.CommitState)
                add(SessionEffect.ClearPointer)
                // Feedback is emitted as an AGENT_MESSAGE below so the live timeline
                // and the durable timeline share one row. The fallback completion has
                // no separate event, so persist it directly here.
                add(
                    SessionEffect.CompleteRun(
                        sessionId,
                        RunStatus.COMPLETED,
                        assistantText = if (feedback == null) displayMessage else null,
                    ),
                )
                if (feedback != null) {
                    add(
                        SessionEffect.AppendEvent(
                            ActivityEventKind.AGENT_MESSAGE,
                            feedback,
                            sessionId,
                            eventId = safeAgentMessageId,
                        ),
                    )
                }
                add(
                    SessionEffect.AppendEvent(
                        ActivityEventKind.SESSION_COMPLETED,
                        if (feedback != null) "Task completed." else displayMessage,
                        sessionId,
                    ),
                )
            },
        )
    }
}

internal fun SessionState.withStoredConversation(conversationId: String?): SessionState =
    if (conversationId != null && this is SessionState.Running) copy(conversationId = conversationId) else this

private fun SessionState.elapsedAt(nowEpochMs: Long): Long = when (this) {
    is SessionState.Running -> elapsedBeforeStartMs +
        (nowEpochMs - startedAtEpochMs).coerceAtLeast(0L)
    is SessionState.Paused -> elapsedBeforeStartMs
    is SessionState.Stopped -> workedDurationMs
    is SessionState.Completed -> workedDurationMs
    SessionState.Idle -> 0L
}.coerceAtLeast(0L)

private fun SessionState.continuationSettings(): Pair<String, Boolean> = when (this) {
    is SessionState.Running -> reasoningEffort to fastMode
    is SessionState.Paused -> reasoningEffort to fastMode
    else -> ReasoningEffort.default.codexValue to false
}

private fun SessionState.requestOrNull(): String? = when (this) {
    is SessionState.Running -> request
    is SessionState.Paused -> request
    is SessionState.Stopped -> request
    else -> null
}
