package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.ObservationSize
import com.phonecontrol.assistant.domain.ScreenProtection
import com.phonecontrol.assistant.domain.ScreenProtectionStatus
import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.domain.StaleObservationReason
import com.phonecontrol.assistant.domain.StaleObservationReasonCode
import com.phonecontrol.assistant.execution.ObservationCaptureResult
import com.phonecontrol.assistant.execution.RejectionCode
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.TransportResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeActionWireGoldenTest {
    private val staleDetails = StaleObservationDiagnostics(
        approvedObservationId = "obs-1",
        currentObservationId = "obs-live",
        reasons = listOf(
            StaleObservationReason(
                code = StaleObservationReasonCode.GUARD_REGION_CHANGED,
                guardRegion = GuardRegion(left = 10, top = 20, right = 110, bottom = 220),
            ),
            StaleObservationReason(
                code = StaleObservationReasonCode.DISPLAY_SIZE_CHANGED,
                approved = ObservationSize(720, 1560),
                current = ObservationSize(1560, 720),
            ),
            StaleObservationReason(
                code = StaleObservationReasonCode.PACKAGE_CHANGED,
                approved = "com.example.shop",
                current = "com.example.mail",
            ),
            StaleObservationReason(code = StaleObservationReasonCode.ROTATION_CHANGED, approved = 0, current = 1),
            StaleObservationReason(code = StaleObservationReasonCode.TASK_SESSION_CHANGED, current = "other-run"),
        ),
    )

    private fun tapRequest(observationId: String = "obs-1", vararg extra: Pair<String, Any?>): JSONObject = request(
        "execute_action",
        "tool" to "dhd_execute",
        "action" to actionJson("tap", observationId = observationId, fields = mapOf("x" to 100, "y" to 200)),
        *extra,
    )

    private fun openAppRequest(packageName: String = "com.example.shop"): JSONObject = request(
        "execute_action",
        "tool" to "dhd_open_app",
        "action" to actionJson(
            "open_app",
            purpose = "Open the shop",
            targetDescription = "Shop app",
            fields = mapOf("packageName" to packageName),
        ),
    )

    private suspend fun taskHarnessWithObservation(
        observed: com.phonecontrol.assistant.domain.ObservationSnapshot = snapshot(
            "obs-1",
            taskSessionKey = TASK_RUN_KEY,
            displayId = TASK_DISPLAY_ID,
        ),
    ): BridgeHarness {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.prepareTaskDisplay()
        harness.observations.captures += captured(observed)
        harness.send(request("observe"))
        return harness
    }

    private fun postObservation(id: String = "obs-2") =
        captured(snapshot(id, taskSessionKey = TASK_RUN_KEY, displayId = TASK_DISPLAY_ID))

    @Test
    fun `tap succeeds and returns a fresh observation`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.transport.results += TransportResult.Succeeded("Tapped the cart button.", BEFORE_SCREENSHOT_BYTES)
        harness.observations.captures += postObservation()
        harness.exchange("execute_action.tap_succeeded", tapRequest())
    }

    @Test
    fun `open app on a new task display publishes the calibration pointer`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        val sessionId = harness.startSession()
        harness.backend.sessions[sessionId] = displaySession(sessionKey = sessionId)
        harness.transport.results += TransportResult.Succeeded("Opened Shop.")
        harness.observations.captures += captured(
            snapshot("obs-open", taskSessionKey = sessionId, displayId = TASK_DISPLAY_ID),
        )
        harness.exchange(
            "execute_action.open_app_succeeded",
            openAppRequest(),
            normalizeResponse = ::normalizeCalibrationPointer,
        )
    }

    @Test
    fun `action without an active run on a task display phone`() = runTest {
        BridgeHarness(taskDisplayRequired = true).exchange(
            "execute_action.task_display_unavailable",
            tapRequest(),
        )
    }

    @Test
    fun `action on an unknown display reference`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_action.display_not_found",
            tapRequest("obs-1", "displayRef" to "dsp_00000000000000"),
        )
    }

    @Test
    fun `action on a display that cannot be resolved`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.backend.resolution = TaskDisplayResolution.Unavailable(
            code = "DISPLAY_EXPIRED",
            message = "The retained task display expired.",
        )
        harness.exchange("execute_action.display_unresolved", tapRequest())
    }

    @Test
    fun `action from an observation of another display`() = runTest {
        val harness = taskHarnessWithObservation(
            snapshot("obs-1", taskSessionKey = "other-run", displayId = TASK_DISPLAY_ID),
        )
        harness.backend.records.value = listOf(displayRecord(), displayRecord(sessionKey = "other-run"))
        harness.exchange("execute_action.display_changed", tapRequest())
    }

    @Test
    fun `action with an unknown observation`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange("execute_action.observation_missing", tapRequest("obs-unknown"))
    }

    @Test
    fun `open app without a launch baseline`() = runTest {
        BridgeHarness().exchange("execute_action.open_app_baseline_failed", openAppRequest())
    }

    @Test
    fun `transport rejection carries stale diagnostics`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.transport.results += TransportResult.Rejected(
            code = RejectionCode.STALE_OBSERVATION,
            message = "The screen changed before the tap.",
            details = staleDetails,
        )
        harness.exchange("execute_action.transport_rejected_stale", tapRequest())
    }

    @Test
    fun `policy rejects a foreground app outside the allowlist`() = runTest {
        val harness = taskHarnessWithObservation(
            snapshot("obs-1", packageName = "com.example.mail", taskSessionKey = TASK_RUN_KEY, displayId = TASK_DISPLAY_ID),
        )
        harness.exchange("execute_action.policy_rejected", tapRequest())
    }

    @Test
    fun `secure screen keeps the policy code only on the completed line`() = runTest {
        val harness = taskHarnessWithObservation(
            snapshot(
                "obs-1",
                taskSessionKey = TASK_RUN_KEY,
                displayId = TASK_DISPLAY_ID,
                screenProtection = ScreenProtection(
                    status = ScreenProtectionStatus.SECURE,
                    requiresUserAttention = true,
                    signals = listOf("FLAG_SECURE"),
                    reason = "The payment screen is protected.",
                ),
            ),
        )
        val responses = harness.exchange("execute_action.secure_screen_requires_user", tapRequest())
        assertEquals("POLICY_REJECTED", responses[1].getString("code"))
        assertEquals("SECURE_SCREEN_REQUIRES_USER", responses[2].getString("code"))
    }

    @Test
    fun `unsupported transport result`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.transport.results += TransportResult.Unsupported("This action is not supported on this phone.")
        harness.exchange("execute_action.unsupported", tapRequest())
    }

    @Test
    fun `display limit returns the recovery inventory`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        val now = harness.clock.wall
        harness.backend.records.value = listOf(
            displayRecord(sessionKey = "older-run", displayId = 8, status = TaskDisplayStatus.COMPLETED, expiresAtEpochMs = now + 60_000L, terminalAtEpochMs = now - 1_000L),
        )
        harness.transport.results += TransportResult.Rejected(
            code = RejectionCode.DISPLAY_LIMIT_REACHED,
            message = "The virtual display limit was reached.",
        )
        harness.exchange("execute_action.display_limit_reached", openAppRequest())
    }

    @Test
    fun `post action observation failure`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.observations.fallbackCapture = ObservationCaptureResult.Failed("screencap failed")
        harness.exchange("execute_action.post_observation_failed", tapRequest())
    }

    @Test
    fun `post action observation failure keeps specific codes`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.observations.fallbackCapture = ObservationCaptureResult.Failed(
            message = "The display was released.",
            code = "TASK_DISPLAY_UNAVAILABLE",
        )
        harness.exchange("execute_action.post_observation_task_display_unavailable", tapRequest())
    }

    @Test
    fun `action while the session is paused`() = runTest {
        val harness = taskHarnessWithObservation()
        assertTrue(harness.coordinator.pause())
        harness.exchange("execute_action.session_not_running", tapRequest())
    }

    @Test
    fun `action payload must be an object`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("execute_action.missing_action", request("execute_action"))
    }

    @Test
    fun `action payload with an unsupported type`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange(
            "execute_action.unsupported_type",
            request("execute_action", "action" to actionJson("pinch")),
        )
    }

    @Test
    fun `sequence succeeds across two actions`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.transport.results += TransportResult.Succeeded("Tapped the search field.", BEFORE_SCREENSHOT_BYTES)
        harness.transport.results += TransportResult.Succeeded("Typed milk.")
        harness.observations.captures += postObservation("obs-2")
        harness.observations.captures += postObservation("obs-3")
        harness.exchange("execute_sequence.succeeded", sequenceRequest(tapStep(), typeStep()))
    }

    @Test
    fun `sequence stops at a failed step`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.transport.results += TransportResult.Succeeded("Tapped the search field.")
        harness.transport.results += TransportResult.Rejected(
            code = RejectionCode.FOREGROUND_CHANGED,
            message = "Another app came to the front.",
            details = staleDetails,
        )
        harness.observations.captures += postObservation("obs-2")
        harness.exchange("execute_sequence.step_failed", sequenceRequest(tapStep(), typeStep(), backStep()))
    }

    @Test
    fun `sequence stops when a post step observation fails`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.observations.fallbackCapture = ObservationCaptureResult.Failed("screencap failed")
        harness.exchange("execute_sequence.post_observation_failed", sequenceRequest(tapStep(), typeStep()))
    }

    @Test
    fun `sequence without actions`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_sequence.invalid_missing_actions",
            request("execute_sequence", "observationId" to "obs-1"),
        )
    }

    @Test
    fun `sequence without an observation id`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_sequence.invalid_observation_id",
            request("execute_sequence", "actions" to JSONArray().put(tapStep())),
        )
    }

    @Test
    fun `sequence with too many actions`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_sequence.invalid_too_many_actions",
            sequenceRequest(*Array(17) { backStep() }),
        )
    }

    @Test
    fun `sequence that tries to open an app`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_sequence.invalid_open_app",
            sequenceRequest(
                tapStep(),
                actionJson("open_app", purpose = "Open", targetDescription = "Shop", fields = mapOf("packageName" to "com.example.shop")),
            ),
        )
    }

    @Test
    fun `sequence step that supplies its own observation id`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_sequence.invalid_step_observation_id",
            sequenceRequest(tapStep(), actionJson("back", observationId = "obs-1")),
        )
    }

    @Test
    fun `sequence step without metadata`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_sequence.invalid_missing_metadata",
            sequenceRequest(JSONObject().put("type", "back")),
        )
    }

    @Test
    fun `sequence step that is not an object`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_sequence.invalid_step_type",
            request("execute_sequence", "observationId" to "obs-1", "actions" to JSONArray().put("back")),
        )
    }

    @Test
    fun `sequence step with an invalid action`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_sequence.invalid_step_action",
            sequenceRequest(actionJson("keypress", fields = mapOf("key" to "volume_up"))),
        )
    }

    @Test
    fun `sequence with an unknown observation`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.exchange(
            "execute_sequence.observation_missing",
            sequenceRequest(tapStep(), observationId = "obs-unknown"),
        )
    }

    @Test
    fun `sequence after the run ended`() = runTest {
        val harness = taskHarnessWithObservation()
        assertTrue(harness.coordinator.stop())
        harness.exchange("execute_sequence.task_display_unavailable", sequenceRequest(tapStep()))
    }

    @Test
    fun `sequence while phone access is lost`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.phoneAccessAvailable = false
        val pending = async {
            harness.exchange("execute_sequence.developer_mode_unavailable", sequenceRequest(tapStep()))
        }
        runCurrent()
        assertTrue(harness.coordinator.stop())
        pending.await()
    }

    @Test
    fun `sequence from an observation of another display`() = runTest {
        val harness = taskHarnessWithObservation(
            snapshot("obs-1", taskSessionKey = "other-run", displayId = TASK_DISPLAY_ID),
        )
        harness.backend.records.value = listOf(displayRecord(), displayRecord(sessionKey = "other-run"))
        harness.exchange("execute_sequence.display_changed", sequenceRequest(tapStep()))
    }

    @Test
    fun `sequence on a display that cannot be resolved`() = runTest {
        val harness = taskHarnessWithObservation()
        harness.backend.resolution = TaskDisplayResolution.Unavailable(
            code = "DISPLAY_EXPIRED",
            message = "The retained task display expired.",
        )
        harness.exchange("execute_sequence.display_unresolved", sequenceRequest(tapStep()))
    }

    @Test
    fun `demo run opens the app and taps`() = runTest {
        val harness = BridgeHarness()
        harness.observations.captures += captured(snapshot("obs-demo-1"))
        harness.observations.captures += captured(snapshot("obs-demo-2"))
        harness.exchange("demo_run.completed", demoRequest())
    }

    @Test
    fun `demo run stops when the app is not allowed`() = runTest {
        BridgeHarness().exchange("demo_run.open_rejected", demoRequest(packageName = "com.example.mail"))
    }

    @Test
    fun `demo run is refused while a session is active`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("demo_run.already_active", demoRequest())
    }

    @Test
    fun `demo run rejects negative coordinates`() = runTest {
        BridgeHarness().exchange("demo_run.invalid_coordinates", demoRequest(x = -1))
    }

    private fun demoRequest(packageName: String = "com.example.shop", x: Int = 100): JSONObject = request(
        "demo_run",
        "packageName" to packageName,
        "x" to x,
        "y" to 200,
        "purpose" to "Tap the demo button",
        "targetDescription" to "Demo button",
        "guardRegions" to JSONArray().put(
            JSONObject().put("left", 0).put("top", 0).put("right", 400).put("bottom", 400),
        ),
    )

    private fun tapStep(): JSONObject = actionJson(
        "tap",
        purpose = "Tap the search field",
        targetDescription = "Search field",
        fields = mapOf("x" to 100, "y" to 200),
    )

    private fun typeStep(): JSONObject = actionJson(
        "type",
        purpose = "Type milk",
        targetDescription = "Search field",
        fields = mapOf("text" to "milk"),
    )

    private fun backStep(): JSONObject = actionJson("back", purpose = "Go back", targetDescription = "Back")

    private fun sequenceRequest(vararg actions: JSONObject, observationId: String = "obs-1"): JSONObject = request(
        "execute_sequence",
        "tool" to "dhd_execute_sequence",
        "observationId" to observationId,
        "actions" to JSONArray(actions.toList()),
    )

    private fun normalizeCalibrationPointer(response: JSONObject) {
        val pointer = response.optJSONObject("initialPointer") ?: return
        assertTrue(pointer.getInt("x") in setOf(130, 590))
        assertTrue(pointer.getInt("y") in setOf(250, 1310))
        pointer.put("x", "{{calibrationX}}").put("y", "{{calibrationY}}")
    }
}
