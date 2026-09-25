package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.core.ToolNames

/** A safe, presentation-only description of one DHD dynamic tool call. */
data class DhdToolCall(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val purpose: String,
    val status: DhdToolCallStatus,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
)

enum class DhdToolCallStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    ATTENTION,
}

fun defaultDhdToolPurpose(toolName: String): String = when (toolName) {
    ToolNames.LIST_ALLOWED_APPS -> "Checking which apps DHD can use"
    ToolNames.BROWSE_APP -> "Browsing installed apps"
    ToolNames.SET_APP_DISPLAY_LAYOUT -> "Adjusting the app's task-display layout"
    ToolNames.FOREGROUND_APP -> "Checking which app is on screen"
    ToolNames.OBSERVE -> "Inspecting the current screen"
    ToolNames.OPEN_APP -> "Opening an app"
    ToolNames.EXECUTE -> "Performing a phone interaction"
    ToolNames.EXECUTE_SEQUENCE -> "Performing a validated series of interactions"
    ToolNames.REQUEST_ATTENTION -> "Waiting for your attention"
    else -> "Working with the phone"
}
