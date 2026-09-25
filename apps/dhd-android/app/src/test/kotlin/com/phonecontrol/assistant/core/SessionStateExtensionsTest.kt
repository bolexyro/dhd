package com.phonecontrol.assistant.core

import com.phonecontrol.assistant.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateExtensionsTest {
    private val running = SessionState.Running(
        sessionId = "run-1",
        request = "Check status",
        currentPurpose = "needs YOUR attention",
        startedAtEpochMs = 1L,
        conversationId = "conversation-1",
    )
    private val paused = SessionState.Paused(
        sessionId = "run-2",
        request = "Check status",
        currentPurpose = "Reading",
        startedAtEpochMs = 1L,
        conversationId = "conversation-2",
    )
    private val stopped = SessionState.Stopped(sessionId = "run-3", reason = "Stopped", conversationId = "conversation-3")
    private val completed = SessionState.Completed(sessionId = "run-4", message = "Done", conversationId = "conversation-4")

    @Test
    fun `ids come from every non idle state`() {
        assertEquals(
            listOf(null, "run-1", "run-2", "run-3", "run-4"),
            listOf(SessionState.Idle, running, paused, stopped, completed).map { it.sessionIdOrNull },
        )
        assertEquals(
            listOf(null, "conversation-1", "conversation-2", "conversation-3", "conversation-4"),
            listOf(SessionState.Idle, running, paused, stopped, completed).map { it.conversationIdOrNull },
        )
    }

    @Test
    fun `only running and paused states are active and carry a purpose`() {
        assertEquals(
            listOf(false, true, true, false, false),
            listOf(SessionState.Idle, running, paused, stopped, completed).map { it.isActive },
        )
        assertEquals("Reading", paused.currentPurposeOrNull)
        assertNull(stopped.currentPurposeOrNull)
    }

    @Test
    fun `attention matches the coordinator copy ignoring case`() {
        assertTrue(running.needsAttention)
        assertFalse(paused.needsAttention)
        assertFalse(completed.needsAttention)
    }
}
