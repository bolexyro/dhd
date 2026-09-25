package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.data.RunStatus
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateMachineTest {
    private val running = SessionState.Running(
        sessionId = "run-1",
        request = "Buy milk",
        currentPurpose = "Planning",
        startedAtEpochMs = 1_000L,
        conversationId = "conversation-1",
        reasoningEffort = "xhigh",
        fastMode = true,
        elapsedBeforeStartMs = 500L,
    )

    @Test
    fun `start normalizes the request and effort and opens the run before committing`() {
        val transition = SessionStateMachine.reduce(
            SessionState.Idle,
            SessionEvent.Start("run-2", "  Buy bread  ", "conversation-2", "unknown", false, 2_000L),
        )!!

        val state = transition.state as SessionState.Running
        assertEquals("Buy bread", state.request)
        assertEquals("high", state.reasoningEffort)
        assertEquals(2_000L, state.startedAtEpochMs)
        assertEquals(
            listOf(
                SessionEffect.ForgetCompletedAttentions,
                SessionEffect.ClearPointer,
                SessionEffect.OpenRun("run-2", "  Buy bread  ", "conversation-2"),
                SessionEffect.ReleaseClaim,
                SessionEffect.ClearToolCalls,
                SessionEffect.CommitState,
                SessionEffect.AppendEvent(
                    ActivityEventKind.SESSION_STARTED,
                    "Request accepted. Waiting for the desktop Codex bridge.",
                    "run-2",
                ),
            ),
            transition.effects,
        )
    }

    @Test
    fun `start is rejected for a blank request or an active session`() {
        assertNull(SessionStateMachine.reduce(SessionState.Idle, SessionEvent.Start("id", "  ", null, "high", false, 0L)))
        assertNull(SessionStateMachine.reduce(running, SessionEvent.Start("id", "Buy", null, "high", false, 0L)))
    }

    @Test
    fun `pause accumulates the active time of the running segment`() {
        val paused = SessionStateMachine.reduce(running, SessionEvent.Pause(4_000L))!!.state as SessionState.Paused

        assertEquals(3_500L, paused.elapsedBeforeStartMs)
        assertEquals(4_000L, paused.startedAtEpochMs)
    }

    @Test
    fun `stop keeps the continuation settings and releases the run in order`() {
        val transition = SessionStateMachine.reduce(running, SessionEvent.Stop("Stopped by the user.", 3_000L))!!

        assertEquals(
            SessionState.Stopped(
                sessionId = "run-1",
                reason = "Stopped by the user.",
                conversationId = "conversation-1",
                reasoningEffort = "xhigh",
                fastMode = true,
                request = "Buy milk",
                workedDurationMs = 2_500L,
            ),
            transition.state,
        )
        assertEquals(
            listOf(
                SessionEffect.SettleAttention("run-1"),
                SessionEffect.CancelTransport("run-1"),
                SessionEffect.RetainDisplay("run-1", TaskDisplayStatus.STOPPED, "Stopped by the user."),
                SessionEffect.ReleaseClaim,
                SessionEffect.ClearSteers("run-1"),
                SessionEffect.CommitState,
                SessionEffect.ClearPointer,
                SessionEffect.CompleteRun("run-1", RunStatus.STOPPED, assistantText = null),
                SessionEffect.AppendEvent(ActivityEventKind.SESSION_STOPPED, "Stopped by the user.", "run-1"),
            ),
            transition.effects,
        )
    }

    @Test
    fun `completion with feedback persists no fallback text and emits the agent message first`() {
        val transition = SessionStateMachine.reduce(
            running,
            SessionEvent.Complete("Fallback", "  Done it.  ", " message-1 ", 3_000L),
        )!!

        assertEquals("Done it.", (transition.state as SessionState.Completed).message)
        assertEquals(
            listOf(
                SessionEffect.CompleteRun("run-1", RunStatus.COMPLETED, assistantText = null),
                SessionEffect.AppendEvent(ActivityEventKind.AGENT_MESSAGE, "Done it.", "run-1", eventId = "message-1"),
                SessionEffect.AppendEvent(ActivityEventKind.SESSION_COMPLETED, "Task completed.", "run-1"),
            ),
            transition.effects.takeLast(3),
        )
    }

    @Test
    fun `known bug complete is accepted after a stop`() {
        val stopped = SessionStateMachine.reduce(running, SessionEvent.Stop("Stopped by the user.", 3_000L))!!.state

        val completed = SessionStateMachine.reduce(stopped, SessionEvent.Complete("Late.", null, null, 4_000L))!!.state

        assertEquals(SessionState.Completed("run-1", "Late.", "conversation-1", workedDurationMs = 2_500L), completed)
    }

    @Test
    fun `reset from idle only clears local state`() {
        val transition = SessionStateMachine.reduce(SessionState.Idle, SessionEvent.Reset)!!

        assertEquals(SessionState.Idle, transition.state)
        assertEquals(
            listOf(
                SessionEffect.CancelAllAttention,
                SessionEffect.ClearAllSteers,
                SessionEffect.ReleaseClaim,
                SessionEffect.ClearPointer,
                SessionEffect.ClearToolCalls,
                SessionEffect.ClearEvents,
                SessionEffect.CommitState,
            ),
            transition.effects,
        )
    }

    @Test
    fun `the stored conversation id replaces the requested one only for running states`() {
        assertEquals("stored", (running.withStoredConversation("stored") as SessionState.Running).conversationId)
        assertEquals(running, running.withStoredConversation(null))
        assertEquals(SessionState.Idle, SessionState.Idle.withStoredConversation("stored"))
    }
}
