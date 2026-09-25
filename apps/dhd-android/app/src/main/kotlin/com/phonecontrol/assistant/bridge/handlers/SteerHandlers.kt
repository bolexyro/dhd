package com.phonecontrol.assistant.bridge.handlers

import com.phonecontrol.assistant.bridge.presence.CompanionPresence
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import org.json.JSONObject

internal class SteerHandlers(
    private val coordinator: SessionCoordinator,
    private val presence: CompanionPresence,
) {
    fun pendingSteer(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        presence.markSeen()
        val expectedSessionId = json.optString("sessionId").trim().ifBlank { null }
        val state = coordinator.state.value
        val pending = coordinator.pendingSteer(expectedSessionId)
        val response = JSONObject()
            .put("type", "pending_steer")
            .put("requestId", requestId)
            .put("ok", true)
            .put("active", state is SessionState.Running)
            .put("attentionPending", coordinator.attentionPending())
            .put("available", pending != null && !coordinator.attentionPending())
        if (pending != null) {
            response
                .put("steerId", pending.steerId)
                .put("sessionId", pending.sessionId)
        }
        reply.write(response)
    }

    fun claimSteer(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val claimed = coordinator.claimSteer(sessionId, steerId)
        if (claimed == null) {
            reply.write(
                errorResponse(requestId, "No unclaimed steer matched the supplied session and steer id.")
                    .put("code", BridgeErrorCodes.STEER_NOT_AVAILABLE),
            )
            return
        }
        reply.write(
            JSONObject()
                .put("type", "steer_claimed")
                .put("requestId", requestId)
                .put("ok", true)
                .put("steerId", claimed.steerId)
                .put("sessionId", claimed.sessionId)
                .put("text", claimed.text),
        )
    }

    fun releaseSteer(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val released = coordinator.releaseSteer(sessionId, steerId)
        reply.write(
            JSONObject()
                .put("type", "steer_released")
                .put("requestId", requestId)
                .put("ok", released)
                .put("sessionId", sessionId)
                .put("steerId", steerId)
                .put("released", released),
        )
    }

    fun completeSteer(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val completed = coordinator.completeSteer(sessionId, steerId)
        reply.write(
            JSONObject()
                .put("type", "steer_completed")
                .put("requestId", requestId)
                .put("ok", completed)
                .put("sessionId", sessionId)
                .put("steerId", steerId)
                .put("delivered", completed),
        )
    }
}
