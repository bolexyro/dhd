package com.phonecontrol.assistant.ui.theme

import androidx.compose.ui.graphics.Color
import com.phonecontrol.assistant.core.ToolNames

/**
 * Maps the DHD tool represented by an activity row to the same accent used by
 * the live task-display footer. Unknown tools retain the caller's fallback.
 */
internal fun toolActivityColor(
    toolName: String?,
    colors: AssistantColorScheme,
    fallback: Color = colors.textSecondary,
    actionType: String? = null,
): Color {
    if (actionType.equals("WAIT", ignoreCase = true)) return colors.accentMagenta

    return when (toolName?.lowercase()) {
        "wait", "dhd_wait", "phone_wait_for" -> colors.accentMagenta
        ToolNames.OBSERVE, "dhd_observe_app" -> colors.accentBlue
        ToolNames.EXECUTE, ToolNames.EXECUTE_SEQUENCE -> colors.accentGreen
        ToolNames.BROWSE_APP -> colors.accentPurple
        ToolNames.OPEN_APP -> colors.accentCyan
        ToolNames.FOREGROUND_APP -> colors.accentOrange
        ToolNames.LIST_ALLOWED_APPS -> colors.accentPink
        else -> fallback
    }
}
