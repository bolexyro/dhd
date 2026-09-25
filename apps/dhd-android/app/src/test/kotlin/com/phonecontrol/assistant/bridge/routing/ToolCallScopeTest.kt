package com.phonecontrol.assistant.bridge.routing

import com.phonecontrol.assistant.bridge.BridgeHarness
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallScopeTest {
    private val harness = BridgeHarness()
    private val toolCalls = ToolCallScope(harness.coordinator, harness.platform)

    @Test
    fun `fallback tool names follow the action type`() {
        assertEquals("dhd_open_app", toolCalls.fallbackActionToolName(JSONObject().put("action", JSONObject().put("type", "OPEN_APP"))))
        assertEquals("dhd_execute", toolCalls.fallbackActionToolName(JSONObject().put("action", JSONObject().put("type", "tap"))))
        assertEquals("dhd_execute", toolCalls.fallbackActionToolName(JSONObject()))
    }

    @Test
    fun `metadata purpose is read from each tool shape in priority order`() {
        assertNull(toolCalls.metadataPurpose(JSONObject()))
        assertEquals("Direct", toolCalls.metadataPurpose(JSONObject().put("metadata", JSONObject().put("purpose", " Direct "))))
        assertEquals(
            "Action",
            toolCalls.metadataPurpose(
                JSONObject()
                    .put("metadata", JSONObject().put("purpose", " "))
                    .put("action", JSONObject().put("metadata", JSONObject().put("purpose", "Action"))),
            ),
        )
        assertEquals(
            "Second step",
            toolCalls.metadataPurpose(
                JSONObject().put(
                    "actions",
                    JSONArray()
                        .put(JSONObject().put("metadata", JSONObject().put("purpose", "")))
                        .put("not an object")
                        .put(JSONObject().put("metadata", JSONObject().put("purpose", "Second step"))),
                ),
            ),
        )
    }

    @Test
    fun `tool purposes fall back to defaults and app labels`() {
        val cases = listOf(
            Triple("dhd_observe", JSONObject(), "Inspecting the current screen"),
            Triple("dhd_observe", JSONObject().put("purpose", " Reading the price "), "Reading the price"),
            Triple("dhd_open_app", JSONObject().put("action", JSONObject().put("packageName", "com.example.shop")), "Opening Shop"),
            Triple("dhd_open_app", JSONObject().put("action", JSONObject().put("packageName", "com.example.unknown")), "Opening an app"),
            Triple("dhd_execute", JSONObject().put("action", JSONObject().put("type", "open_app").put("packageName", "com.example.mail")), "Opening Mail"),
            Triple("dhd_execute", JSONObject().put("action", JSONObject().put("type", "tap")), "Performing a phone interaction"),
            Triple("dhd_set_app_display_layout", JSONObject().put("packageName", "com.example.shop").put("layout", "full_size"), "Fitting Shop to the task display"),
            Triple("dhd_set_app_display_layout", JSONObject().put("packageName", "com.example.x").put("layout", "full_size"), "Fitting the app to the task display"),
            Triple("dhd_set_app_display_layout", JSONObject().put("packageName", "com.example.shop").put("layout", "STANDARD"), "Restoring Shop's standard task layout"),
            Triple("dhd_set_app_display_layout", JSONObject().put("layout", "standard"), "Restoring the standard task layout"),
            Triple("dhd_set_app_display_layout", JSONObject().put("layout", "huge"), "Adjusting the app's task-display layout"),
            Triple("dhd_request_attention", JSONObject(), "Waiting for your attention"),
            Triple("dhd_list_allowed_apps", JSONObject(), "Checking which apps DHD can use"),
            Triple("custom_tool", JSONObject(), "Working with the phone"),
            Triple("dhd_observe", JSONObject().put("metadata", JSONObject().put("purpose", "From metadata")), "From metadata"),
        )
        cases.forEach { (toolName, json, expected) ->
            assertEquals("$toolName $json", expected, toolCalls.toolPurpose(toolName, json))
        }
    }

    @Test
    fun `open app label equal to the package name falls back to the default purpose`() {
        harness.platform.labels["com.example.plain"] = "COM.EXAMPLE.PLAIN"
        assertEquals(
            "Opening an app",
            toolCalls.toolPurpose("dhd_open_app", JSONObject().put("action", JSONObject().put("packageName", "com.example.plain"))),
        )
    }
}
