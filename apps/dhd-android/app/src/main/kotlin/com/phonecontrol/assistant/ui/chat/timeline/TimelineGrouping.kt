package com.phonecontrol.assistant.ui.chat.timeline

import androidx.compose.runtime.key
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.data.TimelineItem

internal data class TaskGroup(
    val id: String,
    val runIds: Set<String>,
    val userMessage: TimelineItem.Message?,
    val steerMessages: List<TimelineItem.Message>,
    val activities: List<TimelineItem.Activity>,
    val assistantMessages: List<TimelineItem.Message>,
    val timestampEpochMs: Long,
)

private class TaskGroupBuilder(val id: String) {
    val runIds = linkedSetOf<String>()
    var userMessage: TimelineItem.Message? = null
    val steerMessages = mutableListOf<TimelineItem.Message>()
    val activities = mutableListOf<TimelineItem.Activity>()
    val assistantMessages = mutableListOf<TimelineItem.Message>()
    var timestampEpochMs: Long = Long.MAX_VALUE

    fun addTimestamp(timestamp: Long) {
        timestampEpochMs = minOf(timestampEpochMs, timestamp)
    }

    fun build(): TaskGroup = TaskGroup(
        id = id,
        runIds = runIds.toSet(),
        userMessage = userMessage,
        steerMessages = steerMessages.toList(),
        activities = activities.toList(),
        assistantMessages = assistantMessages.toList(),
        timestampEpochMs = timestampEpochMs,
    )
}

internal fun groupTimeline(
    timeline: List<TimelineItem>,
    continuationRunId: String? = null,
): List<TaskGroup> {
    val builders = linkedMapOf<String, TaskGroupBuilder>()
    timeline.forEach { item ->
        val key = when (item) {
            is TimelineItem.Message -> item.runId ?: "message-${item.id}"
            is TimelineItem.Activity -> item.runId
        }
        val builder = builders.getOrPut(key) { TaskGroupBuilder(key) }
        builder.runIds += key
        builder.addTimestamp(item.timestampEpochMs)
        when (item) {
            is TimelineItem.Message -> {
                if (item.role == "user" && builder.userMessage == null) {
                    builder.userMessage = item
                } else if (item.role == "steer") {
                    builder.steerMessages += item
                } else {
                    builder.assistantMessages += item
                }
            }

            is TimelineItem.Activity -> {
                // The activity feed is a DHD tool trace, not a general-purpose
                // session log. Keep only physical action events and omit
                // legacy confirmation rows.
                if (item.isDhdActionActivity()) builder.activities += item
            }
        }
    }
    val rawGroups = builders.values.map(TaskGroupBuilder::build).sortedBy { it.timestampEpochMs }
    val groups = mutableListOf<TaskGroup>()

    rawGroups.forEach { group ->
        // A Continue run deliberately has no user-message row. Fold those
        // hidden runs into the latest visible task so an interrupted task
        // keeps one activity trace after it resumes. The actual run IDs stay
        // in the group for active-state and preview matching.
        if (group.userMessage == null) {
            val parentIndex = groups.indexOfLast { it.userMessage != null }
            if (parentIndex >= 0) {
                groups[parentIndex] = groups[parentIndex].merge(group)
                return@forEach
            }
        }
        groups += group
    }

    // Before the resumed turn emits its first event, it has no timeline item
    // of its own. Associate the active continuation with the latest task now
    // so the thinking animation is visible immediately after Continue.
    if (continuationRunId != null && groups.isNotEmpty() &&
        groups.none { continuationRunId in it.runIds }
    ) {
        val parentIndex = groups.indexOfLast { it.userMessage != null }
            .takeIf { it >= 0 }
            ?: groups.lastIndex
        groups[parentIndex] = groups[parentIndex].copy(
            runIds = groups[parentIndex].runIds + continuationRunId,
        )
    }

    return groups
}

private fun TaskGroup.merge(other: TaskGroup): TaskGroup = copy(
    runIds = runIds + other.runIds,
    steerMessages = steerMessages + other.steerMessages,
    activities = activities + other.activities,
    assistantMessages = assistantMessages + other.assistantMessages,
    timestampEpochMs = minOf(timestampEpochMs, other.timestampEpochMs),
)

private fun TimelineItem.Activity.isDhdActionActivity(): Boolean =
    !status.equals("confirmation", ignoreCase = true) &&
            !ToolNames.isCloseDisplay(toolName)

internal fun activeTaskRunIds(
    timeline: List<TimelineItem>,
    active: Boolean,
    activeSessionId: String?,
    continuationRunId: String?,
): Set<String> = if (active && activeSessionId != null) {
    groupTimeline(timeline, continuationRunId)
        .firstOrNull { activeSessionId in it.runIds }
        ?.runIds
        ?: setOf(activeSessionId)
} else {
    emptySet()
}

internal fun recentTimelineItems(
    timeline: List<TimelineItem>,
    nowEpochMs: Long,
    active: Boolean,
    activeTaskRunIds: Set<String>,
): List<TimelineItem> {
    val recentCutoff = nowEpochMs - RECENT_HISTORY_WINDOW_MS
    return timeline.filter { item ->
        item.timestampEpochMs >= recentCutoff ||
                (active && item.belongsTo(activeTaskRunIds))
    }
}

private fun TimelineItem.belongsTo(runIds: Set<String>): Boolean = when (this) {
    is TimelineItem.Message -> this.runId?.let(runIds::contains) == true
    is TimelineItem.Activity -> this.runId in runIds
}

private const val RECENT_HISTORY_WINDOW_MS = 24L * 60L * 60L * 1000L
