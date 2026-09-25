package com.phonecontrol.assistant.bridge.protocol

import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.bridge.SequenceExecutionResult
import com.phonecontrol.assistant.core.Base64Codec
import com.phonecontrol.assistant.domain.ObservationSize
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.domain.StaleObservationReason
import com.phonecontrol.assistant.execution.TransportResult
import com.phonecontrol.assistant.execution.taskDisplayReference
import com.phonecontrol.assistant.session.ActionExecutionResult
import com.phonecontrol.assistant.session.SessionState
import org.json.JSONArray
import org.json.JSONObject

internal class BridgeJson(private val base64: Base64Codec) {
    fun snapshotJson(snapshot: ObservationSnapshot): JSONObject = JSONObject()
        .put("id", snapshot.id)
        .put("packageName", snapshot.packageName)
        .put("activityName", snapshot.activityName ?: JSONObject.NULL)
        .put("rotation", snapshot.rotation)
        .put("width", snapshot.width)
        .put("height", snapshot.height)
        .put("screenshotFingerprint", snapshot.screenshotFingerprint)
        .put(
            "screenProtection",
            JSONObject()
                .put("status", snapshot.screenProtection.status.name.lowercase())
                .put("requiresUserAttention", snapshot.screenProtection.requiresUserAttention)
                .put("signals", JSONArray(snapshot.screenProtection.signals))
                .put("reason", snapshot.screenProtection.reason ?: JSONObject.NULL),
        )
        .also { json ->
            snapshot.taskSessionKey?.let { sessionKey ->
                json.put("displayRef", taskDisplayReference(sessionKey, snapshot.displayId))
            }
        }

    fun addBeforeDebug(
        response: JSONObject,
        observation: ObservationSnapshot?,
        screenshot: ByteArray?,
    ) {
        if (observation == null || screenshot == null) return
        response
            .put("beforeObservation", snapshotJson(observation))
            .put("beforeScreenshotBase64", base64.encode(screenshot))
            .put("beforeScreenshotMimeType", "image/png")
    }

    /** Attach machine-readable freshness diagnostics without changing the
     * action's safe rejection semantics. */
    fun addStaleDiagnostics(
        response: JSONObject,
        details: StaleObservationDiagnostics,
    ) {
        response
            .put("inputSent", false)
            .put("approvedObservationId", details.approvedObservationId)
        details.currentObservationId?.let { response.put("currentObservationId", it) }
        response.put(
            "reasons",
            JSONArray(details.reasons.map(::staleReasonJson)),
        )
    }

    private fun staleReasonJson(reason: StaleObservationReason): JSONObject = JSONObject()
        .put("code", reason.code.name)
        .put("approved", staleReasonValue(reason.approved))
        .put("current", staleReasonValue(reason.current))
        .also { json ->
            reason.guardRegion?.let { region ->
                json.put(
                    "guardRegion",
                    JSONObject()
                        .put("left", region.left)
                        .put("top", region.top)
                        .put("right", region.right)
                        .put("bottom", region.bottom),
                )
            }
        }

