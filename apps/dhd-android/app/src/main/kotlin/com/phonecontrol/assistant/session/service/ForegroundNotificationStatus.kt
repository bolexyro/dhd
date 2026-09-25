package com.phonecontrol.assistant.session.service

import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionState

internal fun SessionState.foregroundNotificationStatus(toolCalls: List<DhdToolCall>): String = when (this) {
    SessionState.Idle -> "Ready"
    is SessionState.Running -> notificationPurpose(
        preferredNotificationPurpose(toolCalls) ?: currentPurpose,
    )
    is SessionState.Paused -> "Paused · ${notificationPurpose(preferredNotificationPurpose(toolCalls) ?: currentPurpose)}"
    is SessionState.Stopped -> "Stopped"
    is SessionState.Completed -> "Completed"
}

private fun SessionState.preferredNotificationPurpose(toolCalls: List<DhdToolCall>): String? {
    val currentPurpose = when (this) {
        is SessionState.Running -> currentPurpose
        is SessionState.Paused -> currentPurpose
        else -> return null
    }
    if (currentPurpose.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true)) return null

    val metadataPurpose = when (this) {
        is SessionState.Running -> currentToolMetadataPurpose
        is SessionState.Paused -> currentToolMetadataPurpose
        else -> null
    }?.trim()?.takeIf(String::isNotBlank)
    return metadataPurpose ?: activeToolPurpose(toolCalls)
}

internal fun notificationPurpose(purpose: String): String = when {
    purpose.equals(CoordinatorCopy.PREPARING_REQUEST, ignoreCase = true) -> "Connecting to Codex…"
    purpose.equals(CoordinatorCopy.CODEX_PLANNING, ignoreCase = true) || purpose.equals(CoordinatorCopy.DHD_PLANNING, ignoreCase = true) -> "DHD-ing…"
    purpose.equals(CoordinatorCopy.WAITING_FOR_COMPANION, ignoreCase = true) -> "Companion not connected"
    purpose.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true) -> "DHD needs your attention"
    else -> purpose
}

internal fun SessionState.activeToolPurpose(toolCalls: List<DhdToolCall>): String? {
    val activeSessionId = when (this) {
        is SessionState.Running -> sessionId
        is SessionState.Paused -> sessionId
        else -> return null
    }
    return toolCalls.lastOrNull {
        it.sessionId == activeSessionId && it.status == DhdToolCallStatus.RUNNING
    }?.purpose
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_NOTIFICATION_PURPOSE_CHARS)
        ?.trimEnd()
        ?.takeIf(String::isNotBlank)
}

private const val MAX_NOTIFICATION_PURPOSE_CHARS = 160
