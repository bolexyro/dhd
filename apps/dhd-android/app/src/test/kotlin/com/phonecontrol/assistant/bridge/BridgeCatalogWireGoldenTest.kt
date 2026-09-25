package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.execution.TaskDisplayCloseResult
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.taskDisplayReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeCatalogWireGoldenTest {
    @Test
    fun `allowed apps returns the allowlist`() = runTest {
        val harness = BridgeHarness()
        harness.allowedPackages = setOf("com.example.shop", "com.example.alpha")
        harness.exchange("allowed_apps.allowlist", request("allowed_apps"))
    }

    @Test
    fun `allowed apps reports full access`() = runTest {
        BridgeHarness(fullAccess = true).exchange("allowed_apps.full_access", request("allowed_apps"))
    }

    @Test
    fun `allowed apps lists allowed launchable apps on request`() = runTest {
        BridgeHarness().exchange("allowed_apps.include_all_allowlist", request("allowed_apps", "includeAll" to true))
    }

    @Test
    fun `allowed apps lists every launchable app with full access`() = runTest {
        BridgeHarness(fullAccess = true).exchange(
            "allowed_apps.include_all_full_access",
            request("allowed_apps", "includeAll" to true),
        )
    }

    @Test
    fun `browse apps matches labels and packages`() = runTest {
        BridgeHarness().exchange("browse_apps.results", request("browse_apps", "query" to "example"))
    }

    @Test
    fun `browse apps truncates long result lists`() = runTest {
        val harness = BridgeHarness(fullAccess = true)
        harness.platform.apps = (1..26).map { index ->
            InstalledUserApp(packageName = "com.example.app$index", label = "App $index")
        }
        val response = harness.send(request("browse_apps", "query" to "app"))[1]
        assertEquals(25, response.getInt("count"))
        assertEquals(true, response.getBoolean("truncated"))
    }

    @Test
    fun `browse apps rejects an empty query`() = runTest {
        BridgeHarness().exchange("browse_apps.invalid_query", request("browse_apps", "query" to " "))
    }

    @Test
    fun `set app display layout saves full size`() = runTest {
        val harness = BridgeHarness()
        harness.exchange(
            "set_app_display_layout.full_size_changed",
            request("set_app_display_layout", "packageName" to "com.example.shop", "layout" to "full_size"),
        )
        assertEquals(setOf("com.example.shop"), harness.platform.fullSizePackages)
    }

    @Test
    fun `set app display layout keeps an unchanged standard layout`() = runTest {
        BridgeHarness().exchange(
            "set_app_display_layout.standard_unchanged",
            request("set_app_display_layout", "packageName" to "com.example.shop", "layout" to "STANDARD"),
        )
    }

    @Test
    fun `set app display layout rejects an invalid package`() = runTest {
        BridgeHarness().exchange(
            "set_app_display_layout.invalid_package",
            request("set_app_display_layout", "packageName" to "shop", "layout" to "full_size"),
        )
    }

    @Test
    fun `set app display layout rejects an unknown layout`() = runTest {
        BridgeHarness().exchange(
            "set_app_display_layout.invalid_layout",
            request("set_app_display_layout", "packageName" to "com.example.shop", "layout" to "huge"),
        )
    }

    @Test
    fun `set app display layout rejects an app that is not installed`() = runTest {
        BridgeHarness().exchange(
            "set_app_display_layout.app_not_found",
            request("set_app_display_layout", "packageName" to "com.example.missing", "layout" to "full_size"),
        )
    }

    @Test
    fun `set app display layout rejects an app outside the allowlist`() = runTest {
        BridgeHarness().exchange(
            "set_app_display_layout.app_not_allowed",
            request("set_app_display_layout", "packageName" to "com.example.mail", "layout" to "full_size"),
        )
    }

    @Test
    fun `list displays returns live and retained displays only`() = runTest {
        val harness = BridgeHarness()
        val now = harness.clock.wall
        harness.backend.records.value = listOf(
            displayRecord(),
            displayRecord(
                sessionKey = "retained-run",
                displayId = 8,
                status = TaskDisplayStatus.COMPLETED,
                terminalAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 240_000L,
                lastPurpose = "Task complete",
            ),
            displayRecord(
                sessionKey = "failed-run",
                displayId = 9,
                packageName = "com.example.unlabelled",
                status = TaskDisplayStatus.FAILED,
                terminalAtEpochMs = now - 1_000L,
                expiresAtEpochMs = now + 1_000L,
                error = "The app crashed.",
            ),
            displayRecord(
                sessionKey = "expired-by-time",
                displayId = 10,
                status = TaskDisplayStatus.STOPPED,
                terminalAtEpochMs = now - 400_000L,
                expiresAtEpochMs = now,
            ),
            displayRecord(sessionKey = "ended-run", displayId = 11, status = TaskDisplayStatus.ENDED),
            displayRecord(sessionKey = "expired-run", displayId = 12, status = TaskDisplayStatus.EXPIRED),
        )
        harness.exchange("list_displays.inventory", request("list_displays"))
    }

    @Test
    fun `list displays with no displays`() = runTest {
        BridgeHarness().exchange("list_displays.empty", request("list_displays"))
    }

    @Test
    fun `list displays without a registry`() = runTest {
        BridgeHarness(withBackend = false).exchange("list_displays.unavailable", request("list_displays"))
    }

    @Test
    fun `close display ends the selected display`() = runTest {
        val harness = BridgeHarness()
        val record = displayRecord(sessionKey = "retained-run", displayId = 8, status = TaskDisplayStatus.COMPLETED)
        harness.backend.records.value = listOf(record)
        harness.backend.closeResult = TaskDisplayCloseResult.Closed(record.copy(status = TaskDisplayStatus.ENDED))
        harness.exchange(
            "close_display.closed",
            request("close_display", "displayRef" to taskDisplayReference("retained-run", 8)),
        )
    }

    @Test
    fun `close display requires a display reference`() = runTest {
        BridgeHarness().exchange("close_display.reference_required", request("close_display"))
    }

    @Test
    fun `close display rejects a malformed display reference`() = runTest {
        BridgeHarness().exchange("close_display.invalid_ref", request("close_display", "displayRef" to "display-7"))
    }

    @Test
    fun `close display with an unknown display reference`() = runTest {
        BridgeHarness().exchange(
            "close_display.not_found",
            request("close_display", "displayRef" to "dsp_00000000000000"),
        )
    }

    @Test
    fun `close display refuses a display used by the active run`() = runTest {
        val harness = BridgeHarness()
        val sessionId = harness.startSession()
        harness.backend.records.value = listOf(displayRecord())
        harness.backend.claimedByRun += TASK_DISPLAY_ID to sessionId
        harness.exchange(
            "close_display.in_use",
            request("close_display", "displayRef" to taskDisplayReference(TASK_RUN_KEY, TASK_DISPLAY_ID)),
        )
    }

    @Test
    fun `close display reports a backend rejection`() = runTest {
        val harness = BridgeHarness()
        harness.backend.records.value = listOf(displayRecord())
        harness.backend.closeResult = TaskDisplayCloseResult.Rejected(
            code = "DISPLAY_CHANGED",
            message = "The display was recreated.",
        )
        harness.exchange(
            "close_display.rejected",
            request("close_display", "displayRef" to taskDisplayReference(TASK_RUN_KEY, TASK_DISPLAY_ID)),
        )
    }

    @Test
    fun `close display without a registry`() = runTest {
        BridgeHarness(withBackend = false).exchange(
            "close_display.unavailable",
            request("close_display", "displayRef" to "dsp_00000000000000"),
        )
    }
}