    private fun staleReasonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is ObservationSize -> JSONObject()
            .put("width", value.width)
            .put("height", value.height)
        else -> value
    }

    fun observationResponse(
        requestId: String,
        snapshot: ObservationSnapshot,
        screenshot: ByteArray,
    ): JSONObject = JSONObject()
        .put("type", "observation")
        .put("requestId", requestId)
        .put("ok", true)
        .put("observation", snapshotJson(snapshot))
        .put("screenshotBase64", base64.encode(screenshot))
        .put("screenshotMimeType", "image/png")

    fun actionResultResponse(
        requestId: String,
        action: String,
        result: ActionExecutionResult,
    ): JSONObject {
        val successful = result.isSuccessful()
        val response = JSONObject()
            .put("type", "action_result")
            .put("requestId", requestId)
            .put("action", action)
            .put("ok", successful)
        when (result) {
            is ActionExecutionResult.TransportFinished -> {
                when (val transportResult = result.result) {
                    is TransportResult.Succeeded -> response.put("message", transportResult.message)
                    is TransportResult.Rejected -> response
                        .put("code", transportResult.code.name)
                        .put("message", transportResult.message)
                        .also { transportResult.details?.let { details -> addStaleDiagnostics(it, details) } }
                    is TransportResult.Unsupported -> response.put("message", transportResult.message)
                }
            }

            is ActionExecutionResult.PolicyRejected -> response
                .put("code", BridgeErrorCodes.POLICY_REJECTED)
                .put("message", result.message)
                .also { result.details?.let { details -> addStaleDiagnostics(it, details) } }
            ActionExecutionResult.SessionNotRunning -> response
                .put("code", BridgeErrorCodes.SESSION_NOT_RUNNING)
                .put("message", SESSION_NOT_RUNNING_MESSAGE)
        }
        return response
    }

    fun sequenceResultResponse(
        requestId: String,
        result: SequenceExecutionResult,
        beforeObservation: ObservationSnapshot? = null,
    ): JSONObject {
        val response = JSONObject()
            .put("type", "completed")
            .put("requestId", requestId)
            .put("ok", result.ok)
            .put("action", "sequence")
            .put("requestedSteps", result.requestedSteps)
            .put("completedSteps", result.completedSteps)
            .put(
                "message",
                if (result.ok) {
                    "Executed ${result.requestedSteps} typed phone actions and returned a fresh observation."
                } else {
                    result.failure?.message ?: "The phone sequence failed."
                },
            )
        val steps = JSONArray()
        result.steps.forEach { step ->
            val stepJson = JSONObject()
                .put("index", step.index)
                .put("action", step.action)
                .put("status", step.status.name.lowercase())
                .put("message", step.message)
            step.observationId?.let { stepJson.put("observationId", it) }
            step.code?.let { stepJson.put("code", it) }
            step.outcome?.let { stepJson.put("outcome", it) }
            step.executed?.let { stepJson.put("executed", it) }
            step.details?.let { addStaleDiagnostics(stepJson, it) }
            steps.put(stepJson)
        }
        response.put("steps", steps)
        result.failure?.let { failure ->
            response
                .put("failedStep", failure.index)
                .put("code", failure.code ?: BridgeErrorCodes.SEQUENCE_FAILED)
                .put("outcome", failure.outcome ?: "failed")
                .put("executed", failure.executed ?: "unknown")
            failure.details?.let { addStaleDiagnostics(response, it) }
        }
        result.finalObservation?.let { captured ->
            response
                .put("observation", snapshotJson(captured.snapshot))
                .put("screenshotBase64", base64.encode(captured.screenshot))
                .put("screenshotMimeType", "image/png")
            if (beforeObservation != null) {
                addBeforeDebug(response, beforeObservation, result.beforeScreenshot)
            }
        }
        return response
    }

    fun invalidSequenceResponse(
        requestId: String,
        json: JSONObject,
        error: InvalidSequencePayloadException,
    ): JSONObject {
        val actions = json.optJSONArray("actions")
        val response = JSONObject()
            .put("type", "completed")
            .put("requestId", requestId)
            .put("ok", false)
            .put("action", "sequence")
            .put("requestedSteps", actions?.length() ?: 0)
            .put("completedSteps", 0)
            .put("message", error.message ?: "The sequence payload is invalid.")
            .put("code", BridgeErrorCodes.INVALID_PAYLOAD)
            .put("outcome", "failed")
            .put("executed", false)
        val steps = JSONArray()
        error.index?.let { index ->
            val action = actions
                ?.optJSONObject(index)
                ?.optString("type")
                ?.trim()
                ?.ifBlank { null }
                ?: "unknown"
            steps.put(
                JSONObject()
                    .put("index", index)
                    .put("action", action)
                    .put("status", "failed")
                    .put("message", error.message ?: "The sequence action is invalid.")
                    .put("code", BridgeErrorCodes.INVALID_PAYLOAD)
                    .put("outcome", "failed")
                    .put("executed", false),
            )
            response.put("failedStep", index)
        }
        response.put("steps", steps)
        return response
    }
}

internal fun errorResponse(requestId: String?, message: String): JSONObject = JSONObject()
    .put("type", "error")
    .put("requestId", requestId ?: JSONObject.NULL)
    .put("ok", false)
    .put("message", message)

internal fun sessionStateName(state: SessionState): String = when (state) {
    SessionState.Idle -> "idle"
    is SessionState.Running -> "running"
    is SessionState.Paused -> "paused"
    is SessionState.Stopped -> "stopped"
    is SessionState.Completed -> "completed"
}

