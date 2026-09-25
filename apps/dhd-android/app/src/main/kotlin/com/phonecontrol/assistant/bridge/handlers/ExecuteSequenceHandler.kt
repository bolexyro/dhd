package com.phonecontrol.assistant.bridge.handlers

import com.phonecontrol.assistant.bridge.CaptureService
import com.phonecontrol.assistant.bridge.DisplayTargetResolver
import com.phonecontrol.assistant.bridge.SequenceExecutor
import com.phonecontrol.assistant.bridge.protocol.ActionParser.parseSequenceRequest
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.BridgeJson
import com.phonecontrol.assistant.bridge.protocol.InvalidSequencePayloadException
import com.phonecontrol.assistant.bridge.protocol.unstartedSequenceFailure
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.taskDisplayReference
import com.phonecontrol.assistant.session.SessionCoordinator
import org.json.JSONObject

internal class ExecuteSequenceHandler(
    private val coordinator: SessionCoordinator,
    private val taskDisplayRequiredProvider: () -> Boolean,
    private val displayTargets: DisplayTargetResolver,
    private val captures: CaptureService,
    private val bridgeJson: BridgeJson,
) {
    suspend fun executeSequence(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val request = try {
            parseSequenceRequest(json)
        } catch (error: InvalidSequencePayloadException) {
            reply.write(bridgeJson.invalidSequenceResponse(requestId, json, error))
            return
        }
        val observation = captures.lookup(request.observationId)
        if (observation == null) {
            reply.write(
                bridgeJson.sequenceResultResponse(
                    requestId,
                    unstartedSequenceFailure(
                        actions = request.actions,
                        code = BridgeErrorCodes.OBSERVATION_MISSING,
                        message = "The supplied observationId is missing or expired; observe the phone before retrying.",
                    ),
                ),
            )
            return
        }
        val runSessionKey = coordinator.activeSessionId()
        if (taskDisplayRequiredProvider() && runSessionKey == null) {
            reply.write(
                bridgeJson.sequenceResultResponse(
                    requestId,
                    unstartedSequenceFailure(
                        actions = request.actions,
                        code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
                        message = "No active task display is available; the physical display was not touched.",
                    ),
                ),
            )
            return
        }
        if (!coordinator.awaitPhoneAccessForTool()) {
            reply.write(
                bridgeJson.sequenceResultResponse(
                    requestId,
                    unstartedSequenceFailure(
                        actions = request.actions,
                        code = BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE,
                        message = "Phone access is no longer available; the sequence was not executed.",
                    ),
                ),
            )
            return
        }
        val target = if (taskDisplayRequiredProvider() || request.displayRef != null || observation.taskSessionKey != null) {
            when (val resolution = displayTargets.resolve(
                displayRef = request.displayRef,
                fallbackDisplayId = observation.displayId,
                fallbackDisplayRef = observation.taskSessionKey?.let { taskSessionKey ->
                    taskDisplayReference(taskSessionKey, observation.displayId)
                },
            )) {
                is TaskDisplayResolution.Ready -> resolution.target
                is TaskDisplayResolution.Unavailable -> {
                    reply.write(
                        bridgeJson.sequenceResultResponse(
                            requestId,
                            unstartedSequenceFailure(
                                actions = request.actions,
                                code = resolution.code,
                                message = resolution.message,
                            ),
                        ),
                    )
                    return
                }
            }
        } else {
            null
        }
        if (target != null &&
            (observation.taskSessionKey != target.session.sessionKey ||
                observation.displayId != target.session.displayId)
        ) {
            reply.write(
                bridgeJson.sequenceResultResponse(
                    requestId,
                    unstartedSequenceFailure(
                        actions = request.actions,
                        code = BridgeErrorCodes.DISPLAY_CHANGED,
                        message = "The observation belongs to a different task display; no input was sent.",
                    ),
                ),
            )
            return
        }

        val result = SequenceExecutor(
            executeAction = { action, baseline ->
                coordinator.executeAction(
                    action = action,
                    observation = baseline,
                    toolName = ToolNames.EXECUTE_SEQUENCE,
                    targetDisplay = target?.session,
                )
            },
            captureAfterAction = { guardRegions ->
                captures.captureWithRetry(
                    expectedPackageName = null,
                    guardRegions = guardRegions,
                    taskSessionKey = target?.session?.sessionKey ?: runSessionKey,
                    displayId = target?.session?.displayId,
                    expectedDisplayRef = target?.displayRef,
                )
            },
            rememberObservation = captures::remember,
            settleAfterAction = ::settleAfterAction,
        ).execute(observation, request.actions)
        reply.write(bridgeJson.sequenceResultResponse(requestId, result, observation))
    }
}
