package com.phonecontrol.assistant.bridge.handlers

import com.phonecontrol.assistant.bridge.BRIDGE_LOG_TAG
import com.phonecontrol.assistant.bridge.BridgePlatform
import com.phonecontrol.assistant.bridge.presence.CompanionPresence
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_AGENT_FEEDBACK_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_REQUEST_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_TEXT_CHARS
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.protocol.sessionStateName
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.core.conversationIdOrNull
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import org.json.JSONObject

internal class SessionHandlers(
    private val coordinator: SessionCoordinator,
    private val platform: BridgePlatform,
    private val presence: CompanionPresence,
) {
    fun startSession(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val request = json.optString("request").trim()
        require(request.isNotEmpty() && request.length <= MAX_REQUEST_CHARS) {
            "request must be 1-$MAX_REQUEST_CHARS characters."
        }
        val conversationId = json.optString("conversationId").trim().ifBlank { null }
        val reasoningEffort = json.optString("reasoningEffort")
            .trim()
            .ifBlank { ReasoningEffort.default.codexValue }
        val fastMode = json.optBoolean("fastMode", false)
        if (!coordinator.start(request, conversationId, reasoningEffort, fastMode)) {
            reply.write(errorResponse(requestId, "The phone already has an active session."))
            return
        }
        val state = coordinator.state.value
        val sessionId = (state as? SessionState.Running)?.sessionId
        require(sessionId != null) { "The phone session did not enter the running state." }
        // A desktop-originated session must get the same persistent foreground
        // notification as a session started from the Compose Run button.
        runCatching {
            platform.startSessionService(request, reasoningEffort, fastMode, conversationId)
        }.onFailure { error ->
            platform.logWarning(BRIDGE_LOG_TAG, "Could not start the foreground notification for the bridge session", error)
        }
        reply.write(
            JSONObject()
                .put("type", "started")
                .put("requestId", requestId)
                .put("ok", true)
                .put("sessionId", sessionId)
                .put("conversationId", (state as? SessionState.Running)?.conversationId ?: JSONObject.NULL)
                .put("reasoningEffort", (state as? SessionState.Running)?.reasoningEffort ?: ReasoningEffort.default.codexValue)
                .put("fastMode", (state as? SessionState.Running)?.fastMode ?: false)
                .put("message", "Phone assistant session started."),
        )
    }

    fun status(
        requestId: String,
        reply: BridgeReply,
    ) {
        val state = coordinator.state.value
        val response = JSONObject()
            .put("type", "status")
            .put("requestId", requestId)
            .put("ok", true)
            .put("state", sessionStateName(state))
            .put("active", state is SessionState.Running || state is SessionState.Paused)
            .put("companionConnected", presence.companionConnected.value)
        when (state) {
            is SessionState.Running -> response
                .put("sessionId", state.sessionId)
                .put("conversationId", state.conversationId ?: JSONObject.NULL)
                .put("request", state.request)
                .put("currentPurpose", state.currentPurpose)
                .put("fastMode", state.fastMode)
                .put("requestAvailable", coordinator.pendingRequest()?.sessionId == state.sessionId)
            is SessionState.Paused -> response
                .put("sessionId", state.sessionId)
                .put("conversationId", state.conversationId ?: JSONObject.NULL)
                .put("request", state.request)
                .put("currentPurpose", state.currentPurpose)
                .put("fastMode", state.fastMode)
            else -> Unit
        }
        reply.write(response)
    }

    fun heartbeat(
        requestId: String,
        reply: BridgeReply,
    ) {
        // Keep the phone-side companion lease independent from pending work,
        // Codex startup, or a long-running task request.
        presence.markSeen()
        reply.write(
            JSONObject()
                .put("type", "heartbeat")
                .put("requestId", requestId)
                .put("ok", true)
                .put("message", "Desktop companion heartbeat acknowledged."),
        )
    }

    fun companionDisconnected(
        requestId: String,
        reply: BridgeReply,
    ) {
        presence.release()
        reply.write(
            JSONObject()
                .put("type", "companion_disconnected")
                .put("requestId", requestId)
                .put("ok", true)
                .put("message", "Desktop companion presence released."),
        )
    }

    fun pendingRequest(
        requestId: String,
        reply: BridgeReply,
    ) {
        // The companion's normal pending-request poll doubles as its
        // heartbeat. The phone uses this to render the existing recovery card
        // without exposing the request or requiring another protocol.
        presence.markSeen()
        val pending = coordinator.pendingRequest()
        val response = JSONObject()
            .put("type", "pending_request")
            .put("requestId", requestId)
            .put("ok", true)
            .put("available", pending != null)
            .put("warmupRequested", presence.consumeCodexWarmupRequest())
        if (pending != null) {
            response
                .put("sessionId", pending.sessionId)
                .put("conversationId", pending.conversationId ?: JSONObject.NULL)
                .put("codexThreadId", pending.codexThreadId ?: JSONObject.NULL)
                .put("reasoningEffort", pending.reasoningEffort)
                .put("fastMode", pending.fastMode)
                .put("continuation", pending.isContinuation)
                .put("request", pending.request)
        }
        reply.write(response)
    }

    fun claimRequest(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val expectedSessionId = json.optString("sessionId").trim().ifBlank { null }
        val claimed = coordinator.claimRequest(expectedSessionId)
        if (claimed == null) {
            reply.write(
                errorResponse(requestId, "No unclaimed running phone request matched the supplied sessionId.")
                    .put("code", BridgeErrorCodes.REQUEST_NOT_AVAILABLE),
            )
            return
        }
        reply.write(
            JSONObject()
                .put("type", "request_claimed")
                .put("requestId", requestId)
                .put("ok", true)
                .put("sessionId", claimed.sessionId)
                .put("conversationId", claimed.conversationId ?: JSONObject.NULL)
                .put("codexThreadId", claimed.codexThreadId ?: JSONObject.NULL)
                .put("reasoningEffort", claimed.reasoningEffort)
                .put("fastMode", claimed.fastMode)
                .put("continuation", claimed.isContinuation)
                .put("request", claimed.request)
                .put("message", "Phone request claimed by the desktop Codex companion."),
        )
    }

    fun releaseRequest(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        val released = coordinator.releaseRequest(sessionId)
        reply.write(
            JSONObject()
                .put("type", "request_released")
                .put("requestId", requestId)
                .put("ok", true)
                .put("sessionId", sessionId)
                .put("released", released),
        )
    }

    fun bindCodexThread(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val conversationId = json.optString("conversationId").trim()
        val codexThreadId = json.optString("codexThreadId").trim()
        require(conversationId.isNotEmpty()) { "conversationId is required." }
        require(codexThreadId.isNotEmpty()) { "codexThreadId is required." }
        val bound = coordinator.bindCodexThread(conversationId, codexThreadId)
        reply.write(
            JSONObject()
                .put("type", "codex_thread_bound")
                .put("requestId", requestId)
                .put("ok", bound)
                .put("conversationId", conversationId)
                .put("codexThreadId", codexThreadId),
        )
    }

    fun failSession(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        if (coordinator.state.value.sessionIdOrNull != sessionId) {
            reply.write(
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", BridgeErrorCodes.SESSION_NOT_RUNNING),
            )
            return
        }
        val reason = json.optString("reason", "The desktop Codex turn failed.")
            .trim()
            .ifBlank { "The desktop Codex turn failed." }
            .take(MAX_AGENT_FEEDBACK_CHARS)
        val failed = coordinator.fail(reason)
        if (failed) {
            platform.showAttentionNotification(
                "DHD stopped: $reason",
                coordinator.state.value.conversationIdOrNull,
            )
        }
        platform.reconcileServiceLifetime()
        reply.write(
            JSONObject()
                .put("type", "session_failed")
                .put("requestId", requestId)
                .put("ok", failed)
                .put("message", reason),
        )
    }

    fun streamAgentMessage(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        presence.markSeen()
        val sessionId = json.optString("sessionId").trim()
        val messageId = json.optString("messageId").trim()
        val text = json.optString("text")
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(messageId.isNotEmpty() && messageId.length <= MAX_TEXT_CHARS) {
            "messageId must be 1-$MAX_TEXT_CHARS characters."
        }
        require(text.length <= MAX_AGENT_FEEDBACK_CHARS) {
            "text must be at most $MAX_AGENT_FEEDBACK_CHARS characters."
        }
        if (coordinator.state.value.sessionIdOrNull != sessionId) {
            reply.write(
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", BridgeErrorCodes.SESSION_NOT_RUNNING),
            )
            return
        }
        val streamed = coordinator.streamAgentMessage(sessionId, messageId, text)
        reply.write(
            JSONObject()
                .put("type", "agent_message_streamed")
                .put("requestId", requestId)
                .put("ok", streamed)
                .put("sessionId", sessionId)
                .put("messageId", messageId),
        )
    }

    fun completeSession(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        val message = json.optString("message", "Your DHD task is ready to review.")
            .trim()
            .ifBlank { "Your DHD task is ready to review." }
            .take(MAX_TEXT_CHARS)
        val feedback = json.optString("feedback")
            .trim()
            .ifBlank { null }
            ?.take(MAX_AGENT_FEEDBACK_CHARS)
        val agentMessageId = json.optString("agentMessageId")
            .trim()
            .ifBlank { null }
            ?.take(MAX_TEXT_CHARS)
        val state = coordinator.state.value
        if (!state.isActive || state.sessionIdOrNull != sessionId) {
            reply.write(
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", BridgeErrorCodes.SESSION_NOT_RUNNING),
            )
            return
        }
        val completionMessage = feedback ?: message
        val completed = coordinator.complete(
            completionMessage,
            agentFeedback = feedback,
            agentMessageId = agentMessageId,
        )
        if (completed) {
            platform.showCompletionNotification(completionMessage, coordinator.state.value.conversationIdOrNull)
        }
        platform.removeAttentionNotification()
        platform.reconcileServiceLifetime()
        reply.write(
            JSONObject()
                .put("type", "session_completed")
                .put("requestId", requestId)
                .put("ok", completed)
                .put("sessionId", sessionId)
                .put("conversationId", coordinator.state.value.conversationIdOrNull ?: JSONObject.NULL)
                .put("message", completionMessage)
                .put("feedback", feedback ?: JSONObject.NULL),
        )
    }

    fun stopSession(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val reason = json.optString("reason", "Stopped by the desktop assistant.")
            .trim()
            .ifBlank { "Stopped by the desktop assistant." }
            .take(MAX_TEXT_CHARS)
        val stopped = coordinator.stop(reason)
        platform.removeAttentionNotification()
        platform.reconcileServiceLifetime()
        reply.write(
            JSONObject()
                .put("type", "stopped")
                .put("requestId", requestId)
                .put("ok", true)
                .put("wasActive", stopped)
                .put("message", reason),
        )
    }
}