internal fun buildAllowedAppsResponse(
    requestId: String,
    fullAccess: Boolean,
    allowedPackages: Set<String>,
    includeAll: Boolean = false,
    apps: List<InstalledUserApp> = emptyList(),
): JSONObject {
    val response = JSONObject()
        .put("type", "allowed_apps")
        .put("requestId", requestId)
        .put("ok", true)
        .put("fullAccess", fullAccess)
        .put("accessMode", if (fullAccess) "full_access" else "allowlist")
        .put("canListAllApps", fullAccess)

    if (includeAll) {
        response
            .put("apps", JSONArray(apps.map(::buildAppResponse)))
            .put("count", apps.size)
            .put(
                "message",
                if (fullAccess) {
                    "Full Access is enabled. Returned all launchable apps on the phone."
                } else {
                    "Restricted access is enabled. Returned all launchable apps in the allowlist."
                },
            )
    } else if (fullAccess) {
        response.put("message", "Full Access is enabled. You can use any launchable app on the phone.")
    } else {
        val packages = allowedPackages.toList().sorted()
        response
            .put("allowedPackages", JSONArray(packages))
            .put("count", packages.size)
    }

    return response
}

internal fun buildBrowseAppsResponse(
    requestId: String,
    query: String,
    fullAccess: Boolean,
    allowedPackages: Set<String> = emptySet(),
    apps: List<InstalledUserApp>,
    truncated: Boolean,
): JSONObject = JSONObject()
    .put("type", "browse_apps")
    .put("requestId", requestId)
    .put("ok", true)
    .put("query", query)
    .put("fullAccess", fullAccess)
    .put("accessMode", if (fullAccess) "full_access" else "allowlist")
    .put(
        "apps",
        JSONArray(
            apps.map { app ->
                buildAppResponse(
                    app = app,
                    canUse = fullAccess || app.packageName in allowedPackages,
                )
            },
        ),
    )
    .put("count", apps.size)
    .put("truncated", truncated)

internal fun buildAppDisplayLayoutResponse(
    requestId: String,
    packageName: String,
    appLabel: String,
    layout: String,
    changed: Boolean,
): JSONObject {
    val fullSize = layout == "full_size"
    val layoutDescription = if (fullSize) "full-size" else "standard"
    return JSONObject()
        .put("type", "app_display_layout_updated")
        .put("requestId", requestId)
        .put("ok", true)
        .put("appLabel", appLabel)
        .put("packageName", packageName)
        .put("layout", layout)
        .put("fullSizeLayoutEnabled", fullSize)
        .put("changed", changed)
        .put("appliesNextOpen", true)
        .put("requiresFreshDisplay", changed)
        .put("currentDisplayUnchanged", true)
        .put("displayGeometryUnchanged", true)
        .put(
            "message",
            if (changed) {
                "$layoutDescription app layout saved for $appLabel. The next dhd_open_app call without displayRef will use a fresh DHD task display with this layout."
            } else {
                "$layoutDescription app layout is already active for $appLabel. Future compatible opens may reuse the current DHD task display."
            },
        )
}

/**
 * Add an actionable display inventory to a session-limit failure. The list
 * intentionally contains only displayRefs and user-facing metadata so the
 * agent can close or reuse a display without receiving native display IDs or
 * coordinator/session keys.
 */
internal fun addDisplayLimitRecovery(
    response: JSONObject,
    packageName: String?,
    displays: List<JSONObject>,
): JSONObject {
    val target = packageName?.trim()?.takeIf(String::isNotEmpty) ?: "the requested app"
    return response
        .put(
            "message",
            "The DHD virtual-display session limit was reached while opening $target. " +
                "The displays array lists the active and retained displays. " +
                "Close an unused display with dhd_close_display using its exact displayRef " +
                "(stop its active run first if needed), then retry dhd_open_app. " +
                "To reuse a retained display instead, pass its displayRef to dhd_open_app.",
        )
        .put("displays", JSONArray(displays))
        .put("count", displays.size)
}

private fun buildAppResponse(app: InstalledUserApp, canUse: Boolean? = null): JSONObject {
    val response = JSONObject()
        .put("appLabel", app.label)
        .put("packageName", app.packageName)
    if (canUse != null) response.put("canUse", canUse)
    return response
}
