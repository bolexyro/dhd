package com.phonecontrol.assistant.bridge.protocol

import com.phonecontrol.assistant.bridge.SequenceExecutionResult
import com.phonecontrol.assistant.bridge.SequenceStepResult
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.execution.TransportResult
import com.phonecontrol.assistant.session.ActionExecutionResult
import org.json.JSONObject

internal fun TransportResult.resultMessage(): String = when (this) {
    is TransportResult.Rejected -> message
    is TransportResult.Unsupported -> message
    is TransportResult.Succeeded -> message
}

internal fun ActionExecutionResult.isSuccessful(): Boolean = this is ActionExecutionResult.TransportFinished &&
    this.result is TransportResult.Succeeded

internal fun ActionExecutionResult.beforeScreenshotOrNull(): ByteArray? = when (this) {
    is ActionExecutionResult.TransportFinished ->
        (result as? TransportResult.Succeeded)?.beforeScreenshot
    is ActionExecutionResult.PolicyRejected,
    ActionExecutionResult.SessionNotRunning -> null
}

internal fun ActionExecutionResult.staleDetailsOrNull(): StaleObservationDiagnostics? = when (this) {
    is ActionExecutionResult.TransportFinished -> (result as? TransportResult.Rejected)?.details
    is ActionExecutionResult.PolicyRejected -> details
    ActionExecutionResult.SessionNotRunning -> null
}

internal fun ActionExecutionResult.resultMessage(): String = when (this) {
    is ActionExecutionResult.TransportFinished -> result.resultMessage()
    is ActionExecutionResult.PolicyRejected -> message
    ActionExecutionResult.SessionNotRunning -> SESSION_NOT_RUNNING_MESSAGE
}

internal fun ActionExecutionResult.failureCode(): String? = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Rejected -> result.code.name
        is TransportResult.Unsupported -> BridgeErrorCodes.UNSUPPORTED_ACTION
        is TransportResult.Succeeded -> null
    }
    is ActionExecutionResult.PolicyRejected -> code
    ActionExecutionResult.SessionNotRunning -> BridgeErrorCodes.SESSION_NOT_RUNNING
}

internal fun ActionExecutionResult.sequenceStepFailureCode(): String = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Rejected -> result.code.name
        is TransportResult.Unsupported -> BridgeErrorCodes.UNSUPPORTED_ACTION
        is TransportResult.Succeeded -> BridgeErrorCodes.ACTION_FAILED
    }
    is ActionExecutionResult.PolicyRejected -> code
    ActionExecutionResult.SessionNotRunning -> BridgeErrorCodes.SESSION_NOT_RUNNING
}

internal const val SESSION_NOT_RUNNING_MESSAGE = "The phone session is no longer running."

internal fun failedActionCompletion(
    requestId: String,
    action: String,
    code: String,
    message: String,
    outcome: String = "failed",
    executed: Any = false,
): JSONObject = JSONObject()
    .put("type", "completed")
    .put("requestId", requestId)
    .put("ok", false)
    .put("action", action)
    .put("outcome", outcome)
    .put("executed", executed)
    .put("code", code)
    .put("message", message)

internal fun unstartedSequenceFailure(
    actions: List<PhoneAction>,
    code: String,
    message: String,
): SequenceExecutionResult {
    val failure = SequenceStepResult(
        index = 0,
        action = ActionParser.wireActionName(actions.first()),
        status = SequenceStepResult.Status.FAILED,
        message = message,
        code = code,
        outcome = "failed",
        executed = false,
    )
    return SequenceExecutionResult(
        requestedSteps = actions.size,
        steps = listOf(failure),
        failure = failure,
    )
}
