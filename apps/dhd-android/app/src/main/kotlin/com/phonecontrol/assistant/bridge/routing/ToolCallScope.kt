package com.phonecontrol.assistant.bridge.routing

import com.phonecontrol.assistant.bridge.BridgePlatform
import com.phonecontrol.assistant.bridge.appLabel
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.overlay.OverlayHideReason
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.defaultDhdToolPurpose
import java.util.Locale
import org.json.JSONObject

internal class ToolCallScope(
    private val coordinator: SessionCoordinator,
    private val platform: BridgePlatform,
) {
    suspend fun withDhdTool(
        json: JSONObject,
        fallbackToolName: String,
        hideDuringObservation: Boolean = false,
        terminalStatus: DhdToolCallStatus = DhdToolCallStatus.COMPLETED,
        block: suspend () -> Unit,
    ) {
        val toolName = json.optString("tool").trim().ifBlank { fallbackToolName }
        val callId = coordinator.beginToolCall(toolName, toolPurpose(toolName, json))
        val visibilityToken = if (hideDuringObservation) {
            platform.overlayVisibilityGate()?.acquire(OverlayHideReason.OBSERVATION)
        } else {
            null
        }
        try {
            block()
            coordinator.finishToolCall(callId, terminalStatus)
        } catch (error: Throwable) {
            coordinator.finishToolCall(callId, DhdToolCallStatus.FAILED)
            throw error
        } finally {
            visibilityToken?.close()
        }
    }

    fun fallbackActionToolName(json: JSONObject): String {
        val actionType = json.optJSONObject("action")?.optString("type")?.lowercase()
        return if (actionType == "open_app") ToolNames.OPEN_APP else ToolNames.EXECUTE
    }

    fun toolPurpose(toolName: String, json: JSONObject): String =
        metadataPurpose(json) ?: when (toolName) {
            ToolNames.OBSERVE -> json.optString("purpose").trim().takeIf(String::isNotBlank)
                ?: defaultDhdToolPurpose(toolName)
            ToolNames.OPEN_APP -> openingAppPurpose(json)
            ToolNames.SET_APP_DISPLAY_LAYOUT -> appDisplayLayoutPurpose(json)
            ToolNames.EXECUTE -> {
                val action = json.optJSONObject("action")
                if (action?.optString("type")?.equals("open_app", ignoreCase = true) == true) {
                    openingAppPurpose(json)
                } else {
                    defaultDhdToolPurpose(toolName)
                }
            }
            ToolNames.REQUEST_ATTENTION -> defaultDhdToolPurpose(toolName)
            else -> defaultDhdToolPurpose(toolName)
        }

    /** Read the user-visible purpose from each tool's metadata shape. */
    fun metadataPurpose(json: JSONObject): String? {
        val directPurpose = json.optJSONObject("metadata")
            ?.optString("purpose")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (directPurpose != null) return directPurpose

        val actionPurpose = json.optJSONObject("action")
            ?.optJSONObject("metadata")
            ?.optString("purpose")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (actionPurpose != null) return actionPurpose

        val actions = json.optJSONArray("actions")
        for (index in 0 until (actions?.length() ?: 0)) {
            val purpose = actions
                ?.optJSONObject(index)
                ?.optJSONObject("metadata")
                ?.optString("purpose")
                ?.trim()
                ?.takeIf(String::isNotBlank)
            if (purpose != null) return purpose
        }
        return null
    }

    private fun openingAppPurpose(json: JSONObject): String {
        val packageName = json.optJSONObject("action")
            ?.optString("packageName")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val label = packageName
            ?.let(platform::appLabel)
            ?.takeIf { it.isNotBlank() && !it.equals(packageName, ignoreCase = true) }
        return label?.let { "Opening $it" } ?: defaultDhdToolPurpose(ToolNames.OPEN_APP)
    }

    private fun appDisplayLayoutPurpose(json: JSONObject): String {
        val packageName = json.optString("packageName")
            .trim()
            .takeIf(String::isNotBlank)
        val label = packageName
            ?.let(platform::appLabel)
            ?.takeIf { it.isNotBlank() && !it.equals(packageName, ignoreCase = true) }
        return when (json.optString("layout").trim().lowercase(Locale.ROOT)) {
            "full_size" -> label?.let { "Fitting $it to the task display" }
                ?: "Fitting the app to the task display"
            "standard" -> label?.let { "Restoring ${it}'s standard task layout" }
                ?: "Restoring the standard task layout"
            else -> defaultDhdToolPurpose(ToolNames.SET_APP_DISPLAY_LAYOUT)
        }
    }
}
