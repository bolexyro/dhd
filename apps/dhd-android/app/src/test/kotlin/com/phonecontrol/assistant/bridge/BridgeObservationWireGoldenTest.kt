package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.domain.ScreenProtection
import com.phonecontrol.assistant.domain.ScreenProtectionStatus
import com.phonecontrol.assistant.observation.ForegroundAppInfo
import com.phonecontrol.assistant.observation.ForegroundAppResult
import com.phonecontrol.assistant.observation.ObservationCaptureResult
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeObservationWireGoldenTest {
    private val secureScreen = ScreenProtection(
        status = ScreenProtectionStatus.SECURE,
        requiresUserAttention = true,
        signals = listOf("FLAG_SECURE"),
        reason = "The payment screen is protected.",
    )

    @Test
    fun `observe the physical display`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.observations.captures += captured(snapshot("obs-1"))
        harness.exchange(
            "observe.physical_display",
            request("observe", "tool" to "dhd_observe", "purpose" to "Checking the cart", "targetDescription" to "Cart"),
        )
    }

    @Test
    fun `observe the task display`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.prepareTaskDisplay()
        harness.observations.captures += captured(
            snapshot("obs-1", taskSessionKey = TASK_RUN_KEY, displayId = TASK_DISPLAY_ID, screenProtection = secureScreen),
        )
        harness.exchange("observe.task_display", request("observe", "tool" to "dhd_observe"))
        assertEquals(
            CaptureCall(null, emptyList(), TASK_RUN_KEY, TASK_DISPLAY_ID, displayRecord().displayRef),
            harness.observations.captureCalls.single(),
        )
    }

    @Test
    fun `observe reports a failed capture after retries`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.observations.fallbackCapture = ObservationCaptureResult.Failed("screencap failed")
        harness.exchange("observe.capture_failed", request("observe"))
        assertEquals(5, harness.observations.captureCalls.size)
    }

    @Test
    fun `observe without phone access`() = runTest {
        BridgeHarness().exchange("observe.developer_mode_unavailable", request("observe"))
    }

    @Test
    fun `observe without a task display`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.exchange("observe.task_display_unavailable", request("observe"))
    }

    @Test
    fun `observe an unknown display reference`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.prepareTaskDisplay()
        harness.exchange("observe.display_not_found", request("observe", "displayRef" to "dsp_00000000000000"))
    }

    @Test
    fun `observe with a malformed display reference`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("observe.invalid_display_ref", request("observe", "displayRef" to "display-7"))
    }

    @Test
    fun `foreground app on the task display`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.prepareTaskDisplay()
        harness.observations.foreground = ForegroundAppResult.Succeeded(
            ForegroundAppInfo(
                packageName = "com.example.shop",
                activityName = "com.example.shop.CartActivity",
                displayId = TASK_DISPLAY_ID,
                rotation = 0,
                width = 720,
                height = 1560,
                screenProtection = ScreenProtection(
                    status = ScreenProtectionStatus.BLANK_UNKNOWN,
                    requiresUserAttention = false,
                    signals = listOf("BLANK_FRAME", "NO_WINDOW"),
                ),
            ),
        )
        harness.exchange("foreground_app.task_display", request("foreground_app", "tool" to "dhd_get_foreground_app"))
    }

    @Test
    fun `foreground app on the physical display`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.observations.foreground = ForegroundAppResult.Succeeded(
            ForegroundAppInfo(
                packageName = "com.example.shop",
                activityName = "com.example.shop.MainActivity",
                displayId = 0,
                rotation = 1,
                width = 2400,
                height = 1080,
            ),
        )
        harness.exchange("foreground_app.physical_display", request("foreground_app"))
    }

    @Test
    fun `foreground app failure`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        harness.exchange("foreground_app.failed", request("foreground_app"))
    }

    @Test
    fun `foreground app without phone access`() = runTest {
        BridgeHarness().exchange("foreground_app.developer_mode_unavailable", request("foreground_app"))
    }

    @Test
    fun `foreground app without a task display`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.exchange("foreground_app.task_display_unavailable", request("foreground_app"))
    }

    @Test
    fun `attention resolved returns a fresh observation`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.prepareTaskDisplay()
        harness.observations.captures += captured(
            snapshot("obs-attention", taskSessionKey = TASK_RUN_KEY, displayId = TASK_DISPLAY_ID),
        )
        val pending = async {
            harness.exchange(
                "request_attention.resolved",
                request("request_attention", "reason" to "Approve the payment on the phone."),
            )
        }
        runCurrent()
        assertTrue(harness.coordinator.acknowledgeAttention())
        pending.await()
        assertEquals(
            listOf(
                "showAttentionNotification:Approve the payment on the phone.:conversation-1",
                "removeAttentionNotification",
            ),
            harness.platform.calls,
        )
    }

    @Test
    fun `attention resolved without an observation`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.prepareTaskDisplay()
        harness.observations.fallbackCapture = ObservationCaptureResult.Failed(
            message = "The task display is gone.",
            code = "TASK_DISPLAY_UNAVAILABLE",
        )
        val pending = async {
            harness.exchange("request_attention.resolved_observation_failed", request("request_attention"))
        }
        runCurrent()
        assertTrue(harness.coordinator.acknowledgeAttention())
        pending.await()
    }

    @Test
    fun `attention cancelled when the session stops`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.prepareTaskDisplay()
        val pending = async {
            harness.exchange("request_attention.cancelled", request("request_attention", "reason" to "Sign in please."))
        }
        runCurrent()
        assertTrue(harness.coordinator.stop())
        pending.await()
    }

    @Test
    fun `attention without a session`() = runTest {
        BridgeHarness().exchange("request_attention.session_not_running", request("request_attention"))
    }

    @Test
    fun `attention while another attention is pending`() = runTest {
        val harness = BridgeHarness()
        harness.startSession()
        assertTrue(harness.coordinator.requestAttention("First"))
        harness.exchange("request_attention.already_pending", request("request_attention", "reason" to "Second"))
    }

    @Test
    fun `attention without a task display`() = runTest {
        val harness = BridgeHarness(taskDisplayRequired = true)
        harness.startSession()
        harness.backend.defaultResolution = TaskDisplayResolution.Unavailable(
            code = "DISPLAY_EXPIRED",
            message = "The retained task display expired.",
        )
        harness.exchange("request_attention.task_display_unavailable", request("request_attention"))
    }
}
