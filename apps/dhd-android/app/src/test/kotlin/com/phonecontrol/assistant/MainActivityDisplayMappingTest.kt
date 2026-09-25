package com.phonecontrol.assistant

import com.phonecontrol.assistant.developer.TaskPreviewState
import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.execution.TaskDisplayGeometry
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.taskDisplayReference
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.LiveDisplayPreviewStatus
import com.phonecontrol.assistant.ui.TaskDisplayLifecycle
import com.phonecontrol.assistant.ui.TaskDisplayUiRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MainActivityDisplayMappingTest {
    private val geometry = TaskDisplayGeometry(720, 1560, 420, 0)
    private val ratio = 720f / 1560f

    private fun session(key: String, displayId: Int = 7, packageName: String = "com.example.shop") = TaskDisplaySession(
        sessionKey = key,
        taskId = "$key@$displayId",
        displayId = displayId,
        geometry = geometry,
        packageName = packageName,
    )

    private fun record(key: String, packageName: String = "com.example.shop", status: TaskDisplayStatus = TaskDisplayStatus.RUNNING) =
        TaskDisplayRecord(
            sessionKey = key,
            taskId = "$key@7",
            packageName = packageName,
            displayId = 7,
            width = 720,
            height = 1560,
            densityDpi = 420,
            rotation = 0,
            status = status,
            createdAtEpochMs = 100L,
            lastPurpose = "Checking the cart",
        )

    private fun running(sessionId: String = "run-1", isContinuation: Boolean = false, attention: String? = null) =
        SessionState.Running(
            sessionId = sessionId,
            request = "Buy milk",
            currentPurpose = "Opening Shop",
            startedAtEpochMs = 5_000L,
            isContinuation = isContinuation,
            attentionReason = attention,
        )

    private fun event(sessionId: String?, toolName: String?) = ActivityEvent(
        id = "$sessionId-$toolName",
        sessionId = sessionId,
        timestampEpochMs = 1L,
        kind = ActivityEventKind.SYSTEM,
        message = "m",
        toolName = toolName,
    )

    @Test
    fun `display purpose follows the session state`() {
        assertEquals("Opening Shop", running().displayPurpose())
        assertEquals("Stopped by the user.", SessionState.Stopped("run-1", "Stopped by the user.").displayPurpose())
        assertEquals("Task complete", SessionState.Completed("run-1", "Milk added.").displayPurpose())
        assertNull(SessionState.Idle.displayPurpose())
    }

    @Test
    fun `display for run prefers the resolved binding then the owner then a continuation`() {
        val resolved = session("owner-1")
        val active = session("run-1")
        assertSame(resolved, selectDisplayForRun(resolved, active, "run-1", running()))
        assertSame(active, selectDisplayForRun(null, active, "run-1", running()))
        assertNull(selectDisplayForRun(null, session("other"), "run-1", running()))
        val retained = session("previous-run")
        assertSame(retained, selectDisplayForRun(null, retained, "run-2", running("run-2", isContinuation = true)))
        assertNull(selectDisplayForRun(null, retained, "run-2", SessionState.Paused("run-2", "Buy", "Paused", startedAtEpochMs = 0L, isContinuation = true)))
        assertNull(selectDisplayForRun(null, null, "run-1", running()))
    }

    @Test
    fun `latest tool name skips close display calls and other sessions`() {
        val events = listOf(
            event("run-1", "dhd_open_app"),
            event("run-1", "dhd_observe"),
            event("run-2", "dhd_execute"),
            event("run-1", "DHD_CLOSE_DISPLAY"),
            event("run-1", "close_display"),
            event("run-1", " "),
            event("run-1", null),
        )
        assertEquals("dhd_observe", latestToolNameForRun(events, "run-1"))
        assertEquals("dhd_execute", latestToolNameForRun(events, "run-2"))
        assertNull(latestToolNameForRun(events, "run-3"))
        assertNull(latestToolNameForRun(events, null))
    }

    @Test
    fun `preview for run maps per session playback`() {
        val displaySession = session("owner-1")
        val pointer = TaskPointerEvent.Click(1L, "run-1", 10, 20, 720, 1560)
        fun preview(
            states: Map<String, TaskPreviewState> = emptyMap(),
            playback: TaskPreviewState = TaskPreviewState.Detached,
            pointerEvent: TaskPointerEvent? = pointer,
        ) = livePreviewForRun(
            session = displaySession,
            previewStates = states,
            playback = playback,
            records = listOf(record("owner-1", packageName = "com.example.mail")),
            coordinatorSessionKey = "run-1",
            pointerEvent = pointerEvent,
            purpose = "Opening Shop",
            currentToolName = "dhd_open_app",
            appLabelFor = { "label:$it" },
        )

        assertEquals(
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.CONNECTING,
                aspectRatio = ratio,
                appLabel = "label:com.example.mail",
                sessionKey = "owner-1",
                runSessionKey = "run-1",
                pointerEvent = pointer,
                purpose = "Opening Shop",
                currentToolName = "dhd_open_app",
            ),
            preview(),
        )
        assertEquals(LiveDisplayPreviewStatus.LIVE, preview(playback = TaskPreviewState.Attached(displaySession)).status)
        assertEquals(
            LiveDisplayPreviewStatus.CONNECTING,
            preview(
                states = mapOf("owner-1" to TaskPreviewState.Connecting(displaySession)),
                playback = TaskPreviewState.Attached(displaySession),
            ).status,
        )
        val error = preview(states = mapOf("owner-1" to TaskPreviewState.Error("owner-1", "decoder failed")))
        assertEquals(LiveDisplayPreviewStatus.ERROR to "decoder failed", error.status to error.message)
        val ended = preview(playback = TaskPreviewState.Ended(displaySession, "The virtual display ended."))
        assertEquals(LiveDisplayPreviewStatus.UNAVAILABLE to "The virtual display ended.", ended.status to ended.message)
        assertEquals(
            LiveDisplayPreviewStatus.CONNECTING,
            preview(playback = TaskPreviewState.Attached(session("other"))).status,
        )
        assertNull(preview(pointerEvent = TaskPointerEvent.Click(1L, "run-9", 1, 1, 720, 1560)).pointerEvent)
        val ownerPointer = TaskPointerEvent.Click(2L, "owner-1", 1, 1, 720, 1560)
        assertEquals(ownerPointer, preview(pointerEvent = ownerPointer).pointerEvent)
    }

    @Test
    fun `preview state for a session ignores other sessions`() {
        val mine = session("owner-1")
        val other = session("owner-2")
        assertNull(TaskPreviewState.Detached.forSession("owner-1"))
        assertEquals(TaskPreviewState.Connecting(mine), TaskPreviewState.Connecting(mine).forSession("owner-1"))
        assertNull(TaskPreviewState.Attached(other).forSession("owner-1"))
        assertNull(TaskPreviewState.Ended(other, "gone").forSession("owner-1"))
        assertEquals(TaskPreviewState.Error("owner-1", "x"), TaskPreviewState.Error("owner-1", "x").forSession("owner-1"))
        assertNull(TaskPreviewState.Error(null, "x").forSession("owner-1"))
    }

    @Test
    fun `backend preview states map to ui previews`() {
        fun map(state: TaskPreviewState?) = state.toUiPreview(geometry, "owner-1", "Shop", "Checking", "dhd_observe")
        val connecting = LiveDisplayPreviewState(
            status = LiveDisplayPreviewStatus.CONNECTING,
            appLabel = "Shop",
            aspectRatio = ratio,
            sessionKey = "owner-1",
            purpose = "Checking",
            currentToolName = "dhd_observe",
        )
        assertEquals(connecting, map(TaskPreviewState.Connecting(session("owner-1"))))
        assertEquals(connecting.copy(status = LiveDisplayPreviewStatus.LIVE), map(TaskPreviewState.Attached(session("owner-1"))))
        assertEquals(
            connecting.copy(status = LiveDisplayPreviewStatus.ERROR, message = "decoder failed"),
            map(TaskPreviewState.Error("owner-1", "decoder failed")),
        )
        assertEquals(
            connecting.copy(status = LiveDisplayPreviewStatus.UNAVAILABLE, message = "ended"),
            map(TaskPreviewState.Ended(session("owner-1"), "ended")),
        )
        assertNull(map(TaskPreviewState.Detached))
        assertNull(map(null))
    }

    @Test
    fun `lifecycles map from backend and coordinator states`() {
        assertEquals(
            TaskDisplayLifecycle.entries.map { it.name },
            TaskDisplayStatus.entries.map { it.toUiLifecycle().name },
        )
        assertEquals(TaskDisplayLifecycle.RUNNING, running().toUiDisplayLifecycle())
        assertEquals(TaskDisplayLifecycle.PAUSED, running(attention = "Approve").toUiDisplayLifecycle())
        assertEquals(TaskDisplayLifecycle.PAUSED, SessionState.Paused("run-1", "Buy", "Paused", startedAtEpochMs = 0L).toUiDisplayLifecycle())
        assertEquals(TaskDisplayLifecycle.STOPPED, SessionState.Stopped("run-1", "x").toUiDisplayLifecycle())
        assertEquals(TaskDisplayLifecycle.COMPLETED, SessionState.Completed("run-1", "x").toUiDisplayLifecycle())
        assertEquals(TaskDisplayLifecycle.UNAVAILABLE, SessionState.Idle.toUiDisplayLifecycle())
        assertEquals(5_000L, running().startedAtEpochMsOrZero())
        assertEquals(0L, SessionState.Completed("run-1", "x").startedAtEpochMsOrZero())
    }

    @Test
    fun `active record uses the current package and coordinator state`() {
        val displaySession = session("owner-1")
        assertEquals("com.example.mail", currentPackageForSession(displaySession, listOf(record("owner-1", "com.example.mail"))))
        assertEquals("com.example.shop", currentPackageForSession(displaySession, listOf(record("owner-2", "com.example.mail"))))
        assertEquals(
            TaskDisplayUiRecord(
                sessionKey = "owner-1",
                taskId = "owner-1@7",
                packageName = "com.example.mail",
                appLabel = "Mail",
                displayId = 7,
                displayRef = taskDisplayReference("owner-1", 7),
                geometry = geometry,
                lifecycle = TaskDisplayLifecycle.RUNNING,
                currentPurpose = "Opening Shop",
                createdAtEpochMs = 5_000L,
                currentToolName = "dhd_open_app",
                previewState = null,
            ),
            activeDisplayUiRecord(displaySession, "com.example.mail", "Mail", running(), "Opening Shop", "dhd_open_app", null),
        )
    }

    @Test
    fun `active record merges into persisted records`() {
        val preview = LiveDisplayPreviewState.live(sessionKey = "owner-1")
        val persisted = TaskDisplayUiRecord(
            sessionKey = "owner-1",
            lifecycle = TaskDisplayLifecycle.COMPLETED,
            currentPurpose = "Task complete",
            currentToolName = "dhd_observe",
        )
        val other = TaskDisplayUiRecord(sessionKey = "owner-2")
        val active = TaskDisplayUiRecord(sessionKey = "owner-1", lifecycle = TaskDisplayLifecycle.RUNNING)

        assertEquals(
            listOf(
                persisted.copy(
                    lifecycle = TaskDisplayLifecycle.RUNNING,
                    currentPurpose = "Opening Shop",
                    currentToolName = "dhd_open_app",
                    previewState = preview,
                ),
                other,
            ),
            mergeActiveDisplayRecord(listOf(persisted, other), active, true, "Opening Shop", "dhd_open_app", preview),
        )
        assertEquals(
            listOf(persisted.copy(lifecycle = TaskDisplayLifecycle.RUNNING), other),
            mergeActiveDisplayRecord(listOf(persisted, other), active, true, null, null, null),
        )
        assertEquals(
            listOf(persisted.copy(previewState = preview), other),
            mergeActiveDisplayRecord(listOf(persisted, other), active, false, "Opening Shop", "dhd_open_app", preview),
        )
        assertEquals(
            listOf(other, active),
            mergeActiveDisplayRecord(listOf(other), active, true, "Opening Shop", null, null),
        )
    }
}
