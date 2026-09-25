package com.phonecontrol.assistant.bridge.handlers

import com.phonecontrol.assistant.bridge.BridgePlatform
import com.phonecontrol.assistant.bridge.DisplayTargetResolver
import com.phonecontrol.assistant.bridge.appLabel
import com.phonecontrol.assistant.bridge.protocol.ActionParser.optionalDisplayRef
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplayCloseResult
import com.phonecontrol.assistant.session.SessionCoordinator
import org.json.JSONArray
import org.json.JSONObject

internal class DisplayHandlers(
    private val taskDisplayBackend: TaskDisplayBackend?,
    private val coordinator: SessionCoordinator,
    private val displayTargets: DisplayTargetResolver,
    private val platform: BridgePlatform,
) {
    suspend fun listDisplays(
        requestId: String,
        reply: BridgeReply,
    ) {
        val backend = taskDisplayBackend
        if (backend == null) {
            reply.write(
                errorResponse(requestId, "The task display registry is unavailable.")
                    .put("code", BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE),
            )
            return
        }
        val displays = displayTargets.currentDisplayJson(backend)
        reply.write(
            JSONObject()
                .put("type", "displays")
                .put("requestId", requestId)
                .put("ok", true)
                .put("displays", JSONArray(displays))
                .put("count", displays.size)
                .put("message", if (displays.isEmpty()) "No task displays are available." else "Returned active and retained task displays."),
        )
    }

    suspend fun closeDisplay(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val backend = taskDisplayBackend
        if (backend == null) {
            reply.write(
                errorResponse(requestId, "The task display registry is unavailable.")
                    .put("code", BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE),
            )
            return
        }
        val displayRef = try {
            optionalDisplayRef(json)
        } catch (error: IllegalArgumentException) {
            reply.write(errorResponse(requestId, error.message ?: "displayRef is invalid.").put("code", BridgeErrorCodes.INVALID_DISPLAY_REF))
            return
        }
        if (displayRef == null) {
            reply.write(
                errorResponse(requestId, "displayRef is required to close a display safely. Call dhd_list_displays first and use the matching displayRef.")
                    .put("code", BridgeErrorCodes.DISPLAY_REFERENCE_REQUIRED),
            )
            return
        }
        backend.activeDisplaySessions()
        val record = backend.displayRecords.value.firstOrNull { it.displayRef == displayRef }
        if (record == null) {
            reply.write(
                errorResponse(requestId, "No task display matches the supplied displayRef. Call dhd_list_displays to see the available displays.")
                    .put("code", BridgeErrorCodes.DISPLAY_NOT_FOUND),
            )
            return
        }
        val activeRunKey = coordinator.activeSessionId()
        if (activeRunKey != null && backend.isDisplayClaimedByRun(record.displayId, activeRunKey)) {
            reply.write(
                errorResponse(requestId, "The selected task display is being used by an active DHD run. Stop the active run first, then close the display.")
                    .put("code", BridgeErrorCodes.DISPLAY_IN_USE),
            )
            return
        }
        when (val result = backend.closeTaskDisplay(record.displayId, displayRef)) {
            is TaskDisplayCloseResult.Rejected -> reply.write(
                errorResponse(requestId, result.message).put("code", result.code),
            )

            is TaskDisplayCloseResult.Closed -> reply.write(
                JSONObject()
                    .put("type", "display_closed")
                    .put("requestId", requestId)
                    .put("ok", true)
                    .put("displayRef", result.record.displayRef)
                    .put("appLabel", platform.appLabel(result.record.packageName))
                    .put("status", result.record.status.name.lowercase())
                    .put("message", "The selected task display was ended."),
            )
        }
    }
}
