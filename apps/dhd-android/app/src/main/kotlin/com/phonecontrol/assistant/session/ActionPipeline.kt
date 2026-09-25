package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.bridge.protocol.resultMessage
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.domain.ClickPhase
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.domain.SwipeAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.userFacingActivityLabel
import com.phonecontrol.assistant.execution.PhoneActionTransport
import com.phonecontrol.assistant.execution.RejectionCode
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TransportResult
import com.phonecontrol.assistant.policy.PolicyContext
import com.phonecontrol.assistant.policy.PolicyDecision
import com.phonecontrol.assistant.policy.PolicyEngine

internal interface ActionPipelineHost {
    fun runningSession(): SessionState.Running?
    fun isSessionActive(sessionId: String): Boolean
    fun setCurrentPurpose(purpose: String, metadataPurpose: String?)
    fun publishPointer(
        sessionId: String,
        action: PhoneAction,
        observation: ObservationSnapshot?,
        clickPhase: ClickPhase = ClickPhase.PRESSED,
    )
    suspend fun awaitPhoneAccess(): Boolean
}

internal class ActionPipeline(
    private val host: ActionPipelineHost,
    private val activityLog: ActivityLog,
    private val policyEngine: PolicyEngine,
    private val transport: PhoneActionTransport,
    private val enabledPackagesProvider: () -> Set<String>,
    private val fullAccessProvider: () -> Boolean,
    private val taskDisplayRequiredProvider: () -> Boolean,
) {
    suspend fun execute(
        action: PhoneAction,
        observation: ObservationSnapshot?,
        toolName: String?,
        targetDisplay: TaskDisplaySession?,
    ): ActionExecutionResult {
        val running = host.runningSession()
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
            activityLog.append(
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
        host.setCurrentPurpose(displayPurpose, metadataPurpose = action.metadata.purpose)
        activityLog.append(
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
                activityLog.append(
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

        activityLog.append(
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
        val stillActiveBeforeDispatch = host.isSessionActive(running.sessionId)
        if (!stillActiveBeforeDispatch) return ActionExecutionResult.SessionNotRunning

        var clickPressPublished = false
        val beforeInput = if (tapAction != null && observation != null) {
            {
                if (!clickPressPublished) {
                    clickPressPublished = true
                    host.publishPointer(
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
                    host.publishPointer(
                        sessionId = running.sessionId,
                        action = tapAction,
                        observation = observation,
                        clickPhase = ClickPhase.MOVING,
                    )
                }
            }

            action is SwipeAction && observation != null -> {
                {
                    host.publishPointer(
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
            if (!host.awaitPhoneAccess()) {
                val stillActiveAfterRecovery = host.isSessionActive(running.sessionId)
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
        val stillActive = host.isSessionActive(running.sessionId)
        if (!stillActive) return ActionExecutionResult.SessionNotRunning
        val eventKind = if (result is TransportResult.Succeeded) {
            ActivityEventKind.ACTION_SUCCEEDED
        } else {
            ActivityEventKind.ACTION_FAILED
        }
        activityLog.append(
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

    private companion object {
        const val MAX_PHONE_ACCESS_RECOVERY_ATTEMPTS = 3
    }
}
