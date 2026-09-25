package com.phonecontrol.assistant.overlay

import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPanelLogicTest {
    private fun running(purpose: String = "Opening Shop", attentionReason: String? = null) = SessionState.Running(
        sessionId = "run-1",
        request = "Buy milk",
        currentPurpose = purpose,
        startedAtEpochMs = 0L,
        attentionReason = attentionReason,
    )

    private fun call(
        id: String,
        purpose: String,
        status: DhdToolCallStatus = DhdToolCallStatus.COMPLETED,
        sessionId: String = "run-1",
        toolName: String = "dhd_observe",
    ) = DhdToolCall(id, sessionId, toolName, purpose, status, startedAtEpochMs = 0L)

    @Test
    fun `explicit bubble wins then recovery then work`() {
        OverlayPanelMode.entries.forEach { mode ->
            assertEquals(OverlayPanelMode.BUBBLE, effectiveOverlayPanelMode(OverlayPanelMode.BUBBLE, active = true, hasRecovery = true))
            val expectedActive = if (mode == OverlayPanelMode.BUBBLE) OverlayPanelMode.BUBBLE else OverlayPanelMode.WORKING
            assertEquals(expectedActive, effectiveOverlayPanelMode(mode, active = true, hasRecovery = false))
            val expectedRecovery = if (mode == OverlayPanelMode.BUBBLE) OverlayPanelMode.BUBBLE else OverlayPanelMode.ATTENTION
            assertEquals(expectedRecovery, effectiveOverlayPanelMode(mode, active = true, hasRecovery = true))
            assertEquals(mode, effectiveOverlayPanelMode(mode, active = false, hasRecovery = true))
        }
    }

    @Test
    fun `working row only counts this session and skips close display calls`() {
        val calls = listOf(
            call("1", "Observing"),
            call("2", "Other run", sessionId = "run-2"),
            call("3", "Closing", toolName = "DHD_CLOSE_DISPLAY"),
            call("4", "Closing legacy", toolName = "close_display"),
        )
        assertEquals(listOf("1"), workingRowSessionCalls(running(), calls).map { it.id })
        assertEquals(emptyList<DhdToolCall>(), workingRowSessionCalls(SessionState.Idle, calls))
    }

    @Test
    fun `working row task text follows attention recovery and tool calls`() {
        val calls = listOf(call("1", "Observing"), call("2", "Tapping cart", DhdToolCallStatus.RUNNING), call("3", "Typing"))
        assertEquals("Tapping cart", workingRowTask(running(), calls, null, "Thinking"))
        assertEquals("Typing", workingRowTask(running(), calls.filter { it.status != DhdToolCallStatus.RUNNING }, null, "Thinking"))
        assertEquals("Thinking", workingRowTask(running(), emptyList(), null, "Thinking"))
        assertEquals(
            "Paused",
            workingRowTask(SessionState.Paused("run-1", "Buy milk", "Paused", startedAtEpochMs = 0L), calls, null, "Thinking"),
        )
        assertEquals(
            "Approve the payment",
            workingRowTask(running("Needs your attention", "Approve the payment"), calls, OverlayRecoveryKind.COMPANION, "Thinking"),
        )
        assertEquals(
            "Needs your attention",
            workingRowTask(running("NEEDS YOUR ATTENTION", "  "), calls, null, "Thinking"),
        )
        assertEquals("Desktop companion not connected", workingRowTask(running(), calls, OverlayRecoveryKind.COMPANION, "Thinking"))
        assertEquals("Phone access needed", workingRowTask(running(), calls, OverlayRecoveryKind.DEVELOPER, "Thinking"))
        assertEquals("Tapping cart", workingRowTask(running(), calls, OverlayRecoveryKind.ATTENTION, "Thinking"))
    }
}
