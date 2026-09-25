package com.phonecontrol.assistant.bridge.handlers

import com.phonecontrol.assistant.bridge.CaptureService
import com.phonecontrol.assistant.bridge.DisplayTargetResolver
import com.phonecontrol.assistant.bridge.protocol.ActionParser.optionalDisplayRef
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.BridgeJson
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_TEXT_CHARS
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.observation.ForegroundAppResult
import com.phonecontrol.assistant.observation.ObservationCaptureResult
import com.phonecontrol.assistant.observation.PhoneObservationSource
import com.phonecontrol.assistant.session.SessionCoordinator
import org.json.JSONArray
import org.json.JSONObject

internal class ObservationHandlers(
    private val coordinator: SessionCoordinator,
    private val observationProvider: PhoneObservationSource,
    private val taskDisplayRequiredProvider: () -> Boolean,
    private val displayTargets: DisplayTargetResolver,
    private val captures: CaptureService,
    private val bridgeJson: BridgeJson,
) {
    suspend fun observe(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val purpose = json.optString("purpose").trim().take(MAX_TEXT_CHARS)
            .ifBlank { "Observing current screen" }
        coordinator.recordPurpose(
            purpose = purpose,
            targetDescription = json.optString("targetDescription").trim().take(MAX_TEXT_CHARS).ifBlank { null },
            toolName = ToolNames.OBSERVE,
        )
        if (!coordinator.awaitPhoneAccessForTool()) {
            reply.write(
                errorResponse(requestId, "Phone access is no longer available; DHD could not observe the phone.")
                    .put("code", BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE),
            )
            return
        }
        val requestedDisplayRef = optionalDisplayRef(json)
        val target = if (taskDisplayRequiredProvider() || requestedDisplayRef != null) {
            when (val resolution = displayTargets.resolve(
                displayRef = requestedDisplayRef,
            )) {
                is TaskDisplayResolution.Ready -> resolution.target
                is TaskDisplayResolution.Unavailable -> {
                    reply.write(errorResponse(requestId, resolution.message).put("code", resolution.code))
                    return
                }
            }
        } else {
            null
        }
        when (val captured = captures.captureWithRetry(
            expectedPackageName = null,
            guardRegions = emptyList(),
            taskSessionKey = target?.session?.sessionKey ?: coordinator.activeSessionId(),
            displayId = target?.session?.displayId,
            expectedDisplayRef = target?.displayRef,
        )) {
            is ObservationCaptureResult.Failed -> reply.write(
                errorResponse(requestId, captured.message).put("code", captured.code),
            )
            is ObservationCaptureResult.Succeeded -> {
                captures.remember(captured.snapshot)
                reply.write(bridgeJson.observationResponse(requestId, captured.snapshot, captured.screenshot))
            }
        }
    }

    suspend fun foregroundApp(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        coordinator.recordPurpose(
            purpose = "Checking foreground app",
            toolName = ToolNames.FOREGROUND_APP,
        )
        if (!coordinator.awaitPhoneAccessForTool()) {
            reply.write(
                errorResponse(requestId, "Phone access is no longer available; DHD could not check the phone.")
                    .put("code", BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE),
            )
            return
        }
        val requestedDisplayRef = optionalDisplayRef(json)
        val target = if (taskDisplayRequiredProvider() || requestedDisplayRef != null) {
            when (val resolution = displayTargets.resolve(
                displayRef = requestedDisplayRef,
            )) {
                is TaskDisplayResolution.Ready -> resolution.target
                is TaskDisplayResolution.Unavailable -> {
                    reply.write(errorResponse(requestId, resolution.message).put("code", resolution.code))
                    return
                }
            }
        } else {
            null
        }
        when (val result = observationProvider.getForegroundApp(
            taskSessionKey = target?.session?.sessionKey ?: coordinator.activeSessionId(),
            displayId = target?.session?.displayId,
            expectedDisplayRef = target?.displayRef,
        )) {
            is ForegroundAppResult.Failed -> reply.write(
                errorResponse(requestId, result.message).put("code", result.code),
            )

            is ForegroundAppResult.Succeeded -> reply.write(
                JSONObject()
                    .put("type", "foreground_app")
                    .put("requestId", requestId)
                    .put("ok", true)
                    .put("packageName", result.app.packageName)
                    .put("activityName", result.app.activityName)
                    .put("rotation", result.app.rotation)
                    .put("width", result.app.width)
                    .put("height", result.app.height)
                    .put(
                        "screenProtection",
                        JSONObject()
                            .put("status", result.app.screenProtection.status.name.lowercase())
                            .put("requiresUserAttention", result.app.screenProtection.requiresUserAttention)
                            .put("signals", JSONArray(result.app.screenProtection.signals))
                            .put("reason", result.app.screenProtection.reason ?: JSONObject.NULL),
                    )
                    .put("message", "The current foreground app is ${result.app.packageName}.")
                    .also { response -> target?.displayRef?.let { response.put("displayRef", it) } },
            )
        }
    }
}
