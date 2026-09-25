package com.phonecontrol.assistant.bridge.handlers

import com.phonecontrol.assistant.bridge.CaptureService
import com.phonecontrol.assistant.bridge.DisplayTargetResolver
import com.phonecontrol.assistant.bridge.protocol.ActionParser.optionalDisplayRef
import com.phonecontrol.assistant.bridge.protocol.ActionParser.parsePhoneAction
import com.phonecontrol.assistant.bridge.protocol.ActionParser.wireActionName
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.BridgeJson
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.OPEN_SETTLE_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.POST_ACTION_SETTLE_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.addDisplayLimitRecovery
import com.phonecontrol.assistant.bridge.protocol.beforeScreenshotOrNull
import com.phonecontrol.assistant.bridge.protocol.failedActionCompletion
import com.phonecontrol.assistant.bridge.protocol.failureCode
import com.phonecontrol.assistant.bridge.protocol.isSuccessful
import com.phonecontrol.assistant.bridge.protocol.resultMessage
import com.phonecontrol.assistant.bridge.protocol.staleDetailsOrNull
import com.phonecontrol.assistant.bridge.routing.ToolCallScope
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.core.Base64Codec
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.WaitAction
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.taskDisplayReference
import com.phonecontrol.assistant.observation.ObservationCaptureResult
import com.phonecontrol.assistant.session.SessionCoordinator
import kotlinx.coroutines.delay
import org.json.JSONObject

