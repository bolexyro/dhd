package com.phonecontrol.assistant.ui

import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGroupingTest {

    @Test
    fun `continuation activity is folded into the original task`() {
        val originalRunId = "original-run"
        val continuationRunId = "continuation-run"
        val timeline = listOf(
            TimelineItem.Message(
                id = "user-message",
                runId = originalRunId,
                role = "user",
                text = "Play the song",
                timestampEpochMs = 1_000L,
            ),
            activity("original-activity", originalRunId, 2_000L),
            activity("continued-activity", continuationRunId, 3_000L),
        )

        val groups = groupTimeline(timeline)

        assertEquals(1, groups.size)
        assertEquals(setOf(originalRunId, continuationRunId), groups.single().runIds)
        assertEquals(2, groups.single().activities.size)
    }

    @Test
    fun `continuation is attached before its first event arrives`() {
        val originalRunId = "original-run"
        val continuationRunId = "continuation-run"
        val timeline = listOf(
            TimelineItem.Message(
                id = "user-message",
                runId = originalRunId,
                role = "user",
                text = "Play the song",
                timestampEpochMs = 1_000L,
            ),
        )

        val groups = groupTimeline(timeline, continuationRunId)

        assertEquals(1, groups.size)
        assertTrue(continuationRunId in groups.single().runIds)
    }

    @Test
    fun `a new user request remains a separate task`() {
        val firstRunId = "first-run"
        val secondRunId = "second-run"
        val timeline = listOf(
            TimelineItem.Message("first-user", firstRunId, "user", "First", 1_000L),
            TimelineItem.Message("second-user", secondRunId, "user", "Second", 2_000L),
        )

        val groups = groupTimeline(timeline)

        assertEquals(2, groups.size)
        assertEquals(setOf(firstRunId), groups[0].runIds)
        assertEquals(setOf(secondRunId), groups[1].runIds)
    }

    @Test
    fun `retained display preview follows current run instead of old display owner`() {
        val preview = LiveDisplayPreviewState.live(
            sessionKey = "old-display-owner",
        ).copy(runSessionKey = "current-run")

        assertFalse(preview.belongsToGroup("old-display-owner"))
        assertTrue(preview.belongsToGroup("current-run"))
    }

    @Test
    fun `elapsed seconds resume from prior active work`() {
        assertEquals(
            13L,
            accumulatedElapsedSeconds(
                startedAtEpochMs = 10_000L,
                elapsedBeforeStartMs = 12_000L,
                nowEpochMs = 11_500L,
            ),
        )
    }

    @Test
    fun `active trace keeps current action and four recent actions`() {
        val activities = (1..8).map { index ->
            activity(
                id = "activity-$index",
                runId = "run",
                timestampEpochMs = index * 1_000L,
                status = if (index == 8) "running" else "completed",
            )
        }

        val trace = capActivityTrace(
            activities = activities,
            active = true,
            currentActivityId = "activity-8",
        )

        assertEquals(
            listOf("activity-4", "activity-5", "activity-6", "activity-7", "activity-8"),
            trace.visibleActivities.map { it.id },
        )
        assertEquals(3, trace.earlierCount)
        assertEquals("activity-8", trace.currentActivityId)
        assertFalse(trace.hasSyntheticCurrent)
    }

    @Test
    fun `active trace reserves a row for a tool before its activity arrives`() {
        val activities = (1..8).map { index ->
            activity(
                id = "activity-$index",
                runId = "run",
                timestampEpochMs = index * 1_000L,
            )
        }

        val trace = capActivityTrace(
            activities = activities,
            active = true,
            hasCurrentTool = true,
        )

        assertEquals(
            listOf("activity-5", "activity-6", "activity-7", "activity-8"),
            trace.visibleActivities.map { it.id },
        )
        assertEquals(4, trace.earlierCount)
        assertTrue(trace.hasSyntheticCurrent)
    }

    @Test
    fun `completed trace keeps the five most recent actions`() {
        val activities = (1..8).map { index ->
            activity(
                id = "activity-$index",
                runId = "run",
                timestampEpochMs = index * 1_000L,
            )
        }

        val trace = capActivityTrace(activities, active = false)

        assertEquals(
            listOf("activity-4", "activity-5", "activity-6", "activity-7", "activity-8"),
            trace.visibleActivities.map { it.id },
        )
        assertEquals(3, trace.earlierCount)
        assertEquals("+37 earlier actions", earlierActionsLabel(37))
    }

    private fun activity(
        id: String,
        runId: String,
        timestampEpochMs: Long,
        status: String = "completed",
    ) =
        TimelineItem.Activity(
            id = id,
            runId = runId,
            purpose = "Opening the app",
            targetDescription = null,
            toolName = "dhd_open_app",
            actionType = "OPEN_APP",
            status = status,
            message = "Opening the app",
            createdAtEpochMs = timestampEpochMs,
            timestampEpochMs = timestampEpochMs,
        )
}
