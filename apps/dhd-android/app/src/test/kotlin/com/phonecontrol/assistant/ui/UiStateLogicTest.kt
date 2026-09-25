package com.phonecontrol.assistant.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.execution.TaskDisplayGeometry
import com.phonecontrol.assistant.ui.chat.composer.PendingSteerDraft
import com.phonecontrol.assistant.ui.chat.composer.SteerDraftPromotion
import com.phonecontrol.assistant.ui.chat.composer.SteerDraftQueue
import com.phonecontrol.assistant.ui.chat.composer.forActiveSession
import com.phonecontrol.assistant.ui.chat.composer.promoteAfterCompletion
import com.phonecontrol.assistant.ui.chat.composer.steerDraftsSaver
import com.phonecontrol.assistant.ui.chat.timeline.activeTaskRunIds
import com.phonecontrol.assistant.ui.chat.timeline.recentTimelineItems
import com.phonecontrol.assistant.ui.components.reasoning.effectiveReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.reasoningEffortAtFraction
import com.phonecontrol.assistant.ui.components.reasoning.reasoningTrackEfforts
import com.phonecontrol.assistant.ui.components.reasoning.reasoningTrackFraction
import com.phonecontrol.assistant.ui.components.reasoning.reasoningTrackPosition
import com.phonecontrol.assistant.ui.components.reasoning.visibleReasoningEffortsFromStorage
import com.phonecontrol.assistant.ui.displays.DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewStatus
import com.phonecontrol.assistant.ui.displays.TaskDisplayLifecycle
import com.phonecontrol.assistant.ui.displays.TaskDisplayUiRecord
import com.phonecontrol.assistant.ui.displays.displayRecordsWithPreviewFallback
import com.phonecontrol.assistant.ui.displays.inspectableTaskDisplayRecords
import com.phonecontrol.assistant.ui.displays.normalizedPoint
import com.phonecontrol.assistant.ui.displays.sortTaskDisplayRecords
import com.phonecontrol.assistant.ui.displays.viewerPreviewState
import com.phonecontrol.assistant.ui.navigation.supportedInitialRoute
import com.phonecontrol.assistant.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class UiStateLogicTest {
    private val allEfforts = ReasoningEffort.entries

    @Test
    fun `theme mode defaults to dark and keeps its storage values and labels`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage("unknown"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("system"))
        assertEquals(
            listOf("system" to "System (Default)", "light" to "Light", "dark" to "Dark"),
            ThemeMode.entries.map { it.storageValue to it.label },
        )
    }

    @Test
    fun `visible reasoning efforts come from storage in canonical order`() {
        assertEquals(allEfforts, visibleReasoningEffortsFromStorage(null))
        assertEquals(allEfforts, visibleReasoningEffortsFromStorage(" , "))
        assertEquals(allEfforts, visibleReasoningEffortsFromStorage("bogus"))
        assertEquals(
            listOf(ReasoningEffort.LIGHT, ReasoningEffort.MAX),
            visibleReasoningEffortsFromStorage(" max ,light,light"),
        )
    }

    @Test
    fun `effective reasoning effort falls back to the first visible effort`() {
        assertEquals(ReasoningEffort.HIGH, effectiveReasoningEffort(null, allEfforts))
        assertEquals(ReasoningEffort.MAX, effectiveReasoningEffort("max", allEfforts))
        assertEquals(
            ReasoningEffort.MEDIUM,
            effectiveReasoningEffort("max", listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
        )
        assertEquals(
            ReasoningEffort.LIGHT,
            effectiveReasoningEffort("unknown", listOf(ReasoningEffort.LIGHT, ReasoningEffort.MEDIUM)),
        )
    }

    @Test
    fun `only known routes are opened from an intent`() {
        listOf("settings", "task_displays", "pairing", "approved_apps", "companion", "permission_setup").forEach {
            assertEquals(it, supportedInitialRoute(it))
        }
        assertNull(supportedInitialRoute("main"))
        assertNull(supportedInitialRoute("unknown"))
        assertNull(supportedInitialRoute(null))
    }

    @Test
    fun `display records fall back to the active preview`() {
        val records = listOf(TaskDisplayUiRecord(sessionKey = "owner-1"))
        val preview = LiveDisplayPreviewState.live(appLabel = "Shop", sessionKey = "owner-2")
            .copy(purpose = "Checking", currentToolName = "dhd_observe")
        assertSame(records, displayRecordsWithPreviewFallback(records, preview))
        assertEquals(
            listOf(
                TaskDisplayUiRecord(
                    sessionKey = "owner-2",
                    lifecycle = TaskDisplayLifecycle.RUNNING,
                    appLabel = "Shop",
                    currentPurpose = "Checking",
                    currentToolName = "dhd_observe",
                    previewState = preview,
                ),
            ),
            displayRecordsWithPreviewFallback(emptyList(), preview),
        )
        assertEquals(emptyList<TaskDisplayUiRecord>(), displayRecordsWithPreviewFallback(emptyList(), null))
        assertEquals(
            emptyList<TaskDisplayUiRecord>(),
            displayRecordsWithPreviewFallback(emptyList(), LiveDisplayPreviewState.live()),
        )
    }

    @Test
    fun `viewer state prefers the record preview then the active preview then an unavailable placeholder`() {
        val recordPreview = LiveDisplayPreviewState.live(sessionKey = "owner-1")
        val activePreview = LiveDisplayPreviewState.connecting(sessionKey = "owner-1")
        val record = TaskDisplayUiRecord(
            sessionKey = "owner-1",
            appLabel = "Shop",
            geometry = TaskDisplayGeometry(720, 1560, 420, 0),
            currentPurpose = "Task complete",
            currentToolName = "dhd_observe",
            error = "The app crashed.",
        )
        assertSame(recordPreview, viewerPreviewState(record.copy(previewState = recordPreview), activePreview))
        assertSame(activePreview, viewerPreviewState(record, activePreview))
        assertEquals(
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.UNAVAILABLE,
                message = "The app crashed.",
                aspectRatio = 720f / 1560f,
                sessionKey = "owner-1",
                appLabel = "Shop",
                purpose = "Task complete",
                currentToolName = "dhd_observe",
            ),
            viewerPreviewState(record, LiveDisplayPreviewState.live(sessionKey = "owner-2")),
        )
        assertEquals(
            DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            viewerPreviewState(record.copy(geometry = null), null)!!.aspectRatio,
        )
        assertNull(viewerPreviewState(null, LiveDisplayPreviewState.live(sessionKey = "owner-1")))
        val unkeyedPreview = LiveDisplayPreviewState.live()
        assertSame(unkeyedPreview, viewerPreviewState(null, unkeyedPreview))
    }

    @Test
    fun `steer drafts follow the active session`() {
        val draft = PendingSteerDraft("Use the blue one", "high", fastMode = false)
        val queue = SteerDraftQueue(listOf(draft), sessionId = "run-1", carryToNextRun = false)
        assertSame(queue, queue.forActiveSession("run-1"))
        assertEquals(SteerDraftQueue(emptyList(), "run-2", false), queue.forActiveSession("run-2"))
        assertEquals(SteerDraftQueue(emptyList(), null, false), queue.forActiveSession(null))
        val carried = queue.copy(carryToNextRun = true)
        assertEquals(SteerDraftQueue(listOf(draft), "run-2", false), carried.forActiveSession("run-2"))
        assertEquals(SteerDraftQueue(emptyList(), null, false), carried.forActiveSession(null))
        assertEquals(
            SteerDraftQueue(emptyList(), "run-2", false),
            carried.copy(drafts = emptyList()).forActiveSession("run-2"),
        )
    }

    @Test
    fun `a completed run promotes the oldest steer draft`() {
        val first = PendingSteerDraft("First", "high", fastMode = false)
        val second = PendingSteerDraft("Second", "xhigh", fastMode = true)
        val queue = SteerDraftQueue(listOf(first, second), sessionId = "run-1", carryToNextRun = false)
        assertEquals(
            SteerDraftPromotion(first, SteerDraftQueue(listOf(second), "run-1", carryToNextRun = true)),
            queue.promoteAfterCompletion("run-1"),
        )
        assertEquals(
            SteerDraftPromotion(second, SteerDraftQueue(emptyList(), "run-1", carryToNextRun = false)),
            queue.copy(drafts = listOf(second)).promoteAfterCompletion("run-1"),
        )
        assertNull(queue.promoteAfterCompletion("run-2"))
        assertNull(queue.copy(drafts = emptyList()).promoteAfterCompletion("run-1"))
    }

    @Test
    fun `steer drafts survive the saver round trip`() {
        val drafts = listOf(
            PendingSteerDraft("Use the blue one", "high", fastMode = false),
            PendingSteerDraft("Then pay", "xhigh", fastMode = true),
        )
        val scope = SaverScope { true }
        val saved = with(steerDraftsSaver) { scope.save(drafts) }
        assertEquals(listOf("Use the blue one", "high", "false", "Then pay", "xhigh", "true"), saved)
        assertEquals(drafts, steerDraftsSaver.restore(saved!!))
        assertEquals(drafts.take(1), steerDraftsSaver.restore(listOf("Use the blue one", "high", "false", "dangling")))
    }

    private fun message(id: String, runId: String?, timestamp: Long) =
        TimelineItem.Message(id = id, runId = runId, role = "user", text = id, timestampEpochMs = timestamp)

    @Test
    fun `recent timeline keeps the last day and the active task`() {
        val now = 10L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        val old = message("old", "run-0", now - day - 1)
        val boundary = message("boundary", "run-0", now - day)
        val activeOld = message("active-old", "run-1", now - 2 * day)
        val fresh = message("fresh", null, now)
        val timeline = listOf(old, boundary, activeOld, fresh)
        assertEquals(
            listOf(boundary, activeOld, fresh),
            recentTimelineItems(timeline, now, active = true, activeTaskRunIds = setOf("run-1")),
        )
        assertEquals(
            listOf(boundary, fresh),
            recentTimelineItems(timeline, now, active = false, activeTaskRunIds = setOf("run-1")),
        )
    }

    @Test
    fun `active task run ids fall back to the active session`() {
        assertEquals(emptySet<String>(), activeTaskRunIds(emptyList(), active = false, activeSessionId = "run-1", continuationRunId = null))
        assertEquals(emptySet<String>(), activeTaskRunIds(emptyList(), active = true, activeSessionId = null, continuationRunId = null))
        assertEquals(setOf("run-1"), activeTaskRunIds(emptyList(), active = true, activeSessionId = "run-1", continuationRunId = null))
    }

    @Test
    fun `task display manager hides ended displays and lists live ones first`() {
        val records = listOf(
            TaskDisplayUiRecord(sessionKey = "completed-new", lifecycle = TaskDisplayLifecycle.COMPLETED, createdAtEpochMs = 30),
            TaskDisplayUiRecord(sessionKey = "running-old", lifecycle = TaskDisplayLifecycle.RUNNING, createdAtEpochMs = 10),
            TaskDisplayUiRecord(sessionKey = "ended", lifecycle = TaskDisplayLifecycle.ENDED, createdAtEpochMs = 50),
            TaskDisplayUiRecord(sessionKey = "paused-new", lifecycle = TaskDisplayLifecycle.PAUSED, createdAtEpochMs = 20),
            TaskDisplayUiRecord(sessionKey = "expired", lifecycle = TaskDisplayLifecycle.EXPIRED, createdAtEpochMs = 60),
            TaskDisplayUiRecord(sessionKey = "unavailable", lifecycle = TaskDisplayLifecycle.UNAVAILABLE, createdAtEpochMs = 40),
        )
        val visible = inspectableTaskDisplayRecords(records)
        assertEquals(listOf("completed-new", "running-old", "paused-new", "unavailable"), visible.map { it.sessionKey })
        assertEquals(
            listOf("paused-new", "running-old", "unavailable", "completed-new"),
            sortTaskDisplayRecords(visible).map { it.sessionKey },
        )
    }

    @Test
    fun `reasoning track maps efforts to positions and back`() {
        assertEquals(listOf(ReasoningEffort.HIGH), reasoningTrackEfforts(emptyList()))
        val ordered = reasoningTrackEfforts(listOf(ReasoningEffort.MAX, ReasoningEffort.LIGHT, ReasoningEffort.MAX, ReasoningEffort.HIGH))
        assertEquals(listOf(ReasoningEffort.LIGHT, ReasoningEffort.HIGH, ReasoningEffort.MAX), ordered)
        assertEquals(0f, reasoningTrackPosition(ordered, ReasoningEffort.LIGHT))
        assertEquals(0.5f, reasoningTrackPosition(ordered, ReasoningEffort.HIGH))
        assertEquals(1f, reasoningTrackPosition(ordered, ReasoningEffort.MAX))
        assertEquals(0f, reasoningTrackPosition(ordered, ReasoningEffort.MEDIUM))
        assertEquals(1f, reasoningTrackPosition(listOf(ReasoningEffort.MEDIUM), ReasoningEffort.MEDIUM))

        assertEquals(0f, reasoningTrackFraction(x = 10f, width = 300f, innerMargin = 6f, thumbRadius = 23f))
        assertEquals(0.5f, reasoningTrackFraction(x = 150f, width = 300f, innerMargin = 6f, thumbRadius = 23f))
        assertEquals(1f, reasoningTrackFraction(x = 299f, width = 300f, innerMargin = 6f, thumbRadius = 23f))
        assertEquals(1f, reasoningTrackFraction(x = 100f, width = 40f, innerMargin = 6f, thumbRadius = 23f))

        assertEquals(ReasoningEffort.LIGHT, reasoningEffortAtFraction(0.24f, ordered))
        assertEquals(ReasoningEffort.HIGH, reasoningEffortAtFraction(0.25f, ordered))
        assertEquals(ReasoningEffort.HIGH, reasoningEffortAtFraction(0.74f, ordered))
        assertEquals(ReasoningEffort.MAX, reasoningEffortAtFraction(0.75f, ordered))
    }

    @Test
    fun `pointer coordinates are normalized and clamped to the display`() {
        assertEquals(Offset(0.5f, 0.25f), normalizedPoint(360, 390, 720, 1560))
        assertEquals(Offset(0f, 0f), normalizedPoint(-5, -5, 720, 1560))
        assertEquals(Offset(719f / 720f, 1559f / 1560f), normalizedPoint(5_000, 5_000, 720, 1560))
    }

    @Test
    fun `known bug pointer normalization throws for an empty display`() {
        assertThrows(IllegalArgumentException::class.java) { normalizedPoint(0, 0, 0, 1560) }
        assertThrows(IllegalArgumentException::class.java) { normalizedPoint(0, 0, 720, 0) }
    }
}
