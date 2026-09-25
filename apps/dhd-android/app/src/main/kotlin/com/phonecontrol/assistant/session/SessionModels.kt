package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.execution.TransportResult

sealed interface SessionState {
    data object Idle : SessionState

    data class Running(
        val sessionId: String,
        val request: String,
        val currentPurpose: String,
        val currentToolMetadataPurpose: String? = null,
        val startedAtEpochMs: Long,
        val conversationId: String? = null,
        val reasoningEffort: String = ReasoningEffort.default.codexValue,
        val fastMode: Boolean = false,
        val isContinuation: Boolean = false,
        val attentionReason: String? = null,
        val attentionActionLabel: String? = null,
        /** Active time accumulated before this currently running segment. */
        val elapsedBeforeStartMs: Long = 0L,
    ) : SessionState

    data class Paused(
        val sessionId: String,
        val request: String,
        val currentPurpose: String,
        val currentToolMetadataPurpose: String? = null,
        val startedAtEpochMs: Long,
        val conversationId: String? = null,
        val reasoningEffort: String = ReasoningEffort.default.codexValue,
        val fastMode: Boolean = false,
        val isContinuation: Boolean = false,
        val attentionReason: String? = null,
        val attentionActionLabel: String? = null,
        /** Active time accumulated before the currently paused segment. */
        val elapsedBeforeStartMs: Long = 0L,
    ) : SessionState

    data class Stopped(
        val sessionId: String,
        val reason: String,
        val conversationId: String? = null,
        val reasoningEffort: String = ReasoningEffort.default.codexValue,
        val fastMode: Boolean = false,
        val request: String = "",
        /** Total active time at the moment this run was stopped. */
        val workedDurationMs: Long = 0L,
    ) : SessionState

    data class Completed(
        val sessionId: String,
        val message: String,
        val conversationId: String? = null,
        /** Total active time across the task's run and any continuations. */
        val workedDurationMs: Long = 0L,
    ) : SessionState
}

sealed interface ActionExecutionResult {
    data object SessionNotRunning : ActionExecutionResult
    data class PolicyRejected(
        val message: String,
        val details: StaleObservationDiagnostics? = null,
        val code: String = "POLICY_REJECTED",
    ) : ActionExecutionResult
    data class TransportFinished(val result: TransportResult) : ActionExecutionResult
}

/**
 * A phone-originated request waiting for the desktop Codex companion to
 * accept it. The session id makes the handoff idempotent across polling and
 * prevents a stale desktop response from being applied to a newer session.
 */
data class PendingRequest(
    val sessionId: String,
    val request: String,
    val conversationId: String? = null,
    val codexThreadId: String? = null,
    val reasoningEffort: String = ReasoningEffort.default.codexValue,
    val fastMode: Boolean = false,
    val isContinuation: Boolean = false,
)

/** A user instruction waiting to be appended to the active Codex turn. */
data class PendingSteer(
    val steerId: String,
    val sessionId: String,
    val text: String,
)

sealed interface AttentionResolution {
    data object Acknowledged : AttentionResolution
    data object Cancelled : AttentionResolution
}
