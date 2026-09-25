package com.phonecontrol.assistant.bridge.routing

import com.phonecontrol.assistant.bridge.BRIDGE_LOG_TAG
import com.phonecontrol.assistant.bridge.BridgePlatform
import com.phonecontrol.assistant.bridge.auth.BridgeCredentials
import com.phonecontrol.assistant.bridge.presence.CompanionPresence
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_REQUEST_CHARS
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

internal fun interface BridgeHandler {
    suspend fun handle(requestId: String, json: JSONObject, reply: BridgeReply)
}

internal class BridgeRouter(
    private val credentials: BridgeCredentials,
    private val presence: CompanionPresence,
    private val platform: BridgePlatform,
    private val newUuid: () -> UUID,
    private val handlers: Map<String, BridgeHandler>,
) {
    suspend fun handleRequestLine(
        line: String?,
        reply: BridgeReply,
    ) {
        if (line == null) {
            reply.write(errorResponse(null, "The bridge received an empty request."))
            return
        }
        if (line.length > MAX_REQUEST_CHARS) {
            reply.write(errorResponse(null, "The bridge request is too large."))
            return
        }
        val json = try {
            JSONObject(line)
        } catch (error: IllegalArgumentException) {
            reply.write(errorResponse(null, error.message ?: "Invalid bridge request."))
            return
        } catch (error: JSONException) {
            reply.write(errorResponse(null, "The bridge request must be valid JSON."))
            return
        }
        val requestId = json.optString("requestId").ifBlank { newUuid().toString() }

        if (!credentials.isAuthorized(json)) {
            reply.write(
                errorResponse(requestId, "The phone bridge rejected this network connection. Pair the desktop companion in DHD settings.")
                    .put("code", BridgeErrorCodes.AUTH_REQUIRED),
            )
            return
        }

        // Dashboard status checks are read-only health probes and must not
        // keep the worker's liveness lease alive after the worker stops.
        // Worker traffic still refreshes presence independently of the
        // current task or Codex polling phase.
        val requestType = json.optString("type")
        if (requestType != "status" && requestType != "companion_disconnected") {
            presence.markSeen()
        }
        reply.write(
            JSONObject()
                .put("type", "accepted")
                .put("requestId", requestId)
                .put("message", "${requestType.ifBlank { "bridge" }} accepted by the phone."),
        )
        try {
            val handler = handlers[requestType]
            if (handler == null) {
                reply.write(errorResponse(requestId, "Unsupported bridge request type."))
            } else {
                handler.handle(requestId, json, reply)
            }
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            platform.logError(BRIDGE_LOG_TAG, "Bridge request failed", error)
            reply.write(errorResponse(requestId, "The phone bridge failed: $message"))
        }
    }
}
