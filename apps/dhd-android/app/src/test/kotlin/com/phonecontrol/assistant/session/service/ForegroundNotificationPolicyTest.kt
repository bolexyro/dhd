package com.phonecontrol.assistant.session.service

import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.session.defaultDhdToolPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundNotificationPolicyTest {
    @Test
    fun `only running or paused work requires the foreground notification`() {
        val running = SessionState.Running(
            sessionId = "run-1",
            request = "Check status",
            currentPurpose = "Preparing request",
            startedAtEpochMs = 1L,
        )
        val paused = SessionState.Paused(
            sessionId = "run-1",
            request = "Check status",
            currentPurpose = "Needs user input",
            startedAtEpochMs = 1L,
        )

        assertTrue(running.isActive)
        assertTrue(paused.isActive)
        assertFalse(SessionState.Idle.isActive)
        assertFalse(SessionState.Stopped(sessionId = "run-1", reason = "Stopped").isActive)
        assertFalse(SessionState.Completed(sessionId = "run-1", message = "Done").isActive)
    }

    @Test
    fun `active tool notification purpose comes from the latest running metadata for this session`() {
        val state = SessionState.Running(
            sessionId = "run-1",
            request = "Check status",
            currentPurpose = "Tapping the status button",
            startedAtEpochMs = 1L,
        )
        val calls = listOf(
            toolCall("done", "run-1", "Old metadata purpose", DhdToolCallStatus.COMPLETED),
            toolCall("other-session", "run-2", "Unrelated metadata", DhdToolCallStatus.RUNNING),
            toolCall("current", "run-1", "Read the latest account status", DhdToolCallStatus.RUNNING),
        )

        assertEquals("Read the latest account status", state.activeToolPurpose(calls))
    }

    @Test
    fun `active tool notification purpose is absent when no matching tool is running`() {
        val state = SessionState.Running(
            sessionId = "run-1",
            request = "Check status",
            currentPurpose = "Thinking",
            startedAtEpochMs = 1L,
        )
        val calls = listOf(
            toolCall("done", "run-1", "Completed purpose", DhdToolCallStatus.COMPLETED),
            toolCall("attention", "run-1", "Waiting for user", DhdToolCallStatus.ATTENTION),
        )

        assertNull(state.activeToolPurpose(calls))
        assertNull(SessionState.Idle.activeToolPurpose(calls))
    }

    @Test
    fun `foreground notification prefers raw action metadata over rewritten display label`() {
        val openApp = SessionState.Running(
            sessionId = "run-1",
            request = "Open the app",
            currentPurpose = "Opening Maps",
            currentToolMetadataPurpose = "Open the map app and search for the saved place",
            startedAtEpochMs = 1L,
        )
        val wait = openApp.copy(
            currentPurpose = "Waiting for the screen",
            currentToolMetadataPurpose = "Wait until the results finish loading",
        )

        assertEquals(
            "Open the map app and search for the saved place",
            openApp.foregroundNotificationStatus(emptyList()),
        )
        assertEquals(
            "Wait until the results finish loading",
            wait.foregroundNotificationStatus(emptyList()),
        )
    }

    @Test
    fun `planning notification says DHD-ing and browse app fallback is consistent`() {
        val planning = SessionState.Running(
            sessionId = "run-1",
            request = "Check status",
            currentPurpose = "DHD is planning",
            startedAtEpochMs = 1L,
        )

        assertEquals("DHD-ing…", planning.foregroundNotificationStatus(emptyList()))
        assertEquals("Browsing installed apps", defaultDhdToolPurpose("dhd_browse_app"))
    }

    @Test
    fun `attention notification takes priority over retained tool metadata`() {
        val paused = SessionState.Paused(
            sessionId = "run-1",
            request = "Check status",
            currentPurpose = "Needs your attention",
            currentToolMetadataPurpose = "Opening Maps",
            startedAtEpochMs = 1L,
        )

        assertEquals(
            "Paused · DHD needs your attention",
            paused.foregroundNotificationStatus(emptyList()),
        )
    }

    private fun toolCall(
        id: String,
        sessionId: String,
        purpose: String,
        status: DhdToolCallStatus,
    ) = DhdToolCall(
        id = id,
        sessionId = sessionId,
        toolName = "dhd_execute",
        purpose = purpose,
        status = status,
        startedAtEpochMs = 1L,
    )
}
