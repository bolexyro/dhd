package com.phonecontrol.assistant.bridge.handlers

import com.phonecontrol.assistant.bridge.CaptureService
import com.phonecontrol.assistant.bridge.protocol.ActionParser.parseGuardRegions
import com.phonecontrol.assistant.bridge.protocol.BridgeJson
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_TEXT_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.OPEN_SETTLE_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.PACKAGE_PATTERN
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.POST_ACTION_SETTLE_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.protocol.isSuccessful
import com.phonecontrol.assistant.bridge.protocol.resultMessage
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.observation.ObservationCaptureResult
import com.phonecontrol.assistant.session.SessionCoordinator
import java.util.UUID
import kotlinx.coroutines.delay
import org.json.JSONObject

internal class DemoHandler(
    private val coordinator: SessionCoordinator,
    private val captures: CaptureService,
    private val bridgeJson: BridgeJson,
    private val newUuid: () -> UUID,
) {
    suspend fun run(json: JSONObject, reply: BridgeReply) {
        runDemo(parseRequest(json), reply)
    }

    private suspend fun runDemo(request: DemoRequest, reply: BridgeReply) {
        val startedSession = coordinator.start("Desktop Codex demo: ${request.purpose}")
        if (!startedSession) {
            reply.write(errorResponse(request.requestId, "The phone already has an active session."))
            return
        }

        val open = OpenAppAction(
            packageName = request.packageName,
            metadata = ActionMetadata(
                purpose = "Opening ${request.packageName}",
                observationId = "",
                targetDescription = request.packageName,
            ),
        )
        val openResult = coordinator.executeAction(open, null)
        reply.write(bridgeJson.actionResultResponse(request.requestId, "open_app", openResult))
        if (!openResult.isSuccessful()) {
            failSession(reply, request, openResult.resultMessage())
            return
        }

        delay(OPEN_SETTLE_DELAY_MS)
        val afterOpen = captures.captureWithRetry(
            request.packageName,
            request.guardRegions,
            coordinator.activeSessionId(),
        )
        val tapSnapshot = when (afterOpen) {
            is ObservationCaptureResult.Failed -> {
                failSession(reply, request, afterOpen.message)
                return
            }

            is ObservationCaptureResult.Succeeded -> afterOpen.snapshot
        }
        val tap = TapAction(
            x = request.x,
            y = request.y,
            metadata = ActionMetadata(
                purpose = request.purpose,
                observationId = tapSnapshot.id,
                targetDescription = request.targetDescription,
                guardRegions = request.guardRegions,
            ),
        )
        val tapResult = coordinator.executeAction(tap, tapSnapshot)
        reply.write(bridgeJson.actionResultResponse(request.requestId, "tap", tapResult))
        if (!tapResult.isSuccessful()) {
            failSession(reply, request, tapResult.resultMessage())
            return
        }

        delay(POST_ACTION_SETTLE_DELAY_MS)
        val afterTap = captures.captureWithRetry(null, emptyList(), coordinator.activeSessionId())
        when (afterTap) {
            is ObservationCaptureResult.Failed -> {
                failSession(reply, request, "Tap completed, but the post-action observation failed: ${afterTap.message}")
                return
            }

            is ObservationCaptureResult.Succeeded -> {
                coordinator.complete("Demo completed; the phone returned a fresh observation.")
                reply.write(
                    JSONObject()
                        .put("type", "completed")
                        .put("requestId", request.requestId)
                        .put("message", "Opened ${request.packageName} and tapped ${request.x},${request.y}.")
                        .put("observationId", afterTap.snapshot.id)
                        .put("width", afterTap.snapshot.width)
                        .put("height", afterTap.snapshot.height),
                )
            }
        }
    }

    private fun failSession(reply: BridgeReply, request: DemoRequest, message: String) {
        coordinator.stop("Demo stopped: $message")
        reply.write(errorResponse(request.requestId, message))
    }

    private fun parseRequest(json: JSONObject): DemoRequest {
        require(json.optString("type") == "demo_run") {
            "Only type=demo_run is accepted by the development bridge."
        }
        val packageName = json.optString("packageName")
        require(PACKAGE_PATTERN.matches(packageName)) { "packageName is not a valid Android package name." }
        require(json.has("x") && json.has("y")) { "x and y coordinates are required." }
        val x = json.getInt("x")
        val y = json.getInt("y")
        require(x >= 0 && y >= 0) { "x and y must be non-negative." }
        val purpose = json.optString("purpose", "Developer-selected demo coordinate")
        require(purpose.isNotBlank() && purpose.length <= MAX_TEXT_CHARS) { "purpose is invalid." }
        val targetDescription = json.optString("targetDescription", "developer-selected coordinate")
        require(targetDescription.isNotBlank() && targetDescription.length <= MAX_TEXT_CHARS) {
            "targetDescription is invalid."
        }
        return DemoRequest(
            requestId = json.optString("requestId").ifBlank { newUuid().toString() },
            packageName = packageName,
            x = x,
            y = y,
            purpose = purpose,
            targetDescription = targetDescription,
            guardRegions = parseGuardRegions(json.optJSONArray("guardRegions")),
        )
    }

    private data class DemoRequest(
        val requestId: String,
        val packageName: String,
        val x: Int,
        val y: Int,
        val purpose: String,
        val targetDescription: String,
        val guardRegions: List<GuardRegion>,
    )
}
