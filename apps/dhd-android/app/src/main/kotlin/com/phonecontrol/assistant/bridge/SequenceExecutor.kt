package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.domain.BackAction
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.KeypressAction
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.SwipeAction
import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.TypeAction
import com.phonecontrol.assistant.domain.WaitAction
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.beforeScreenshotOrNull
import com.phonecontrol.assistant.bridge.protocol.isSuccessful
import com.phonecontrol.assistant.bridge.protocol.resultMessage
import com.phonecontrol.assistant.bridge.protocol.sequenceStepFailureCode
import com.phonecontrol.assistant.bridge.protocol.staleDetailsOrNull
import com.phonecontrol.assistant.session.ActionExecutionResult
import com.phonecontrol.assistant.observation.ObservationCaptureResult

internal data class SequenceStepResult(
    val index: Int,
    val action: String,
    val status: Status,
    val message: String,
    val observationId: String? = null,
    val code: String? = null,
    val outcome: String? = null,
    val executed: Boolean? = null,
    val details: StaleObservationDiagnostics? = null,
) {
    enum class Status {
        SUCCESS,
        FAILED,
    }
}

internal data class SequenceExecutionResult(
    val requestedSteps: Int,
    val steps: List<SequenceStepResult>,
    val finalObservation: ObservationCaptureResult.Succeeded? = null,
    val failure: SequenceStepResult? = null,
    val beforeScreenshot: ByteArray? = null,
) {
    val completedSteps: Int
        get() = steps.count { it.status == SequenceStepResult.Status.SUCCESS }

    val ok: Boolean
        get() = failure == null && completedSteps == requestedSteps
}

/**
 * Runs a fixed typed action list while carrying the phone-created observation
 * from one verified step into the next. The companion only supplies the
 * initial observation ID; future IDs are never guessed by the model.
 */
internal class SequenceExecutor(
    private val executeAction: suspend (PhoneAction, ObservationSnapshot) -> ActionExecutionResult,
    private val captureAfterAction: suspend (List<GuardRegion>) -> ObservationCaptureResult,
    private val rememberObservation: (ObservationSnapshot) -> Unit,
    private val settleAfterAction: suspend (PhoneAction) -> Unit,
) {
    suspend fun execute(
        initialObservation: ObservationSnapshot,
        actions: List<PhoneAction>,
    ): SequenceExecutionResult {
        require(actions.isNotEmpty()) { "A sequence must contain at least one action." }

        var baseline = initialObservation
        var finalObservation: ObservationCaptureResult.Succeeded? = null
        var beforeScreenshot: ByteArray? = null
        val steps = mutableListOf<SequenceStepResult>()

        actions.forEachIndexed { index, unboundAction ->
            val action = bindObservation(unboundAction, baseline.id)
            val execution = executeAction(action, baseline)
            if (index == 0 && beforeScreenshot == null) {
                beforeScreenshot = execution.beforeScreenshotOrNull()
            }
            if (!execution.isSuccessful()) {
                val failure = failureStep(index, action, execution)
                steps += failure
                return SequenceExecutionResult(
                    requestedSteps = actions.size,
                    steps = steps,
                    failure = failure,
                    beforeScreenshot = beforeScreenshot,
                )
            }

            settleAfterAction(action)
            val guardRegions = actions.getOrNull(index + 1)
                ?.metadata
                ?.guardRegions
                ?: action.metadata.guardRegions
            when (val captured = captureAfterAction(guardRegions)) {
                is ObservationCaptureResult.Failed -> {
                    val failure = SequenceStepResult(
                        index = index,
                        action = action.type.name.lowercase(),
                        status = SequenceStepResult.Status.FAILED,
                        message = "The action may have run, but the phone could not produce a post-action observation: ${captured.message}",
                        code = BridgeErrorCodes.POST_OBSERVATION_FAILED,
                        outcome = "unknown",
                        executed = null,
                    )
                    steps += failure
                    return SequenceExecutionResult(
                        requestedSteps = actions.size,
                        steps = steps,
                        failure = failure,
                        beforeScreenshot = beforeScreenshot,
                    )
                }

                is ObservationCaptureResult.Succeeded -> {
                    rememberObservation(captured.snapshot)
                    baseline = captured.snapshot
                    finalObservation = captured
                    steps += SequenceStepResult(
                        index = index,
                        action = action.type.name.lowercase(),
                        status = SequenceStepResult.Status.SUCCESS,
                        message = execution.resultMessage(),
                        observationId = captured.snapshot.id,
                    )
                }
            }
        }

        return SequenceExecutionResult(
            requestedSteps = actions.size,
            steps = steps,
            finalObservation = finalObservation,
            beforeScreenshot = beforeScreenshot,
        )
    }

    private fun failureStep(
        index: Int,
        action: PhoneAction,
        result: ActionExecutionResult,
    ): SequenceStepResult = SequenceStepResult(
        index = index,
        action = action.type.name.lowercase(),
        status = SequenceStepResult.Status.FAILED,
        message = result.resultMessage(),
        code = result.sequenceStepFailureCode(),
        outcome = "failed",
        executed = false,
        details = result.staleDetailsOrNull(),
    )

    private fun bindObservation(action: PhoneAction, observationId: String): PhoneAction {
        val metadata = action.metadata.copy(observationId = observationId)
        return when (action) {
            is OpenAppAction -> action.copy(metadata = metadata)
            is TapAction -> action.copy(metadata = metadata)
            is TypeAction -> action.copy(metadata = metadata)
            is SwipeAction -> action.copy(metadata = metadata)
            is BackAction -> action.copy(metadata = metadata)
            is KeypressAction -> action.copy(metadata = metadata)
            is WaitAction -> action.copy(metadata = metadata)
        }
    }
}
