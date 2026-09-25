package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.domain.ClickPhase
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.domain.userFacingActivityLabel
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.core.conversationIdOrNull
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.execution.PhoneActionTransport
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val pointerFeedback = PointerFeedback()
    private val toolCallLog = ToolCallLog()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handoff = CompanionHandoff(conversationStore)
    private val steers = SteerQueue()
    private val attentionGate = AttentionGate()
    private val phoneAccessRecovery = PhoneAccessRecovery(
        host = object : PhoneAccessRecoveryHost {
            override fun activeSessionId(): String? = this@SessionCoordinator.activeSessionId()

            override fun isSessionActive(sessionId: String): Boolean = sessionStillActive(sessionId)

            override fun requestAttentionWaiter(
                reason: String,
                actionLabel: String,
            ): CompletableDeferred<AttentionResolution>? =
                this@SessionCoordinator.requestAttentionWaiter(reason, actionLabel)

            override fun attentionPending(): Boolean = this@SessionCoordinator.attentionPending()

            override suspend fun awaitAttention(sessionId: String): AttentionResolution =
                this@SessionCoordinator.awaitAttention(sessionId)

            override fun acknowledgeAttention(automatic: Boolean): Boolean =
                this@SessionCoordinator.acknowledgeAttention(automatic)

            override fun conversationId(): String? = synchronized(lock) {
                _state.value.conversationIdOrNull
            }
        },
        phoneAccessReadyProvider = phoneAccessReadyProvider,
        onPhoneAccessAttentionRequested = onPhoneAccessAttentionRequested,
        onPhoneAccessAttentionResolved = onPhoneAccessAttentionResolved,
    )
    private val actionPipeline = ActionPipeline(
        host = object : ActionPipelineHost {
            override fun runningSession(): SessionState.Running? = synchronized(lock) {
                _state.value as? SessionState.Running
            }

            override fun isSessionActive(sessionId: String): Boolean = sessionStillActive(sessionId)

            override fun setCurrentPurpose(purpose: String, metadataPurpose: String?) {
                this@SessionCoordinator.setCurrentPurpose(purpose, metadataPurpose)
            }

            override fun publishPointer(
                sessionId: String,
                action: PhoneAction,
                observation: ObservationSnapshot?,
                clickPhase: ClickPhase,
            ) = publishPointerEvent(sessionId, action, observation, clickPhase)

            override suspend fun awaitPhoneAccess(): Boolean = awaitPhoneAccessForTool()
        },
        activityLog = activityLog,
        policyEngine = policyEngine,
        transport = transport,
        enabledPackagesProvider = enabledPackagesProvider,
        fullAccessProvider = fullAccessProvider,
        taskDisplayRequiredProvider = taskDisplayRequiredProvider,
    )

    val state: StateFlow<SessionState> = _state.asStateFlow()
    val events: StateFlow<List<ActivityEvent>> = activityLog.events
    val toolCalls: StateFlow<List<DhdToolCall>> = toolCallLog.toolCalls

    /** Latest task-display pointer feedback for the read-only live preview. */
    val pointerEvent: StateFlow<TaskPointerEvent?> = pointerFeedback.pointerEvent

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
        transition(
            SessionEvent.Start(
                sessionId = UUID.randomUUID().toString(),
                request = request,
                conversationId = conversationId,
                reasoningEffort = reasoningEffort,
                fastMode = fastMode,
                nowEpochMs = System.currentTimeMillis(),
            ),
        )
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
        transition(SessionEvent.Pause(System.currentTimeMillis()))
    }

    fun resume(): Boolean = synchronized(lock) {
        transition(SessionEvent.Resume(System.currentTimeMillis()))
    }

    /** Start a hidden continuation turn in the stopped run's persisted conversation. */
    fun continueStopped(): Boolean = synchronized(lock) {
        transition(
            SessionEvent.ContinueStopped(
                sessionId = UUID.randomUUID().toString(),
                nowEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    fun stop(reason: String = "Stopped by the user."): Boolean = synchronized(lock) {
        transition(SessionEvent.Stop(reason, System.currentTimeMillis()))
    }

    /**
     * Terminate any active or stopped session and reset coordinator state to Idle.
     * Clears all steer instructions, tool calls, pointer events, and timeline events.
     */
    fun reset(): Boolean = synchronized(lock) {
        transition(SessionEvent.Reset)
    }

    /**
     * End a run after a provider/bridge failure. This is intentionally distinct
     * from releaseRequest: a failed Codex turn must not be picked up and
     * replayed indefinitely by the polling companion.
     */
    fun fail(reason: String = "The desktop Codex turn failed."): Boolean = synchronized(lock) {
        transition(SessionEvent.Fail(reason, System.currentTimeMillis()))
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
        transition(
            SessionEvent.Complete(
                message = message,
                agentFeedback = agentFeedback,
                agentMessageId = agentMessageId,
                nowEpochMs = System.currentTimeMillis(),
            ),
        )
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
        if (attentionGate.isPending) return@synchronized null
        val message = reason.trim().take(MAX_TEXT_CHARS).ifBlank { "The phone assistant needs your attention." }
        val safeActionLabel = actionLabel.trim().take(MAX_TEXT_CHARS)
            .ifBlank { DEFAULT_ATTENTION_ACTION_LABEL }
        val completion = attentionGate.open(sessionId, message)
        val updated = SessionStateMachine.withAttention(current, message, safeActionLabel)
            ?: return@synchronized null
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
    fun attentionPending(): Boolean = synchronized(lock) { attentionGate.isPending }

    /** Suspend the bridge request until the phone user acknowledges or stops the run. */
    suspend fun awaitAttention(sessionId: String): AttentionResolution =
        synchronized(lock) {
            attentionGate.waiterFor(sessionId)
        }?.await() ?: AttentionResolution.Cancelled

    /** Complete the blocking attention tool from the DHD UI's Done button. */
    fun acknowledgeAttention(automatic: Boolean = false): Boolean = synchronized(lock) {
        val pending = attentionGate.pendingOrNull() ?: return@synchronized false
        val current = _state.value
        if (current.sessionIdOrNull != pending.sessionId || !current.isActive) {
            attentionGate.cancelPending()
            return@synchronized false
        }
        attentionGate.dismissPending()
        _state.value = SessionStateMachine.withoutAttention(current)
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
        attentionGate.acknowledge(pending)
        true
    }

    /**
     * Keep a phone-dependent tool call open while the user restores DHD's
     * phone access. This deliberately resolves automatically when the local
     * service becomes ready; the model must never receive a normal tool error
     * that it can absorb and turn into a misleading completed response.
     */
    suspend fun awaitPhoneAccessForTool(
        reason: String = PhoneAccessRecovery.PHONE_ACCESS_RECOVERY_MESSAGE,
    ): Boolean = phoneAccessRecovery.awaitPhoneAccess(reason)

    fun setCurrentPurpose(purpose: String, metadataPurpose: String? = null): Boolean = synchronized(lock) {
        val displayPurpose = userFacingActivityLabel(actionType = null, purpose = purpose)
        val safeMetadataPurpose = metadataPurpose
            ?.trim()
            ?.take(MAX_TEXT_CHARS)
            ?.takeIf(String::isNotBlank)
        val current = _state.value
        val updated = SessionStateMachine.withPurpose(current, displayPurpose, safeMetadataPurpose)
            ?: return false
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
        _state.value = SessionStateMachine.withPurpose(current, safePurpose, safePurpose) ?: current
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
    ): ActionExecutionResult = actionPipeline.execute(action, observation, toolName, targetDisplay)

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
        pointerFeedback.publishGesture(sessionId, action, observation, clickPhase)
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

        pointerFeedback.publishCalibration(sessionId, observation)
    }

    fun close() {
        val sessionId = synchronized(lock) { _state.value.sessionIdOrNull }
        if (sessionId != null) {
            transport.cancelSessionForRun(sessionId)
            cleanupScope.launch {
                transport.retainSessionForRun(sessionId, TaskDisplayStatus.STOPPED, "Session closed.")
            }
        }
        synchronized(lock) {
            attentionGate.cancelAll()
            steers.clearAll()
        }
    }

    private fun transition(event: SessionEvent): Boolean {
        val transition = SessionStateMachine.reduce(_state.value, event) ?: return false
        var storedConversationId: String? = null
        transition.effects.forEach { effect ->
            when (effect) {
                SessionEffect.CommitState ->
                    _state.value = transition.state.withStoredConversation(storedConversationId)
                SessionEffect.ForgetCompletedAttentions -> attentionGate.forgetCompleted()
                is SessionEffect.SettleAttention -> attentionGate.settle(effect.sessionId)
                SessionEffect.CancelAllAttention -> attentionGate.cancelAll()
                SessionEffect.ClearPointer -> pointerFeedback.clear()
                SessionEffect.ReleaseClaim -> handoff.release()
                SessionEffect.ClearToolCalls -> toolCallLog.clear()
                SessionEffect.ClearEvents -> activityLog.clear()
                is SessionEffect.ClearSteers -> steers.clear(effect.sessionId)
                SessionEffect.ClearAllSteers -> steers.clearAll()
                is SessionEffect.CancelTransport -> transport.cancelSessionForRun(effect.sessionId)
                is SessionEffect.RetainDisplay -> cleanupScope.launch {
                    transport.retainSessionForRun(effect.sessionId, effect.status, effect.error)
                }
                is SessionEffect.UpdateDisplayStatus -> cleanupScope.launch {
                    transport.updateSessionDisplayStatusForRun(effect.sessionId, effect.status)
                }
                is SessionEffect.OpenRun -> storedConversationId = conversationStore
                    ?.startRun(effect.sessionId, effect.request, effect.conversationId)
                    ?.conversationId
                is SessionEffect.OpenContinuationRun -> storedConversationId = conversationStore
                    ?.startContinuationRun(
                        runId = effect.sessionId,
                        requestedConversationId = effect.conversationId,
                    )
                    ?.conversationId
                is SessionEffect.SetRunStatus -> conversationStore?.setRunStatus(effect.sessionId, effect.status)
                is SessionEffect.CompleteRun -> conversationStore?.completeRun(
                    effect.sessionId,
                    effect.status,
                    assistantText = effect.assistantText,
                )
                is SessionEffect.AppendEvent -> appendEvent(
                    effect.kind,
                    effect.message,
                    effect.sessionId,
                    eventId = effect.eventId,
                )
            }
        }
        return true
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
        const val MAX_TEXT_CHARS = SessionStateMachine.MAX_TEXT_CHARS
        const val MAX_AGENT_FEEDBACK_CHARS = SessionStateMachine.MAX_AGENT_FEEDBACK_CHARS
        const val DEFAULT_ATTENTION_ACTION_LABEL = "Done"
    }
}
