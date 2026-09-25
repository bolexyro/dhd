package com.phonecontrol.assistant.bridge.protocol

import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.execution.TransportResult
import com.phonecontrol.assistant.session.ActionExecutionResult

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
        is TransportResult.Unsupported -> "UNSUPPORTED_ACTION"
        is TransportResult.Succeeded -> null
    }
    is ActionExecutionResult.PolicyRejected -> code
    ActionExecutionResult.SessionNotRunning -> "SESSION_NOT_RUNNING"
}

internal fun ActionExecutionResult.sequenceStepFailureCode(): String = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Rejected -> result.code.name
        is TransportResult.Unsupported -> "UNSUPPORTED_ACTION"
        is TransportResult.Succeeded -> "ACTION_FAILED"
    }
    is ActionExecutionResult.PolicyRejected -> "POLICY_REJECTED"
    ActionExecutionResult.SessionNotRunning -> "SESSION_NOT_RUNNING"
}

internal const val SESSION_NOT_RUNNING_MESSAGE = "The phone session is no longer running."
