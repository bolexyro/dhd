package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.domain.ClickPhase
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.SwipeAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.domain.userFacingActivityLabel
import com.phonecontrol.assistant.bridge.protocol.resultMessage
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.core.conversationIdOrNull
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.data.RunStatus
import com.phonecontrol.assistant.policy.PolicyContext
import com.phonecontrol.assistant.policy.PolicyDecision
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.execution.PhoneActionTransport
import com.phonecontrol.assistant.execution.RejectionCode
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.TransportResult
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlin.random.Random

private val CALIBRATION_ANCHORS = arrayOf(
    0.18f to 0.16f,
    0.82f to 0.16f,
    0.18f to 0.84f,
    0.82f to 0.84f,
)

private data class PendingAttention(
    val sessionId: String,
    val reason: String,
    val completion: CompletableDeferred<AttentionResolution>,
)

/**
 * Process-local session state shared by the Compose activity, foreground
 * service, and desktop Codex bridge. The phone owns the handoff and all typed
 * observation/action policy decisions.
 */
class SessionCoordinator(
    private val enabledPackagesProvider: () -> Set<String>,
    private val policyEngine: PolicyEngine,
    private val transport: PhoneActionTransport,
    private val conversationStore: ConversationStore? = null,
    private val fullAccessProvider: () -> Boolean = { false },
    /** Production DHD wires this true so task calls can never fall back to display 0. */
    private val taskDisplayRequiredProvider: () -> Boolean = { false },
    /** Optional display registry used to retain the live task surface after terminal state. */
    private val taskDisplayBackend: TaskDisplayBackend? = null,
    /** True only when the local phone-action service can accept a request. */
    private val phoneAccessReadyProvider: () -> Boolean = { true },
    /** Called when an automatic phone-access recovery needs user visibility. */
    private val onPhoneAccessAttentionRequested: (String, String?) -> Unit = { _, _ -> },
    /** Called after automatic phone-access recovery is resolved or cancelled. */
    private val onPhoneAccessAttentionResolved: () -> Unit = {},
) {
    private val lock = Any()
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val activityLog = ActivityLog(conversationStore)
    private val _pointerEvent = MutableStateFlow<TaskPointerEvent?>(null)
    private val toolCallLog = ToolCallLog()
    private var sessionJob: Job? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handoff = CompanionHandoff(conversationStore)
    private val steers = SteerQueue()
    private var pendingAttention: PendingAttention? = null
    private val completedAttentions = mutableMapOf<String, AttentionResolution>()

    val state: StateFlow<SessionState> = _state.asStateFlow()
    val events: StateFlow<List<ActivityEvent>> = activityLog.events
    val toolCalls: StateFlow<List<DhdToolCall>> = toolCallLog.toolCalls

    /** Latest task-display pointer feedback for the read-only live preview. */
    val pointerEvent: StateFlow<TaskPointerEvent?> = _pointerEvent.asStateFlow()

    /** Stable owner key used by the phone bridge to choose the task display. */
    fun activeSessionId(): String? = synchronized(lock) {
        when (val current = _state.value) {
            is SessionState.Running -> current.sessionId
            is SessionState.Paused -> current.sessionId
            else -> null
        }
    }

    fun start(
        request: String,
        conversationId: String? = null,
        reasoningEffort: String = ReasoningEffort.default.codexValue,
        fastMode: Boolean = false,
    ): Boolean = synchronized(lock) {
        if (request.isBlank() || _state.value.isActive) return false

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val normalizedReasoningEffort = ReasoningEffort.fromCodexValue(reasoningEffort)?.codexValue
            ?: ReasoningEffort.default.codexValue
        completedAttentions.clear()
        _pointerEvent.value = null
        val startedRun = conversationStore?.startRun(sessionId, request, conversationId)
        handoff.release()
        toolCallLog.clear()
        _state.value = SessionState.Running(
            sessionId = sessionId,
            request = request.trim(),
            currentPurpose = CoordinatorCopy.PREPARING_REQUEST,
            startedAtEpochMs = now,
            conversationId = startedRun?.conversationId ?: conversationId,
            reasoningEffort = normalizedReasoningEffort,
            fastMode = fastMode,
            isContinuation = false,
            elapsedBeforeStartMs = 0L,
        )
        sessionJob?.cancel()
        sessionJob = SupervisorJob()
        appendEvent(
            ActivityEventKind.SESSION_STARTED,
            "Request accepted. Waiting for the desktop Codex bridge.",
            sessionId = sessionId,
        )
        true
    }

    /**
     * Begin a safe, presentation-only record for a dynamic DHD tool call.
     * Arguments, screenshots, and private reasoning are intentionally absent.
     */
    fun beginToolCall(toolName: String, purpose: String? = null): String? = synchronized(lock) {
        val current = _state.value
        val sessionId = current.sessionIdOrNull ?: return@synchronized null
        if (!current.isActive) return@synchronized null

        val call = toolCallLog.begin(sessionId, toolName, purpose)
        setCurrentPurpose(call.purpose, metadataPurpose = call.purpose)
        call.id
    }

    fun finishToolCall(
        callId: String?,
        status: DhdToolCallStatus = DhdToolCallStatus.COMPLETED,
    ): Boolean = synchronized(lock) {
        toolCallLog.finish(callId, status)
    }

    /**
     * Return the active phone request until a desktop companion claims it.
     *
     * Handoff must remain visible even when the local execution prerequisite is
     * unavailable. The action transport keeps the safety boundary by rejecting
     * every phone action before execution until its developer-mode connection
     * is ready.
     */
    fun pendingRequest(): PendingRequest? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        if (handoff.isClaimed(running.sessionId)) return@synchronized null
        handoff.requestFor(running)
    }

    /** Queue a user instruction for the desktop companion's active Codex turn. */
    fun enqueueSteer(text: String): PendingSteer? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        val steer = steers.enqueue(running.sessionId, text) ?: return@synchronized null
        _state.value = running.copy(
            currentPurpose = "Steer queued",
            currentToolMetadataPurpose = null,
        )
        conversationStore?.setCurrentPurpose(running.sessionId, "Steer queued")
        taskDisplayBackend?.updatePurposeForRun(running.sessionId, "Steer queued")
        conversationStore?.recordSteer(steer.steerId, running.sessionId, steer.text)
        appendEvent(
            ActivityEventKind.SYSTEM,
            "Steer instruction queued for Codex.",
            sessionId = running.sessionId,
        )
        steer
    }

    /** Return the next unclaimed steer for the active phone session. */
    fun pendingSteer(expectedSessionId: String? = null): PendingSteer? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        if (expectedSessionId != null && expectedSessionId != running.sessionId) {
            return@synchronized null
        }
        steers.next(running.sessionId)
    }

    /** Atomically claim a steer so multiple desktop pollers cannot deliver it twice. */
    fun claimSteer(
        expectedSessionId: String,
        expectedSteerId: String,
    ): PendingSteer? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        if (expectedSessionId != running.sessionId) return@synchronized null
        steers.claim(running.sessionId, expectedSteerId)
    }

    /** Put a steer back at the front after a transient desktop delivery failure. */
    fun releaseSteer(expectedSessionId: String, steerId: String): Boolean = synchronized(lock) {
        val running = _state.value as? SessionState.Running
        steers.release(expectedSessionId, steerId, running?.sessionId)
    }

    /** Acknowledge that the active Codex client accepted a steer. */
    fun completeSteer(expectedSessionId: String, steerId: String): Boolean = synchronized(lock) {
        steers.complete(expectedSessionId, steerId)
    }

    /**
     * Atomically claim the current phone request. Pollers can pass the session
     * id they observed so a delayed claim cannot attach to a newer request.
     */
    fun claimRequest(expectedSessionId: String? = null): PendingRequest? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        if (expectedSessionId != null && expectedSessionId != running.sessionId) {
            return@synchronized null
        }
        if (handoff.isClaimed(running.sessionId)) return@synchronized null
        handoff.claim(running.sessionId)
        _state.value = running.copy(
            currentPurpose = CoordinatorCopy.DHD_PLANNING,
            currentToolMetadataPurpose = null,
        )
        taskDisplayBackend?.updatePurposeForRun(running.sessionId, CoordinatorCopy.DHD_PLANNING)
        appendEvent(
            ActivityEventKind.SYSTEM,
            "Desktop Codex companion claimed the request.",
            sessionId = running.sessionId,
        )
        handoff.requestFor(running)
    }

    /** Release a claim after a desktop-side failure so the user can retry. */
    fun releaseRequest(sessionId: String): Boolean = synchronized(lock) {
        if (!handoff.isClaimed(sessionId)) return@synchronized false
        val activeSessionId = _state.value.sessionIdOrNull
        if (activeSessionId != sessionId) {
            handoff.release()
            return@synchronized false
        }
        handoff.release()
        if (_state.value is SessionState.Running) {
            val running = _state.value as SessionState.Running
            _state.value = running.copy(
                currentPurpose = CoordinatorCopy.WAITING_FOR_COMPANION,
                currentToolMetadataPurpose = null,
            )
            taskDisplayBackend?.updatePurposeForRun(sessionId, CoordinatorCopy.WAITING_FOR_COMPANION)
        }
        appendEvent(
            ActivityEventKind.SYSTEM,
            "Desktop Codex companion released the request; waiting for retry.",
            sessionId = sessionId,
        )
        true
    }

    fun pause(): Boolean = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return false
        val now = System.currentTimeMillis()
        _state.value = SessionState.Paused(
            sessionId = running.sessionId,
            request = running.request,
            currentPurpose = running.currentPurpose,
            currentToolMetadataPurpose = running.currentToolMetadataPurpose,
            startedAtEpochMs = now,
            conversationId = running.conversationId,
            reasoningEffort = running.reasoningEffort,
            fastMode = running.fastMode,
            isContinuation = running.isContinuation,
            attentionReason = running.attentionReason,
            attentionActionLabel = running.attentionActionLabel,
            elapsedBeforeStartMs = running.elapsedAt(now),
        )
        conversationStore?.setRunStatus(running.sessionId, RunStatus.PAUSED)
        cleanupScope.launch {
            transport.updateSessionDisplayStatusForRun(running.sessionId, TaskDisplayStatus.PAUSED)
        }
        appendEvent(ActivityEventKind.SESSION_PAUSED, "Session paused.", running.sessionId)
        true
    }

    fun resume(): Boolean = synchronized(lock) {
        val paused = _state.value as? SessionState.Paused ?: return false
        val now = System.currentTimeMillis()
        _state.value = SessionState.Running(
            sessionId = paused.sessionId,
            request = paused.request,
            currentPurpose = paused.currentPurpose,
            currentToolMetadataPurpose = paused.currentToolMetadataPurpose,
            startedAtEpochMs = now,
            conversationId = paused.conversationId,
            reasoningEffort = paused.reasoningEffort,
            fastMode = paused.fastMode,
            isContinuation = paused.isContinuation,
            attentionReason = paused.attentionReason,
            attentionActionLabel = paused.attentionActionLabel,
            elapsedBeforeStartMs = paused.elapsedBeforeStartMs,
        )
        conversationStore?.setRunStatus(paused.sessionId, RunStatus.RUNNING)
        cleanupScope.launch {
            transport.updateSessionDisplayStatusForRun(paused.sessionId, TaskDisplayStatus.RUNNING)
        }
        appendEvent(ActivityEventKind.SESSION_RESUMED, "Session resumed.", paused.sessionId)
        true
    }

    fun togglePause(): Boolean = when (_state.value) {
        is SessionState.Running -> pause()
        is SessionState.Paused -> resume()
        else -> false
    }

    /** Start a hidden continuation turn in the stopped run's persisted conversation. */
    fun continueStopped(): Boolean = synchronized(lock) {
        val stopped = _state.value as? SessionState.Stopped ?: return@synchronized false
        if (_state.value.isActive) return@synchronized false

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val startedRun = conversationStore?.startContinuationRun(
            runId = sessionId,
            requestedConversationId = stopped.conversationId,
        )
        completedAttentions.clear()
        _pointerEvent.value = null
        handoff.release()
        _state.value = SessionState.Running(
            sessionId = sessionId,
            request = stopped.request,
            currentPurpose = "Preparing continuation",
            startedAtEpochMs = now,
            conversationId = startedRun?.conversationId ?: stopped.conversationId,
            reasoningEffort = stopped.reasoningEffort,
            fastMode = stopped.fastMode,
            isContinuation = true,
            elapsedBeforeStartMs = stopped.workedDurationMs,
        )
        sessionJob?.cancel()
        sessionJob = SupervisorJob()
        appendEvent(
            ActivityEventKind.SESSION_STARTED,
            "Continuation accepted. Waiting for the desktop Codex bridge.",
            sessionId = sessionId,
        )
        true
    }

    fun stop(reason: String = "Stopped by the user."): Boolean = synchronized(lock) {
        val current = _state.value
        val sessionId = current.sessionIdOrNull ?: return false
        val now = System.currentTimeMillis()
        val workedDurationMs = current.elapsedAt(now)
        cancelPendingAttentionLocked()
        completedAttentions.remove(sessionId)
        sessionJob?.cancel()
        sessionJob = null
        transport.cancelSessionForRun(sessionId)
        cleanupScope.launch {
            transport.retainSessionForRun(sessionId, TaskDisplayStatus.STOPPED, reason)
        }
        handoff.release()
        steers.clear(sessionId)
        val conversationId = current.conversationIdOrNull
        val continuationSettings = current.continuationSettings()
        _state.value = SessionState.Stopped(
            sessionId = sessionId,
            reason = reason,
            conversationId = conversationId,
            reasoningEffort = continuationSettings.first,
            fastMode = continuationSettings.second,
            request = current.requestOrNull() ?: "",
            workedDurationMs = workedDurationMs,
        )
        _pointerEvent.value = null
        conversationStore?.completeRun(sessionId, RunStatus.STOPPED)
        appendEvent(ActivityEventKind.SESSION_STOPPED, reason, sessionId)
        true
    }

    /**
     * Terminate any active or stopped session and reset coordinator state to Idle.
     * Clears all steer instructions, tool calls, pointer events, and timeline events.
     */
    fun reset(): Boolean = synchronized(lock) {
        val current = _state.value
        val sessionId = current.sessionIdOrNull
        cancelPendingAttentionLocked()
        completedAttentions.clear()
        sessionJob?.cancel()
        sessionJob = null
        if (sessionId != null) {
            transport.cancelSessionForRun(sessionId)
            steers.clear(sessionId)
            conversationStore?.completeRun(sessionId, RunStatus.STOPPED)
        }
        steers.clearAll()
        handoff.release()
        _pointerEvent.value = null
        toolCallLog.clear()
        activityLog.clear()
        _state.value = SessionState.Idle
        true
    }

    /**
     * End a run after a provider/bridge failure. This is intentionally distinct
     * from releaseRequest: a failed Codex turn must not be picked up and
     * replayed indefinitely by the polling companion.
     */
    fun fail(reason: String = "The desktop Codex turn failed."): Boolean = synchronized(lock) {
        val current = _state.value
        val sessionId = current.sessionIdOrNull ?: return false
        if (!current.isActive) return false
        val now = System.currentTimeMillis()
        val workedDurationMs = current.elapsedAt(now)
        cancelPendingAttentionLocked()
        completedAttentions.remove(sessionId)
        sessionJob?.cancel()
        sessionJob = null
        transport.cancelSessionForRun(sessionId)
        handoff.release()
        steers.clear(sessionId)
        val safeReason = reason.trim().take(MAX_AGENT_FEEDBACK_CHARS)
            .ifBlank { "The desktop Codex turn failed." }
        cleanupScope.launch {
            transport.retainSessionForRun(sessionId, TaskDisplayStatus.FAILED, safeReason)
        }
        val conversationId = current.conversationIdOrNull
        val continuationSettings = current.continuationSettings()
        _state.value = SessionState.Stopped(
            sessionId = sessionId,
            reason = "Failed: $safeReason",
            conversationId = conversationId,
            reasoningEffort = continuationSettings.first,
            fastMode = continuationSettings.second,
            request = current.requestOrNull() ?: "",
            workedDurationMs = workedDurationMs,
        )
        _pointerEvent.value = null
        conversationStore?.completeRun(
            sessionId,
            RunStatus.FAILED,
            assistantText = null,
        )
        appendEvent(ActivityEventKind.AGENT_MESSAGE, "DHD could not complete this request: $safeReason", sessionId)
        true
    }

    /**
     * Record a user-facing message from the desktop agent without exposing its
     * private reasoning stream. The message is kept in the local conversation
     * timeline and becomes the completed-session result.
     */
    fun complete(
        message: String = "Session completed.",
        agentFeedback: String? = null,
        agentMessageId: String? = null,
    ): Boolean = synchronized(lock) {
        val current = _state.value
        val sessionId = current.sessionIdOrNull ?: return false
        val workedDurationMs = current.elapsedAt(System.currentTimeMillis())
        cancelPendingAttentionLocked()
        completedAttentions.remove(sessionId)
        val feedback = agentFeedback
            ?.trim()
            ?.take(MAX_AGENT_FEEDBACK_CHARS)
            ?.ifBlank { null }
        val safeAgentMessageId = agentMessageId
            ?.trim()
            ?.take(MAX_TEXT_CHARS)
            ?.ifBlank { null }
        val displayMessage = feedback ?: message.trim().take(MAX_TEXT_CHARS).ifBlank { "Session completed." }
        sessionJob?.cancel()
        sessionJob = null
        transport.cancelSessionForRun(sessionId)
        cleanupScope.launch {
            transport.retainSessionForRun(sessionId, TaskDisplayStatus.COMPLETED)
        }
        handoff.release()
        val conversationId = current.conversationIdOrNull
        current.sessionIdOrNull?.let(steers::clear)
        _state.value = SessionState.Completed(
            sessionId = sessionId,
            message = displayMessage,
            conversationId = conversationId,
            workedDurationMs = workedDurationMs,
        )
        _pointerEvent.value = null
        // Feedback is emitted as an AGENT_MESSAGE below so the live timeline
        // and the durable timeline share one row. The fallback completion has
        // no separate event, so persist it directly here.
        conversationStore?.completeRun(
            sessionId,
            RunStatus.COMPLETED,
            assistantText = if (feedback == null) displayMessage else null,
        )
        if (feedback != null) {
            appendEvent(
                ActivityEventKind.AGENT_MESSAGE,
                feedback,
                sessionId,
                eventId = safeAgentMessageId,
            )
        }
        appendEvent(
            ActivityEventKind.SESSION_COMPLETED,
            if (feedback != null) "Task completed." else displayMessage,
            sessionId,
        )
        true
    }

    /** Update the assistant message that is visible while Codex emits deltas. */
    fun streamAgentMessage(sessionId: String, messageId: String, text: String): Boolean = synchronized(lock) {
        val current = _state.value
        if (current.sessionIdOrNull != sessionId || !current.isActive) return@synchronized false
        val safeMessageId = messageId.trim().take(MAX_TEXT_CHARS).ifBlank { return@synchronized false }
        val safeText = text.replace(Regex("\\r\\n?"), "\n").take(MAX_AGENT_FEEDBACK_CHARS)
        if (safeText.isBlank()) return@synchronized false
        conversationStore?.upsertAgentMessage(sessionId, safeMessageId, safeText) ?: true
    }

    /** Mark that the user should review the phone without launching an Activity. */
    fun requestAttention(reason: String): Boolean = requestAttentionWaiter(reason) != null

    /**
     * Register an attention request and return its completion handle atomically.
     * The bridge keeps this handle before showing the notification so a very
     * fast Done tap cannot race with a later lookup of pendingAttention.
     */
    fun requestAttentionWaiter(
        reason: String,
        actionLabel: String = DEFAULT_ATTENTION_ACTION_LABEL,
    ): CompletableDeferred<AttentionResolution>? = synchronized(lock) {
        val current = _state.value
        val sessionId = current.sessionIdOrNull ?: return@synchronized null
        if (current !is SessionState.Running && current !is SessionState.Paused) {
            return@synchronized null
        }
        if (pendingAttention != null) return@synchronized null
        val message = reason.trim().take(MAX_TEXT_CHARS).ifBlank { "The phone assistant needs your attention." }
        val safeActionLabel = actionLabel.trim().take(MAX_TEXT_CHARS)
            .ifBlank { DEFAULT_ATTENTION_ACTION_LABEL }
        val completion = CompletableDeferred<AttentionResolution>()
        completedAttentions.remove(sessionId)
        pendingAttention = PendingAttention(sessionId, message, completion)
        val updated = when (current) {
            is SessionState.Running -> current.copy(
                currentPurpose = CoordinatorCopy.NEEDS_ATTENTION,
                currentToolMetadataPurpose = null,
                attentionReason = message,
                attentionActionLabel = safeActionLabel,
            )
            is SessionState.Paused -> current.copy(
                currentPurpose = CoordinatorCopy.NEEDS_ATTENTION,
                currentToolMetadataPurpose = null,
                attentionReason = message,
                attentionActionLabel = safeActionLabel,
            )
            else -> return@synchronized null
        }
        _state.value = updated
        conversationStore?.setCurrentPurpose(sessionId, CoordinatorCopy.NEEDS_ATTENTION)
        taskDisplayBackend?.updatePurposeForRun(sessionId, CoordinatorCopy.NEEDS_ATTENTION)
        cleanupScope.launch {
            transport.updateSessionDisplayStatusForRun(sessionId, TaskDisplayStatus.PAUSED)
        }
        appendEvent(ActivityEventKind.ATTENTION_REQUIRED, message, sessionId)
        completion
    }

    /** True while the Codex turn is waiting for the user to finish the step. */
    fun attentionPending(): Boolean = synchronized(lock) { pendingAttention != null }

    /** Suspend the bridge request until the phone user acknowledges or stops the run. */
    suspend fun awaitAttention(sessionId: String): AttentionResolution =
        synchronized(lock) {
            pendingAttention
                ?.takeIf { it.sessionId == sessionId }
                ?.completion
                ?: completedAttentions.remove(sessionId)?.let { resolution ->
                    CompletableDeferred<AttentionResolution>().apply { complete(resolution) }
                }
        }?.await() ?: AttentionResolution.Cancelled

    /** Complete the blocking attention tool from the DHD UI's Done button. */
    fun acknowledgeAttention(automatic: Boolean = false): Boolean = synchronized(lock) {
        val pending = pendingAttention ?: return@synchronized false
        val current = _state.value
        if (current.sessionIdOrNull != pending.sessionId || !current.isActive) {
            cancelPendingAttentionLocked()
            return@synchronized false
        }
        pendingAttention = null
        _state.value = when (current) {
            is SessionState.Running -> current.copy(
                currentPurpose = CoordinatorCopy.DHD_PLANNING,
                currentToolMetadataPurpose = null,
                attentionReason = null,
                attentionActionLabel = null,
            )
            is SessionState.Paused -> current.copy(
                currentPurpose = "Paused",
                currentToolMetadataPurpose = null,
                attentionReason = null,
                attentionActionLabel = null,
            )
            else -> current
        }
        val resumedPurpose = when (val after = _state.value) {
            is SessionState.Running -> after.currentPurpose
            is SessionState.Paused -> after.currentPurpose
            else -> CoordinatorCopy.DHD_PLANNING
        }
        val resumedDisplayStatus = if (_state.value is SessionState.Paused) {
            TaskDisplayStatus.PAUSED
        } else {
            TaskDisplayStatus.RUNNING
        }
        conversationStore?.setCurrentPurpose(pending.sessionId, resumedPurpose)
        taskDisplayBackend?.updatePurposeForRun(pending.sessionId, resumedPurpose)
        cleanupScope.launch {
            transport.updateSessionDisplayStatusForRun(pending.sessionId, resumedDisplayStatus)
        }
        appendEvent(
            ActivityEventKind.SYSTEM,
            if (automatic) {
                "Phone access was restored; DHD resumed the paused phone action."
            } else {
                "The user completed the requested attention step."
            },
            pending.sessionId,
        )
        completedAttentions[pending.sessionId] = AttentionResolution.Acknowledged
        pending.completion.complete(AttentionResolution.Acknowledged)
        true
    }

    /**
     * Keep a phone-dependent tool call open while the user restores DHD's
     * phone access. This deliberately resolves automatically when the local
     * service becomes ready; the model must never receive a normal tool error
     * that it can absorb and turn into a misleading completed response.
     */
    suspend fun awaitPhoneAccessForTool(
        reason: String = PHONE_ACCESS_RECOVERY_MESSAGE,
    ): Boolean {
        val sessionId = activeSessionId() ?: return false
        while (true) {
            if (!sessionStillActive(sessionId)) return false
            if (phoneAccessReadyProvider()) return true

            val attention = requestAttentionWaiter(
                reason = reason,
                actionLabel = PHONE_ACCESS_INSTRUCTIONS_ACTION_LABEL,
            )
            if (attention == null) {
                if (!attentionPending()) return false
                if (awaitAttention(sessionId) == AttentionResolution.Cancelled) return false
                continue
            }

            val conversationId = synchronized(lock) {
                _state.value.conversationIdOrNull
            }
            runCatching {
                onPhoneAccessAttentionRequested(reason, conversationId)
            }
            try {
                while (sessionStillActive(sessionId)) {
                    if (phoneAccessReadyProvider()) {
                        if (acknowledgeAttention(automatic = true) || phoneAccessReadyProvider()) {
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
            if (!sessionStillActive(sessionId)) return false
        }
    }

    private fun cancelPendingAttentionLocked() {
        val pending = pendingAttention ?: return
        pendingAttention = null
        completedAttentions[pending.sessionId] = AttentionResolution.Cancelled
        pending.completion.complete(AttentionResolution.Cancelled)
    }

    fun setCurrentPurpose(purpose: String, metadataPurpose: String? = null): Boolean = synchronized(lock) {
        val displayPurpose = userFacingActivityLabel(actionType = null, purpose = purpose)
        val safeMetadataPurpose = metadataPurpose
            ?.trim()
            ?.take(MAX_TEXT_CHARS)
            ?.takeIf(String::isNotBlank)
        val current = _state.value
        val updated = when (current) {
            is SessionState.Running -> current.copy(
                currentPurpose = displayPurpose,
                currentToolMetadataPurpose = safeMetadataPurpose,
            )
            is SessionState.Paused -> current.copy(
                currentPurpose = displayPurpose,
                currentToolMetadataPurpose = safeMetadataPurpose,
            )
            else -> return false
        }
        _state.value = updated
        current.sessionIdOrNull?.let { conversationStore?.setCurrentPurpose(it, displayPurpose) }
        current.sessionIdOrNull?.let { taskDisplayBackend?.updatePurposeForRun(it, displayPurpose) }
        true
    }

    fun bindCodexThread(conversationId: String, codexThreadId: String): Boolean {
        if (conversationStore == null || conversationId.isBlank() || codexThreadId.isBlank()) return false
        conversationStore.bindCodexThread(conversationId, codexThreadId)
        return true
    }

    /** Record a safe purpose-bearing operation such as a fresh screen observation. */
    fun recordPurpose(
        purpose: String,
        targetDescription: String? = null,
        toolName: String? = null,
    ): Boolean = synchronized(lock) {
        val sessionId = _state.value.sessionIdOrNull ?: return@synchronized false
        if (!_state.value.isActive) return@synchronized false
        val safePurpose = userFacingActivityLabel(actionType = null, purpose = purpose)
            .take(MAX_TEXT_CHARS)
            .ifBlank { return@synchronized false }
        val current = _state.value
        _state.value = when (current) {
            is SessionState.Running -> current.copy(
                currentPurpose = safePurpose,
                currentToolMetadataPurpose = safePurpose,
            )
            is SessionState.Paused -> current.copy(
                currentPurpose = safePurpose,
                currentToolMetadataPurpose = safePurpose,
            )
            else -> current
        }
        conversationStore?.setCurrentPurpose(sessionId, safePurpose)
        taskDisplayBackend?.updatePurposeForRun(sessionId, safePurpose)
        appendEvent(
            ActivityEventKind.SYSTEM,
            safePurpose,
            sessionId = sessionId,
            toolName = toolName,
            purpose = safePurpose,
            targetDescription = targetDescription?.trim()?.take(MAX_TEXT_CHARS),
        )
        true
    }

    /** Policy and transport integration point for the Codex bridge. */
    suspend fun executeAction(
        action: PhoneAction,
        observation: ObservationSnapshot?,
        toolName: String? = null,
        targetDisplay: TaskDisplaySession? = null,
    ): ActionExecutionResult {
        val running = synchronized(lock) { _state.value as? SessionState.Running }
            ?: return ActionExecutionResult.SessionNotRunning
        val targetSessionKey = targetDisplay?.sessionKey ?: running.sessionId
        if (taskDisplayRequiredProvider()) {
            val observationMatchesTask = observation?.taskSessionKey == targetSessionKey &&
                (targetDisplay == null || observation.displayId == targetDisplay.displayId)
            if ((observation != null && !observationMatchesTask) ||
                (observation == null && action !is com.phonecontrol.assistant.domain.OpenAppAction)
            ) {
                return ActionExecutionResult.PolicyRejected(
                    message = "The action must use the active task display; the physical display was not touched.",
                    details = StaleObservationDiagnostics(
                        approvedObservationId = action.metadata.observationId,
                        currentObservationId = observation?.id,
                        reasons = listOf(
                            com.phonecontrol.assistant.domain.StaleObservationReason(
                                code = com.phonecontrol.assistant.domain.StaleObservationReasonCode.TASK_SESSION_CHANGED,
                                approved = observation?.taskSessionKey,
                                current = targetSessionKey,
                            ),
                        ),
                    ),
                )
            }
        }
        if (observation?.screenProtection?.requiresUserAttention == true &&
            action !is com.phonecontrol.assistant.domain.OpenAppAction
        ) {
            val message = observation.screenProtection.reason
                ?: "The current task screen is protected; ask the user to complete it before continuing."
            appendEvent(
                ActivityEventKind.ACTION_FAILED,
                message,
                sessionId = running.sessionId,
                actionType = action.type,
                toolName = toolName,
                purpose = action.metadata.purpose,
                observationId = action.metadata.observationId,
                targetDescription = action.metadata.targetDescription,
            )
            return ActionExecutionResult.PolicyRejected(
                message = "$message Call dhd_request_attention and wait for the user's Done acknowledgement.",
                code = "SECURE_SCREEN_REQUIRES_USER",
            )
        }
        val displayPurpose = userFacingActivityLabel(
            actionType = action.type,
            purpose = action.metadata.purpose,
            targetDescription = action.metadata.targetDescription,
        )
        setCurrentPurpose(displayPurpose, metadataPurpose = action.metadata.purpose)
        appendEvent(
            ActivityEventKind.ACTION_PROPOSED,
            // Keep the provider's metadata purpose as the activity label. The
            // human-readable current-purpose status may still use displayPurpose.
            action.metadata.purpose,
            sessionId = running.sessionId,
            actionType = action.type,
            toolName = toolName,
            purpose = action.metadata.purpose,
            observationId = action.metadata.observationId,
            targetDescription = action.metadata.targetDescription,
        )

        val decision = policyEngine.evaluate(
            action,
            PolicyContext(
                enabledPackages = enabledPackagesProvider(),
                foregroundPackage = observation?.packageName,
                currentObservationId = observation?.id,
                fullAccess = fullAccessProvider(),
            ),
        )
        when (decision) {
            PolicyDecision.Allowed -> Unit
            is PolicyDecision.Denied -> {
                appendEvent(
                    ActivityEventKind.ACTION_FAILED,
                    decision.message,
                    sessionId = running.sessionId,
                    actionType = action.type,
                    toolName = toolName,
                    purpose = action.metadata.purpose,
                    observationId = action.metadata.observationId,
                    targetDescription = action.metadata.targetDescription,
                )
                return ActionExecutionResult.PolicyRejected(decision.message, decision.details)
            }
        }

        appendEvent(
            ActivityEventKind.ACTION_STARTED,
            "Executing ${action.type.name.lowercase().replace('_', ' ')}",
            sessionId = running.sessionId,
            actionType = action.type,
            toolName = toolName,
            purpose = action.metadata.purpose,
            observationId = action.metadata.observationId,
            targetDescription = action.metadata.targetDescription,
        )
        val tapAction = action as? TapAction
        val stillActiveBeforeDispatch = synchronized(lock) {
            _state.value.sessionIdOrNull == running.sessionId && _state.value.isActive
        }
        if (!stillActiveBeforeDispatch) return ActionExecutionResult.SessionNotRunning

        var clickPressPublished = false
        val beforeInput = if (tapAction != null && observation != null) {
            {
                if (!clickPressPublished) {
                    clickPressPublished = true
                    publishPointerEvent(
                        sessionId = running.sessionId,
                        action = tapAction,
                        observation = observation,
                        clickPhase = ClickPhase.PRESSED,
                    )
                }
            }
        } else {
            null
        }
        val onPointerMove = when {
            tapAction != null && observation != null -> {
                {
                    publishPointerEvent(
                        sessionId = running.sessionId,
                        action = tapAction,
                        observation = observation,
                        clickPhase = ClickPhase.MOVING,
                    )
                }
            }

            action is SwipeAction && observation != null -> {
                {
                    publishPointerEvent(
                        sessionId = running.sessionId,
                        action = action,
                        observation = observation,
                    )
                }
            }

            else -> null
        }
        var result = transport.executeForSession(
            targetSessionKey,
            action,
            observation,
            beforeInput,
            onPointerMove,
        )
        var phoneAccessRecoveryAttempts = 0
        while (
            result is TransportResult.Rejected &&
                result.code == RejectionCode.DEVELOPER_MODE_UNAVAILABLE &&
                phoneAccessRecoveryAttempts < MAX_PHONE_ACCESS_RECOVERY_ATTEMPTS
        ) {
            phoneAccessRecoveryAttempts += 1
            if (!awaitPhoneAccessForTool()) {
                val stillActiveAfterRecovery = synchronized(lock) {
                    _state.value.sessionIdOrNull == running.sessionId && _state.value.isActive
                }
                if (!stillActiveAfterRecovery) return ActionExecutionResult.SessionNotRunning
                break
            }
            result = transport.executeForSession(
                targetSessionKey,
                action,
                observation,
                beforeInput,
                onPointerMove,
            )
        }
        val stillActive = synchronized(lock) {
            _state.value.sessionIdOrNull == running.sessionId && _state.value.isActive
        }
        if (!stillActive) return ActionExecutionResult.SessionNotRunning
        val eventKind = if (result is TransportResult.Succeeded) {
            ActivityEventKind.ACTION_SUCCEEDED
        } else {
            ActivityEventKind.ACTION_FAILED
        }
        appendEvent(
            eventKind,
            result.resultMessage(),
            sessionId = running.sessionId,
            actionType = action.type,
            toolName = toolName,
            purpose = action.metadata.purpose,
            observationId = action.metadata.observationId,
            targetDescription = action.metadata.targetDescription,
        )
        return ActionExecutionResult.TransportFinished(result)
    }

    /** Publish visual feedback for a gesture or one phase of a click. */
    private fun publishPointerEvent(
        sessionId: String,
        action: PhoneAction,
        observation: ObservationSnapshot?,
        clickPhase: ClickPhase = ClickPhase.PRESSED,
    ) = synchronized(lock) {
        val current = _state.value
        if (current.sessionIdOrNull != sessionId || !current.isActive || observation == null) {
            return@synchronized
        }
        val sequence = (_pointerEvent.value?.sequence ?: 0L) + 1L
        val nextEvent = when (action) {
            is TapAction -> TaskPointerEvent.Click(
                sequence = sequence,
                sessionId = sessionId,
                x = action.x,
                y = action.y,
                displayWidth = observation.width,
                displayHeight = observation.height,
                phase = clickPhase,
            )

            is SwipeAction -> TaskPointerEvent.Swipe(
                sequence = sequence,
                sessionId = sessionId,
                startX = action.startX,
                startY = action.startY,
                endX = action.endX,
                endY = action.endY,
                durationMs = action.durationMs,
                displayWidth = observation.width,
                displayHeight = observation.height,
            )

            else -> return@synchronized
        }
        _pointerEvent.value = nextEvent
    }

    /**
     * Publish the initial calibration cursor for a freshly opened task
     * display. This is presentation metadata only; it does not dispatch an
     * input action or alter the observation.
     */
    fun publishCalibrationPointerEvent(
        observation: ObservationSnapshot,
    ): TaskPointerEvent.Calibration? = synchronized(lock) {
        val current = _state.value
        val sessionId = current.sessionIdOrNull ?: return@synchronized null
        if (!current.isActive || observation.width <= 0 || observation.height <= 0) {
            return@synchronized null
        }

        val (xRatio, yRatio) = CALIBRATION_ANCHORS[Random.nextInt(CALIBRATION_ANCHORS.size)]
        val nextEvent = TaskPointerEvent.Calibration(
            sequence = (_pointerEvent.value?.sequence ?: 0L) + 1L,
            sessionId = sessionId,
            x = (observation.width * xRatio).roundToInt().coerceIn(0, observation.width - 1),
            y = (observation.height * yRatio).roundToInt().coerceIn(0, observation.height - 1),
            displayWidth = observation.width,
            displayHeight = observation.height,
        )
        _pointerEvent.value = nextEvent
        nextEvent
    }

    fun close() {
        sessionJob?.cancel()
        sessionJob = null
        val sessionId = synchronized(lock) { _state.value.sessionIdOrNull }
        if (sessionId != null) {
            transport.cancelSessionForRun(sessionId)
            cleanupScope.launch {
                transport.retainSessionForRun(sessionId, TaskDisplayStatus.STOPPED, "Session closed.")
            }
        }
        synchronized(lock) {
            cancelPendingAttentionLocked()
            completedAttentions.clear()
            steers.clearAll()
        }
    }

    private fun sessionStillActive(sessionId: String): Boolean = synchronized(lock) {
        _state.value.sessionIdOrNull == sessionId && _state.value.isActive
    }

    private fun appendEvent(
        kind: ActivityEventKind,
        message: String,
        sessionId: String? = _state.value.sessionIdOrNull,
        actionType: com.phonecontrol.assistant.domain.ActionType? = null,
        toolName: String? = null,
        purpose: String? = null,
        observationId: String? = null,
        targetDescription: String? = null,
        eventId: String? = null,
    ) = activityLog.append(
        kind = kind,
        message = message,
        sessionId = sessionId,
        actionType = actionType,
        toolName = toolName,
        purpose = purpose,
        observationId = observationId,
        targetDescription = targetDescription,
        eventId = eventId,
    )

    private companion object {
        const val MAX_TEXT_CHARS = 240
        const val MAX_AGENT_FEEDBACK_CHARS = 4_000
        const val MAX_PHONE_ACCESS_RECOVERY_ATTEMPTS = 3
        const val PHONE_ACCESS_STATUS_POLL_INTERVAL_MS = 500L
        const val DEFAULT_ATTENTION_ACTION_LABEL = "Done"
        const val PHONE_ACCESS_INSTRUCTIONS_ACTION_LABEL = CoordinatorCopy.VIEW_INSTRUCTIONS
        const val PHONE_ACCESS_RECOVERY_MESSAGE =
            "DHD paused this task because it needs phone access. Turn on Wi-Fi and Wireless debugging in Android Settings, then return to DHD. Your phone action has not been sent."
    }
}

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
