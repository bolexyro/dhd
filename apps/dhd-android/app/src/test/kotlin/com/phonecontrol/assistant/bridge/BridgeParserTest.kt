package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.BackAction
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.KeypressAction
import com.phonecontrol.assistant.domain.KeypressKey
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.SwipeAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.TypeAction
import com.phonecontrol.assistant.domain.WaitAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeParserTest {
    private val server = BridgeHarness().server
    private val metadata = ActionMetadata(
        purpose = "Tap the cart",
        observationId = "obs-1",
        targetDescription = "Cart",
    )

    private fun metadataJson(
        purpose: String = "Tap the cart",
        targetDescription: String = "Cart",
        observationId: String? = "obs-1",
    ): JSONObject = JSONObject()
        .put("purpose", purpose)
        .put("targetDescription", targetDescription)
        .also { json -> observationId?.let { json.put("observationId", it) } }

    private fun action(type: String, vararg fields: Pair<String, Any>): JSONObject = JSONObject()
        .put("type", type)
        .put("metadata", metadataJson())
        .also { json -> fields.forEach { (key, value) -> json.put(key, value) } }

    private fun parseError(json: JSONObject): String =
        assertThrows(IllegalArgumentException::class.java) { server.parsePhoneAction(json) }.message!!

    @Test
    fun `every supported action type parses to its typed action`() {
        val cases = listOf<Pair<JSONObject, PhoneAction>>(
            action("open_app", "packageName" to "com.example.shop") to OpenAppAction("com.example.shop", metadata),
            action("tap", "x" to 10, "y" to 20) to TapAction(10, 20, metadata),
            action("type", "text" to "milk and eggs") to TypeAction("milk and eggs", metadata),
            action("swipe", "startX" to 1, "startY" to 2, "endX" to 3, "endY" to 4) to
                SwipeAction(1, 2, 3, 4, 350L, metadata),
            action("swipe", "startX" to 1, "startY" to 2, "endX" to 3, "endY" to 4, "durationMs" to 900) to
                SwipeAction(1, 2, 3, 4, 900L, metadata),
            action("back") to BackAction(metadata),
            action("keypress", "key" to "enter") to KeypressAction(KeypressKey.ENTER, metadata),
            action("keypress", "key" to "HOME") to KeypressAction(KeypressKey.HOME, metadata),
            action("wait", "durationMs" to 1500) to WaitAction(1500L, metadata),
        )
        cases.forEach { (json, expected) -> assertEquals(json.toString(), expected, server.parsePhoneAction(json)) }
    }

    @Test
    fun `wire action names match the action types`() {
        assertEquals(
            listOf("open_app", "tap", "type", "swipe", "back", "keypress", "wait"),
            listOf(
                OpenAppAction("com.example.shop", metadata),
                TapAction(1, 1, metadata),
                TypeAction("a", metadata),
                SwipeAction(1, 1, 2, 2, 350L, metadata),
                BackAction(metadata),
                KeypressAction(KeypressKey.BACK, metadata),
                WaitAction(1L, metadata),
            ).map(server::wireActionName),
        )
    }

    @Test
    fun `invalid actions are rejected with stable messages`() {
        assertEquals(
            "Unsupported action type. Use open_app, tap, type, swipe, back, keypress, or wait.",
            parseError(action("pinch")),
        )
        assertEquals(
            "Unsupported action type. Use open_app, tap, type, swipe, back, keypress, or wait.",
            parseError(action("TAP", "x" to 1, "y" to 1)),
        )
        assertEquals("packageName is not a valid Android package name.", parseError(action("open_app", "packageName" to "shop")))
        assertEquals("packageName is not a valid Android package name.", parseError(action("open_app")))
        assertEquals("Unsupported enum value: volume_up", parseError(action("keypress", "key" to "volume_up")))
        assertEquals("Tap x must be non-negative", parseError(action("tap", "x" to -1, "y" to 0)))
        assertEquals("Type text must not be empty", parseError(action("type", "text" to "")))
        assertEquals(
            "Swipe duration must be between 1 and 10000 ms",
            parseError(action("swipe", "startX" to 1, "startY" to 2, "endX" to 3, "endY" to 4, "durationMs" to 0)),
        )
        assertEquals("Wait duration must be between 1 and 30000 ms", parseError(action("wait", "durationMs" to 30_001)))
    }

    @Test
    fun `missing required numeric fields fail before an action is built`() {
        assertThrows(Exception::class.java) { server.parsePhoneAction(action("tap", "x" to 1)) }
        assertThrows(Exception::class.java) { server.parsePhoneAction(action("wait")) }
        assertThrows(Exception::class.java) { server.parsePhoneAction(action("type")) }
    }

    @Test
    fun `metadata is validated and trimmed`() {
        assertEquals(
            ActionMetadata(purpose = "Tap", observationId = "obs-1", targetDescription = "Cart"),
            server.parseMetadata(metadataJson(purpose = "  Tap ", targetDescription = " Cart ", observationId = " obs-1 ")),
        )
        assertEquals("", server.parseMetadata(metadataJson(observationId = null)).observationId)
        val cases = listOf(
            null to "action.metadata is required.",
            metadataJson(purpose = " ") to "metadata.purpose must be 1-240 characters.",
            metadataJson(purpose = "p".repeat(241)) to "metadata.purpose must be 1-240 characters.",
            metadataJson(targetDescription = "") to "metadata.targetDescription must be 1-240 characters.",
            metadataJson(targetDescription = "t".repeat(241)) to "metadata.targetDescription must be 1-240 characters.",
            metadataJson(observationId = "o".repeat(241)) to "metadata.observationId must be at most 240 characters.",
        )
        cases.forEach { (json, message) ->
            assertEquals(
                message,
                assertThrows(IllegalArgumentException::class.java) { server.parseMetadata(json) }.message,
            )
        }
        assertEquals(240, server.parseMetadata(metadataJson(purpose = "p".repeat(240))).purpose.length)
    }

    @Test
    fun `guard regions are parsed and bounded`() {
        assertEquals(emptyList<GuardRegion>(), server.parseGuardRegions(null))
        assertEquals(
            listOf(GuardRegion(1, 2, 30, 40)),
            server.parseGuardRegions(
                JSONArray().put(JSONObject().put("left", 1).put("top", 2).put("right", 30).put("bottom", 40)),
            ),
        )
        val region = JSONObject().put("left", 0).put("top", 0).put("right", 10).put("bottom", 10)
        assertEquals(8, server.parseGuardRegions(JSONArray(List(8) { region })).size)
        assertEquals(
            "At most 8 guard regions are supported.",
            assertThrows(IllegalArgumentException::class.java) {
                server.parseGuardRegions(JSONArray(List(9) { region }))
            }.message,
        )
        assertEquals(
            "Guard region right must be greater than left",
            assertThrows(IllegalArgumentException::class.java) {
                server.parseGuardRegions(
                    JSONArray().put(JSONObject().put("left", 10).put("top", 0).put("right", 10).put("bottom", 5)),
                )
            }.message,
        )
        assertEquals(
            listOf(GuardRegion(0, 0, 10, 10)),
            server.parseMetadata(metadataJson().put("guardRegions", JSONArray().put(region))).guardRegions,
        )
    }

    @Test
    fun `sequence requests bind the display reference`() {
        val request = server.parseSequenceRequest(
            JSONObject()
                .put("observationId", " obs-1 ")
                .put("displayRef", "dsp_0123456789abcd")
                .put("actions", JSONArray().put(action("back").put("metadata", metadataJson(observationId = null)))),
        )
        assertEquals("obs-1", request.observationId)
        assertEquals("dsp_0123456789abcd", request.displayRef)
        assertEquals(listOf<PhoneAction>(BackAction(metadata.copy(observationId = ""))), request.actions)
    }

    @Test
    fun `sequence request errors carry the failing index`() {
        fun failure(json: JSONObject): Pair<Int?, String?> =
            assertThrows(DevBridgeServer.InvalidSequencePayloadException::class.java) {
                server.parseSequenceRequest(json)
            }.let { it.index to it.message }
        val back = action("back").put("metadata", metadataJson(observationId = null))
        assertEquals(null to "observationId must be 1-240 characters.", failure(JSONObject().put("actions", JSONArray().put(back))))
        assertEquals(
            null to "observationId must be 1-240 characters.",
            failure(JSONObject().put("observationId", "o".repeat(241)).put("actions", JSONArray().put(back))),
        )
        assertEquals(null to "actions must be an array.", failure(JSONObject().put("observationId", "obs-1")))
        assertEquals(
            null to "A sequence must contain between 1 and 16 actions.",
            failure(JSONObject().put("observationId", "obs-1").put("actions", JSONArray())),
        )
        assertEquals(
            1 to "Sequence action 1 is invalid: Unsupported action type. Use open_app, tap, type, swipe, back, keypress, or wait.",
            failure(
                JSONObject().put("observationId", "obs-1").put(
                    "actions",
                    JSONArray().put(back).put(JSONObject().put("type", "pinch").put("metadata", metadataJson(observationId = null))),
                ),
            ),
        )
        assertEquals(
            "displayRef must match dsp_ followed by 14 lowercase hexadecimal characters.",
            assertThrows(IllegalArgumentException::class.java) {
                server.parseSequenceRequest(
                    JSONObject().put("observationId", "obs-1").put("displayRef", "DSP_0123456789ABCD").put("actions", JSONArray().put(back)),
                )
            }.message,
        )
    }

    @Test
    fun `fallback tool names follow the action type`() {
        assertEquals("dhd_open_app", server.fallbackActionToolName(JSONObject().put("action", JSONObject().put("type", "OPEN_APP"))))
        assertEquals("dhd_execute", server.fallbackActionToolName(JSONObject().put("action", JSONObject().put("type", "tap"))))
        assertEquals("dhd_execute", server.fallbackActionToolName(JSONObject()))
    }

    @Test
    fun `metadata purpose is read from each tool shape in priority order`() {
        assertNull(server.metadataPurpose(JSONObject()))
        assertEquals("Direct", server.metadataPurpose(JSONObject().put("metadata", JSONObject().put("purpose", " Direct "))))
        assertEquals(
            "Action",
            server.metadataPurpose(
                JSONObject()
                    .put("metadata", JSONObject().put("purpose", " "))
                    .put("action", JSONObject().put("metadata", JSONObject().put("purpose", "Action"))),
            ),
        )
        assertEquals(
            "Second step",
            server.metadataPurpose(
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
            assertEquals("$toolName $json", expected, server.toolPurpose(toolName, json))
        }
    }

    @Test
    fun `open app label equal to the package name falls back to the default purpose`() {
        val harness = BridgeHarness()
        harness.platform.labels["com.example.plain"] = "COM.EXAMPLE.PLAIN"
        assertEquals(
            "Opening an app",
            harness.server.toolPurpose("dhd_open_app", JSONObject().put("action", JSONObject().put("packageName", "com.example.plain"))),
        )
    }
}
