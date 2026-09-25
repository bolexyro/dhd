package com.phonecontrol.assistant.ui.chat.trace

import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.session.DhdToolCall

internal const val MAX_VISIBLE_TRACE_ACTIVITIES = 5

internal data class ActivityTraceSlice(
    val visibleActivities: List<TimelineItem.Activity>,
    val earlierCount: Int,
    val currentActivityId: String?,
    val hasSyntheticCurrent: Boolean,
)

/** Keep the conversation trace compact without discarding the stored history. */
internal fun capActivityTrace(
    activities: List<TimelineItem.Activity>,
    active: Boolean,
    currentActivityId: String? = null,
    hasCurrentTool: Boolean = false,
): ActivityTraceSlice {
    val persistedCurrentId = currentActivityId
        ?.takeIf { id -> active && activities.any { it.id == id } }
    val hasCurrent = persistedCurrentId != null || (active && hasCurrentTool)
    val historyLimit = (MAX_VISIBLE_TRACE_ACTIVITIES - if (hasCurrent) 1 else 0)
        .coerceAtLeast(0)
    val recentActivities = activities
        .filterNot { it.id == persistedCurrentId }
        .takeLast(historyLimit)
    val visibleIds = (recentActivities.map { it.id } + listOfNotNull(persistedCurrentId)).toSet()
    val visibleActivities = activities.filter { it.id in visibleIds }

    return ActivityTraceSlice(
        visibleActivities = visibleActivities,
        earlierCount = (activities.size - visibleActivities.size).coerceAtLeast(0),
        currentActivityId = persistedCurrentId,
        hasSyntheticCurrent = active && hasCurrentTool && persistedCurrentId == null,
    )
}

internal fun earlierActionsLabel(count: Int): String {
    val safeCount = count.coerceAtLeast(0)
    return "+$safeCount earlier action${if (safeCount == 1) "" else "s"}"
}

internal fun TimelineItem.Activity.isInFlight(): Boolean =
    status.equals("proposed", ignoreCase = true) || status.equals("running", ignoreCase = true)

internal fun TimelineItem.Activity.matchesLiveTool(toolCall: DhdToolCall): Boolean {
    val activityStatus = status.lowercase()
    val sameTool = toolName?.equals(toolCall.toolName, ignoreCase = true) == true ||
            (toolCall.toolName.equals(ToolNames.OPEN_APP, ignoreCase = true) &&
                    toolName.equals(ToolNames.EXECUTE, ignoreCase = true) &&
                    actionType.equals("OPEN_APP", ignoreCase = true))
    return runId == toolCall.sessionId &&
            sameTool &&
            createdAtEpochMs >= toolCall.startedAtEpochMs &&
            // The phone records ACTION_SUCCEEDED/ACTION_FAILED before the bridge
            // finishes the outer live tool call. Treat that terminal row as the
            // same call so the UI never renders a green persisted row alongside
            // its cyan synthetic counterpart.
            activityStatus in setOf("info", "proposed", "running", "completed", "failed", "attention")
}

internal fun activityLabel(activity: TimelineItem.Activity): String = activity.purpose
    .trim()
    .ifBlank { "Working on the phone" }
