package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.execution.PhoneActionTransport
import com.phonecontrol.assistant.execution.TransportResult
import com.phonecontrol.assistant.policy.PolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTerminalTransitionTest {
    private enum class Start { IDLE, RUNNING, PAUSED, STOPPED, FAILED, COMPLETED }

    private data class Outcome(val accepted: Boolean, val state: String, val detail: String = "")

    private fun coordinator(): SessionCoordinator = SessionCoordinator(
        enabledPackagesProvider = { emptySet() },
        policyEngine = PolicyEngine(),
        transport = object : PhoneActionTransport {
            override suspend fun execute(action: PhoneAction, observation: ObservationSnapshot?): TransportResult =
                TransportResult.Succeeded("executed")
        },
    )

    private fun SessionCoordinator.arrange(start: Start): SessionCoordinator = apply {
        when (start) {
            Start.IDLE -> Unit
            Start.RUNNING -> check(start("Buy milk", "conversation-1"))
            Start.PAUSED -> check(start("Buy milk", "conversation-1") && pause())
            Start.STOPPED -> check(start("Buy milk", "conversation-1") && stop("Stopped by the user."))
            Start.FAILED -> check(start("Buy milk", "conversation-1") && fail("Codex crashed."))
            Start.COMPLETED -> check(start("Buy milk", "conversation-1") && complete("Milk added."))
        }
    }

    private fun SessionCoordinator.describe(accepted: Boolean): Outcome = when (val state = state.value) {
        SessionState.Idle -> Outcome(accepted, "Idle")
        is SessionState.Running -> Outcome(accepted, "Running", if (state.isContinuation) "continuation" else "")
        is SessionState.Paused -> Outcome(accepted, "Paused")
        is SessionState.Stopped -> Outcome(accepted, "Stopped", state.reason)
        is SessionState.Completed -> Outcome(accepted, "Completed", state.message)
    }

    private fun assertTransitions(
        event: String,
        apply: SessionCoordinator.() -> Boolean,
        expected: Map<Start, Outcome>,
    ) {
        assertEquals(Start.entries.toSet(), expected.keys)
        expected.forEach { (start, outcome) ->
            val coordinator = coordinator().arrange(start)
            assertEquals("$event from $start", outcome, coordinator.describe(coordinator.apply()))
        }
    }

    @Test
    fun `start only leaves inactive states`() = assertTransitions(
        event = "start",
        apply = { start("Buy bread") },
        expected = mapOf(
            Start.IDLE to Outcome(true, "Running"),
            Start.RUNNING to Outcome(false, "Running"),
            Start.PAUSED to Outcome(false, "Paused"),
            Start.STOPPED to Outcome(true, "Running"),
            Start.FAILED to Outcome(true, "Running"),
            Start.COMPLETED to Outcome(true, "Running"),
        ),
    )

    @Test
    fun `pause only applies to a running session`() = assertTransitions(
        event = "pause",
        apply = { pause() },
        expected = mapOf(
            Start.IDLE to Outcome(false, "Idle"),
            Start.RUNNING to Outcome(true, "Paused"),
            Start.PAUSED to Outcome(false, "Paused"),
            Start.STOPPED to Outcome(false, "Stopped", "Stopped by the user."),
            Start.FAILED to Outcome(false, "Stopped", "Failed: Codex crashed."),
            Start.COMPLETED to Outcome(false, "Completed", "Milk added."),
        ),
    )

    @Test
    fun `resume only applies to a paused session`() = assertTransitions(
        event = "resume",
        apply = { resume() },
        expected = mapOf(
            Start.IDLE to Outcome(false, "Idle"),
            Start.RUNNING to Outcome(false, "Running"),
            Start.PAUSED to Outcome(true, "Running"),
            Start.STOPPED to Outcome(false, "Stopped", "Stopped by the user."),
            Start.FAILED to Outcome(false, "Stopped", "Failed: Codex crashed."),
            Start.COMPLETED to Outcome(false, "Completed", "Milk added."),
        ),
    )

    @Test
    fun `stop only applies to active sessions`() = assertTransitions(
        event = "stop",
        apply = { stop("Late stop.") },
        expected = mapOf(
            Start.IDLE to Outcome(false, "Idle"),
            Start.RUNNING to Outcome(true, "Stopped", "Late stop."),
            Start.PAUSED to Outcome(true, "Stopped", "Late stop."),
            Start.STOPPED to Outcome(false, "Stopped", "Stopped by the user."),
            Start.FAILED to Outcome(false, "Stopped", "Failed: Codex crashed."),
            Start.COMPLETED to Outcome(false, "Completed", "Milk added."),
        ),
    )

    @Test
    fun `complete only applies to active sessions`() = assertTransitions(
        event = "complete",
        apply = { complete("Late completion.") },
        expected = mapOf(
            Start.IDLE to Outcome(false, "Idle"),
            Start.RUNNING to Outcome(true, "Completed", "Late completion."),
            Start.PAUSED to Outcome(true, "Completed", "Late completion."),
            Start.STOPPED to Outcome(false, "Stopped", "Stopped by the user."),
            Start.FAILED to Outcome(false, "Stopped", "Failed: Codex crashed."),
            Start.COMPLETED to Outcome(false, "Completed", "Milk added."),
        ),
    )

    @Test
    fun `late completion keeps a user stop`() {
        val coordinator = coordinator().arrange(Start.STOPPED)
        val sessionId = (coordinator.state.value as SessionState.Stopped).sessionId
        assertEquals(
            Outcome(false, "Stopped", "Stopped by the user."),
            coordinator.describe(coordinator.complete("Late completion.")),
        )
        assertEquals(sessionId, (coordinator.state.value as SessionState.Stopped).sessionId)
    }

    @Test
    fun `fail only applies to active sessions`() = assertTransitions(
        event = "fail",
        apply = { fail("Late failure.") },
        expected = mapOf(
            Start.IDLE to Outcome(false, "Idle"),
            Start.RUNNING to Outcome(true, "Stopped", "Failed: Late failure."),
            Start.PAUSED to Outcome(true, "Stopped", "Failed: Late failure."),
            Start.STOPPED to Outcome(false, "Stopped", "Stopped by the user."),
            Start.FAILED to Outcome(false, "Stopped", "Failed: Codex crashed."),
            Start.COMPLETED to Outcome(false, "Completed", "Milk added."),
        ),
    )

    @Test
    fun `continue only applies to stopped sessions including failed ones`() = assertTransitions(
        event = "continueStopped",
        apply = { continueStopped() },
        expected = mapOf(
            Start.IDLE to Outcome(false, "Idle"),
            Start.RUNNING to Outcome(false, "Running"),
            Start.PAUSED to Outcome(false, "Paused"),
            Start.STOPPED to Outcome(true, "Running", "continuation"),
            Start.FAILED to Outcome(true, "Running", "continuation"),
            Start.COMPLETED to Outcome(false, "Completed", "Milk added."),
        ),
    )

    @Test
    fun `reset always returns to idle`() = assertTransitions(
        event = "reset",
        apply = { reset() },
        expected = Start.entries.associateWith { Outcome(true, "Idle") },
    )

    @Test
    fun `attention can only be requested while active`() = assertTransitions(
        event = "requestAttention",
        apply = { requestAttention("Approve the payment") },
        expected = mapOf(
            Start.IDLE to Outcome(false, "Idle"),
            Start.RUNNING to Outcome(true, "Running"),
            Start.PAUSED to Outcome(true, "Paused"),
            Start.STOPPED to Outcome(false, "Stopped", "Stopped by the user."),
            Start.FAILED to Outcome(false, "Stopped", "Failed: Codex crashed."),
            Start.COMPLETED to Outcome(false, "Completed", "Milk added."),
        ),
    )

    @Test
    fun `terminal states keep the conversation and request for continuation`() {
        val stopped = coordinator().arrange(Start.STOPPED).state.value as SessionState.Stopped
        assertEquals("conversation-1", stopped.conversationId)
        assertEquals("Buy milk", stopped.request)
        val completed = coordinator().arrange(Start.COMPLETED).state.value as SessionState.Completed
        assertEquals("conversation-1", completed.conversationId)
    }

    @Test
    fun `completion prefers agent feedback and trims long messages`() {
        val withFeedback = coordinator().arrange(Start.RUNNING)
        withFeedback.complete("Short.", agentFeedback = "  Detailed feedback.  ")
        assertEquals("Detailed feedback.", (withFeedback.state.value as SessionState.Completed).message)

        val blank = coordinator().arrange(Start.RUNNING)
        blank.complete("   ")
        assertEquals("Session completed.", (blank.state.value as SessionState.Completed).message)

        val long = coordinator().arrange(Start.RUNNING)
        long.complete("m".repeat(300))
        assertEquals(240, (long.state.value as SessionState.Completed).message.length)
    }

    @Test
    fun `failure reason defaults and is prefixed`() {
        val coordinator = coordinator().arrange(Start.RUNNING)
        coordinator.fail("   ")
        assertEquals("Failed: The desktop Codex turn failed.", (coordinator.state.value as SessionState.Stopped).reason)
    }
}
