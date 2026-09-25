package com.phonecontrol.assistant.bridge

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSessionWireGoldenTest {
    @Test
    fun `empty request line is rejected without a request id`() = runTest {
        BridgeHarness().rawExchange(
            name = "error.empty_request",
            line = null,
            description = JSONObject().put("line", JSONObject.NULL),
        )
    }

    @Test
    fun `oversized request line is rejected before parsing`() = runTest {
        BridgeHarness().rawExchange(
            name = "error.request_too_large",
            line = "x".repeat(16_385),
            description = JSONObject().put("lineLength", 16_385).put("fill", "x"),
        )
    }

    @Test
    fun `request line that is not json is rejected`() = runTest {
        BridgeHarness().rawExchange(
            name = "error.invalid_json",
            line = "not json",
            description = JSONObject().put("line", "not json"),
        )
    }

    @Test
    fun `lan peer without the pairing token is rejected`() = runTest {
        BridgeHarness().exchange("error.auth_required", request("status"), peer = LAN_PEER)
    }

    @Test
    fun `lan peer with a wrong pairing token is rejected`() = runTest {
        BridgeHarness().exchange(
            "error.auth_wrong_token",
            request("status", "authToken" to "not-the-token"),
            peer = LAN_PEER,
        )
    }

    @Test
    fun `lan peer with the pairing token is accepted`() = runTest {
        BridgeHarness().exchange(
            "status.lan_authorized",
            request("status", "authToken" to FIXTURE_TOKEN),
            peer = LAN_PEER,
        )
    }

    @Test
    fun `unknown request type is accepted then rejected`() = runTest {
        BridgeHarness().exchange("error.unsupported_type", request("bogus"))
    }

    @Test
    fun `handler failures are reported with the request id`() = runTest {
        BridgeHarness().exchange("error.handler_failure", request("claim_steer", "steerId" to "steer-1"))
    }

    @Test
    fun `missing request id is generated`() = runTest {
        BridgeHarness().exchange("heartbeat.generated_request_id", JSONObject().put("type", "heartbeat"))
    }

    @Test
    fun `start session returns the running session`() = runTest {
        val harness = BridgeHarness()
        harness.exchange(
            "start_session.started",
            request(
                "start_session",
                "request" to "Buy milk",
                "conversationId" to "conversation-1",
                "reasoningEffort" to "xhigh",
                "fastMode" to true,
            ),
        )
        assertEquals(
            "startSessionService:Buy milk:xhigh:true:conversation-1",
            harness.platform.calls.single(),
        )
    }

    @Test
    fun `start session normalizes an unknown reasoning effort`() = runTest {
        BridgeHarness().exchange(
            "start_session.default_settings",
            request("start_session", "request" to "Buy milk", "reasoningEffort" to "turbo"),
        )
    }

    @Test
    fun `start session is refused while a session is active`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("start_session.already_active", request("start_session", "request" to "Buy bread"))
    }

    @Test
    fun `start session requires a request`() = runTest {
        BridgeHarness().exchange("start_session.invalid_request", request("start_session", "request" to " "))
    }

    @Test
    fun `status while idle`() = runTest {
        BridgeHarness().exchange("status.idle", request("status"))
    }

    @Test
    fun `status while running`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("status.running", request("status"))
    }

    @Test
    fun `status while paused`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        assertTrue(harness.coordinator.pause())
        harness.exchange("status.paused", request("status"))
    }

    @Test
    fun `status after stop`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        assertTrue(harness.coordinator.stop())
        harness.exchange("status.stopped", request("status"))
    }

    @Test
    fun `status after completion`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        assertTrue(harness.coordinator.complete("Done."))
        harness.exchange("status.completed", request("status"))
    }

    @Test
    fun `status does not refresh companion presence`() = runTest {
        val harness = BridgeHarness()
        harness.send(request("status"))
        assertEquals(false, harness.server.companionConnected.value)
        harness.send(request("heartbeat"))
        assertEquals(true, harness.server.companionConnected.value)
    }

    @Test
    fun `heartbeat is acknowledged`() = runTest {
        BridgeHarness().exchange("heartbeat.acknowledged", request("heartbeat"))
    }

    @Test
    fun `companion disconnect releases presence`() = runTest {
        val harness = BridgeHarness()
        harness.send(request("heartbeat"))
        harness.exchange("companion_disconnected.released", request("companion_disconnected"))
        assertEquals(false, harness.server.companionConnected.value)
    }

    @Test
    fun `pending request without a session`() = runTest {
        BridgeHarness().exchange("pending_request.none", request("pending_request"))
    }

    @Test
    fun `pending request with a running session and a warmup`() = runTest {
        val harness = BridgeHarness()
        harness.server.requestCodexWarmup()
        harness.startSession()
        harness.exchange("pending_request.available", request("pending_request"))
    }

    @Test
    fun `claim request claims the running session`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("claim_request.claimed", request("claim_request", "sessionId" to "{{sessionId}}"))
    }

    @Test
    fun `claim request without a session`() = runTest {
        BridgeHarness().exchange("claim_request.not_available", request("claim_request", "sessionId" to "missing"))
    }

    @Test
    fun `release request after a claim`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.coordinator.claimRequest()
        harness.exchange("release_request.released", request("release_request", "sessionId" to "{{sessionId}}"))
    }

    @Test
    fun `release request that was never claimed`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("release_request.not_claimed", request("release_request", "sessionId" to "{{sessionId}}"))
    }

    @Test
    fun `pending steer while idle`() = runTest {
        BridgeHarness().exchange("pending_steer.idle", request("pending_steer"))
    }

    @Test
    fun `pending steer with a queued steer`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.enqueueSteer("Use the blue one")
        harness.exchange("pending_steer.available", request("pending_steer", "sessionId" to "{{sessionId}}"))
    }

    @Test
    fun `pending steer is hidden while attention is pending`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.enqueueSteer("Use the blue one")
        assertTrue(harness.coordinator.requestAttention("Approve the payment"))
        harness.exchange("pending_steer.attention_pending", request("pending_steer"))
    }

    @Test
    fun `claim steer claims a queued steer`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.enqueueSteer("Use the blue one")
        harness.exchange(
            "claim_steer.claimed",
            request("claim_steer", "sessionId" to "{{sessionId}}", "steerId" to "{{steerId}}"),
        )
    }

    @Test
    fun `claim steer with an unknown steer`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange(
            "claim_steer.not_available",
            request("claim_steer", "sessionId" to "{{sessionId}}", "steerId" to "missing"),
        )
    }

    @Test
    fun `release steer puts a claimed steer back`() = runTest {
        val harness = BridgeHarness()
        val sessionId = harness.startSession()
        val steerId = harness.enqueueSteer("Use the blue one")
        harness.coordinator.claimSteer(sessionId, steerId)
        harness.exchange(
            "release_steer.released",
            request("release_steer", "sessionId" to "{{sessionId}}", "steerId" to "{{steerId}}"),
        )
    }

    @Test
    fun `release steer that was not claimed`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange(
            "release_steer.not_claimed",
            request("release_steer", "sessionId" to "{{sessionId}}", "steerId" to "missing"),
        )
    }

    @Test
    fun `complete steer acknowledges delivery`() = runTest {
        val harness = BridgeHarness()
        val sessionId = harness.startSession()
        val steerId = harness.enqueueSteer("Use the blue one")
        harness.coordinator.claimSteer(sessionId, steerId)
        harness.exchange(
            "complete_steer.delivered",
            request("complete_steer", "sessionId" to "{{sessionId}}", "steerId" to "{{steerId}}"),
        )
    }

    @Test
    fun `complete steer that was not claimed`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange(
            "complete_steer.not_claimed",
            request("complete_steer", "sessionId" to "{{sessionId}}", "steerId" to "missing"),
        )
    }

    @Test
    fun `bind codex thread without a conversation store`() = runTest {
        BridgeHarness().exchange(
            "bind_codex_thread.without_store",
            request("bind_codex_thread", "conversationId" to "conversation-1", "codexThreadId" to "thread-1"),
        )
    }

    @Test
    fun `stream agent message to the running session`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange(
            "stream_agent_message.streamed",
            request(
                "stream_agent_message",
                "sessionId" to "{{sessionId}}",
                "messageId" to "message-1",
                "text" to "Looking for milk",
            ),
        )
    }

    @Test
    fun `stream agent message for another session`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange(
            "stream_agent_message.session_not_running",
            request("stream_agent_message", "sessionId" to "other", "messageId" to "message-1", "text" to "Hi"),
        )
    }

    @Test
    fun `complete session with feedback`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange(
            "complete_session.with_feedback",
            request(
                "complete_session",
                "sessionId" to "{{sessionId}}",
                "message" to "Milk added.",
                "feedback" to "I added one litre of milk to the cart.",
                "agentMessageId" to "message-1",
            ),
        )
        assertEquals(
            listOf(
                "showCompletionNotification:I added one litre of milk to the cart.:conversation-1",
                "removeAttentionNotification",
                "reconcileServiceLifetime",
            ),
            harness.platform.calls,
        )
    }

    @Test
    fun `complete session with the default message`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("complete_session.default_message", request("complete_session", "sessionId" to "{{sessionId}}"))
    }

    @Test
    fun `complete session for another session`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("complete_session.session_not_running", request("complete_session", "sessionId" to "other"))
    }

    @Test
    fun `complete session after a stop is accepted today`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        assertTrue(harness.coordinator.stop("Stopped by the user."))
        harness.exchange(
            "complete_session.after_stop_known_bug",
            request("complete_session", "sessionId" to "{{sessionId}}", "message" to "Late completion."),
        )
    }

    @Test
    fun `fail session reports the reason`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange(
            "fail_session.failed",
            request("fail_session", "sessionId" to "{{sessionId}}", "reason" to "Codex crashed."),
        )
        assertEquals(
            listOf("showAttentionNotification:DHD stopped: Codex crashed.:conversation-1", "reconcileServiceLifetime"),
            harness.platform.calls,
        )
    }

    @Test
    fun `fail session for another session`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("fail_session.session_not_running", request("fail_session", "sessionId" to "other"))
    }

    @Test
    fun `stop session stops the active session`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("stop_session.active", request("stop_session", "reason" to "User changed their mind."))
    }

    @Test
    fun `stop session without a session`() = runTest {
        BridgeHarness().exchange("stop_session.idle", request("stop_session"))
    }
}