internal class ExecuteActionHandler(
    private val coordinator: SessionCoordinator,
    private val taskDisplayBackend: TaskDisplayBackend?,
    private val taskDisplayRequiredProvider: () -> Boolean,
    private val displayTargets: DisplayTargetResolver,
    private val captures: CaptureService,
    private val bridgeJson: BridgeJson,
    private val base64: Base64Codec,
    private val toolCalls: ToolCallScope,
) {
    suspend fun executeAction(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val actionJson = json.optJSONObject("action")
            ?: throw IllegalArgumentException("action must be an object.")
        val parsedAction = parsePhoneAction(actionJson)
        val observationId = parsedAction.metadata.observationId.trim()
        val suppliedObservation = observationId.takeIf(String::isNotBlank)?.let(captures::lookup)
        val runSessionKey = coordinator.activeSessionId()
        if (taskDisplayRequiredProvider() && runSessionKey == null) {
            reply.write(
                failedActionCompletion(
                    requestId = requestId,
                    action = wireActionName(parsedAction),
                    code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
                    message = "No active task display is available; the physical display was not touched.",
                ),
            )
            return
        }
        val requestedDisplayRef = optionalDisplayRef(json)
        val targetResolution = if (requestedDisplayRef != null ||
            suppliedObservation != null ||
            parsedAction !is OpenAppAction
        ) {
            displayTargets.resolve(
                displayRef = requestedDisplayRef,
                fallbackDisplayId = suppliedObservation?.displayId,
                fallbackDisplayRef = suppliedObservation?.taskSessionKey?.let { taskSessionKey ->
                    taskDisplayReference(taskSessionKey, suppliedObservation.displayId)
                },
            )
        } else {
            null
        }
        val target = when (targetResolution) {
            null -> null
            is TaskDisplayResolution.Ready -> targetResolution.target
            is TaskDisplayResolution.Unavailable -> {
                // If there is no retained display, open_app is allowed to
                // create a fresh one under the current run. Any other
                // resolution failure is actionable and must reach the model.
                if (parsedAction is OpenAppAction &&
                    requestedDisplayRef == null &&
                    targetResolution.code == BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE
                ) {
                    null
                } else {
                    reply.write(
                        failedActionCompletion(
                            requestId = requestId,
                            action = wireActionName(parsedAction),
                            code = targetResolution.code,
                            message = targetResolution.message,
                        ),
                    )
                    return
                }
            }
        }
        if (target != null && suppliedObservation != null &&
            (suppliedObservation.displayId != target.session.displayId ||
                suppliedObservation.taskSessionKey != target.session.sessionKey)
        ) {
            reply.write(
                failedActionCompletion(
                    requestId = requestId,
                    action = wireActionName(parsedAction),
                    code = BridgeErrorCodes.DISPLAY_CHANGED,
                    message = "The supplied observation belongs to a different task display; call dhd_observe with the selected display before retrying.",
                ),
            )
            return
        }
        val taskSessionKey = target?.session?.sessionKey ?: runSessionKey
        val observation = if (suppliedObservation != null) {
            suppliedObservation
        } else if (parsedAction is OpenAppAction && observationId.isBlank()) {
            // Launch is setup rather than an input against a model-selected
            // screen. A task display does not have a meaningful pre-launch
            // physical baseline: the task backend creates the virtual display
            // and launches the allowlisted package atomically. Legacy calls
            // without a task session retain the physical baseline behavior.
            if (taskSessionKey != null) {
                null
            } else when (val captured = captures.captureWithRetry(null, emptyList(), null)) {
                is ObservationCaptureResult.Failed -> {
                    reply.write(
                        failedActionCompletion(
                            requestId = requestId,
                            action = "open_app",
                            code = BridgeErrorCodes.OBSERVATION_FAILED,
                            message = "Could not establish a launch baseline; the app was not opened: ${captured.message}",
                        ),
                    )
                    return
                }

                is ObservationCaptureResult.Succeeded -> {
                    captures.remember(captured.snapshot)
                    captured.snapshot
                }
            }
        } else {
            null
        }
        if (observation == null && parsedAction !is OpenAppAction) {
            reply.write(
                failedActionCompletion(
                    requestId = requestId,
                    action = wireActionName(parsedAction),
                    code = BridgeErrorCodes.OBSERVATION_MISSING,
                    message = "The supplied observationId is missing or expired; observe the phone before retrying.",
                ),
            )
            return
        }
        val action = if (
            parsedAction is OpenAppAction &&
            observationId.isBlank() &&
            observation != null
        ) {
            parsedAction.copy(
                metadata = parsedAction.metadata.copy(observationId = observation.id),
            )
        } else {
            parsedAction
        }
        // Preserve the bridge tool identity on the activity event so the live
        // tool call and its lifecycle row can be rendered as one entry.
        val activityToolName = json.optString("tool")
            .trim()
            .takeIf(String::isNotBlank)
            ?: toolCalls.fallbackActionToolName(json)
        val result = coordinator.executeAction(
            action = action,
            observation = observation,
            toolName = activityToolName,
            targetDisplay = target?.session,
        )
        reply.write(bridgeJson.actionResultResponse(requestId, wireActionName(action), result))
        if (!result.isSuccessful()) {
            val failureCode = result.failureCode()
            val response = JSONObject()
                .put("type", "completed")
                .put("requestId", requestId)
                .put("ok", false)
                .put("action", wireActionName(action))
                .put("message", result.resultMessage())
            failureCode?.let { response.put("code", it) }
            bridgeJson.addBeforeDebug(
                response,
                observation,
                result.beforeScreenshotOrNull(),
            )
            result.staleDetailsOrNull()?.let { details -> bridgeJson.addStaleDiagnostics(response, details) }
            if (failureCode == BridgeErrorCodes.DISPLAY_LIMIT_REACHED) {
                val backend = taskDisplayBackend
                val displays = if (backend == null) {
                    emptyList()
                } else {
                    displayTargets.displayInventoryForRecovery(backend)
                }
                addDisplayLimitRecovery(
                    response = response,
                    packageName = (action as? OpenAppAction)?.packageName,
                    displays = displays,
                )
            }
            reply.write(response)
            return
        }

        settleAfterAction(action)
        // A successful action may intentionally navigate to another activity,
        // system surface, or package. Capture what is actually on screen and
        // let the model decide what the new observation means.
        // An app-layout change can retire the target display while the open
        // action is executing. Resolve the post-action session again so the
        // response observes the replacement generation instead of the stale
        // pre-open session.
        val postSession = taskSessionKey?.let { key ->
            taskDisplayBackend?.current(key)
        } ?: target?.session
        when (val captured = captures.captureWithRetry(
            expectedPackageName = null,
            guardRegions = emptyList(),
            taskSessionKey = postSession?.sessionKey ?: taskSessionKey,
            displayId = postSession?.displayId,
            expectedDisplayRef = postSession?.let { taskDisplayReference(it.sessionKey, it.displayId) },
        )) {
            is ObservationCaptureResult.Failed -> {
                reply.write(
                    failedActionCompletion(
                        requestId = requestId,
                        action = wireActionName(action),
                        code = if (captured.code == BridgeErrorCodes.OBSERVATION_FAILED) BridgeErrorCodes.POST_OBSERVATION_FAILED else captured.code,
                        message = "The action may have run, but the phone could not produce a post-action observation: ${captured.message}",
                        outcome = "unknown",
                        executed = "unknown",
                    ),
                )
            }

            is ObservationCaptureResult.Succeeded -> {
                val initialPointer = if (action is OpenAppAction) {
                    coordinator.publishCalibrationPointerEvent(captured.snapshot)
                } else {
                    null
                }
                captures.remember(captured.snapshot)
                val response = JSONObject()
                    .put("type", "completed")
                    .put("requestId", requestId)
                    .put("ok", true)
                    .put("action", wireActionName(action))
                    .put("message", result.resultMessage())
                    .put("observation", bridgeJson.snapshotJson(captured.snapshot))
                    .put("screenshotBase64", base64.encode(captured.screenshot))
                    .put("screenshotMimeType", "image/png")
                initialPointer?.let { pointer ->
                    response.put(
                        "initialPointer",
                        JSONObject()
                            .put("x", pointer.x)
                            .put("y", pointer.y),
                    )
                }
                bridgeJson.addBeforeDebug(
                    response,
                    observation,
                    result.beforeScreenshotOrNull(),
                )
                reply.write(response)
            }
        }
    }
}

internal suspend fun settleAfterAction(action: PhoneAction) {
    if (action is OpenAppAction) {
        delay(OPEN_SETTLE_DELAY_MS)
    } else if (action !is WaitAction) {
        delay(POST_ACTION_SETTLE_DELAY_MS)
    }
}
