package com.phonecontrol.assistant.bridge.handlers

import com.phonecontrol.assistant.bridge.BridgePlatform
import com.phonecontrol.assistant.bridge.CaptureService
import com.phonecontrol.assistant.bridge.DisplayTargetResolver
import com.phonecontrol.assistant.bridge.protocol.ActionParser.optionalDisplayRef
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.BridgeJson
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_TEXT_CHARS
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.core.Base64Codec
import com.phonecontrol.assistant.core.conversationIdOrNull
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.observation.ObservationCaptureResult
import com.phonecontrol.assistant.session.AttentionResolution
import com.phonecontrol.assistant.session.SessionCoordinator
import org.json.JSONObject

internal class AttentionHandler(
    private val coordinator: SessionCoordinator,
    private val platform: BridgePlatform,
    private val taskDisplayRequiredProvider: () -> Boolean,
    private val displayTargets: DisplayTargetResolver,
    private val captures: CaptureService,
    private val bridgeJson: BridgeJson,
    private val base64: Base64Codec,
) {
    suspend fun requestAttention(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val reason = json.optString("reason")
            .trim()
            .ifBlank { "The phone assistant needs your attention." }
            .take(MAX_TEXT_CHARS)
        val sessionId = coordinator.activeSessionId()
        if (sessionId == null) {
            reply.write(
                errorResponse(requestId, "The phone assistant has no active session to interrupt.")
                    .put("code", BridgeErrorCodes.SESSION_NOT_RUNNING),
            )
            return
        }
        val requestedDisplayRef = optionalDisplayRef(json)
        val target = if (taskDisplayRequiredProvider() || requestedDisplayRef != null) {
            when (val resolution = displayTargets.resolve(displayRef = requestedDisplayRef)) {
                is TaskDisplayResolution.Ready -> resolution.target
                is TaskDisplayResolution.Unavailable -> {
                    reply.write(
                        errorResponse(requestId, resolution.message).put("code", resolution.code),
                    )
                    return
                }
            }
        } else {
            null
        }
        val attention = coordinator.requestAttentionWaiter(reason)
        if (attention == null) {
            reply.write(
                errorResponse(requestId, "The phone assistant is already waiting for the user's attention.")
                    .put("code", BridgeErrorCodes.ATTENTION_ALREADY_PENDING),
            )
            return
        }
        platform.showAttentionNotification(reason, coordinator.state.value.conversationIdOrNull)
        when (attention.await()) {
            AttentionResolution.Cancelled -> reply.write(
                JSONObject()
                    .put("type", "attention_cancelled")
                    .put("requestId", requestId)
                    .put("ok", false)
                    .put("sessionId", sessionId)
                    .put("code", BridgeErrorCodes.SESSION_STOPPED)
                    .put("message", "The attention step was cancelled because the phone session stopped."),
            )

            AttentionResolution.Acknowledged -> {
                platform.removeAttentionNotification()
                val response = JSONObject()
                    .put("type", "attention_resolved")
                    .put("requestId", requestId)
                    .put("ok", true)
                    .put("sessionId", sessionId)
                    .put("acknowledged", true)
                    .put("message", "The user confirmed that the attention step is complete. Observe the phone before taking the next action.")
                when (val captured = captures.captureWithRetry(
                    expectedPackageName = null,
                    guardRegions = emptyList(),
                    taskSessionKey = target?.session?.sessionKey ?: sessionId,
                    displayId = target?.session?.displayId,
                    expectedDisplayRef = target?.displayRef,
                )) {
                    is ObservationCaptureResult.Failed -> response
                        .put("observationError", captured.message)
                        .put("observationErrorCode", captured.code)
                    is ObservationCaptureResult.Succeeded -> {
                        captures.remember(captured.snapshot)
                        response
                            .put("observation", bridgeJson.snapshotJson(captured.snapshot))
                            .put("screenshotBase64", base64.encode(captured.screenshot))
                            .put("screenshotMimeType", "image/png")
                    }
                }
                reply.write(response)
            }
        }
    }
}
