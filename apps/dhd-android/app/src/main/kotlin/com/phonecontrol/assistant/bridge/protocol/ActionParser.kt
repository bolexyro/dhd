package com.phonecontrol.assistant.bridge.protocol

import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.DISPLAY_REF_PATTERN
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_GUARD_REGIONS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_SEQUENCE_ACTIONS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_TEXT_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.PACKAGE_PATTERN
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

internal data class SequenceRequest(
    val observationId: String,
    val actions: List<PhoneAction>,
    val displayRef: String? = null,
)

internal class InvalidSequencePayloadException(
    val index: Int?,
    message: String,
) : IllegalArgumentException(message)

internal object ActionParser {
    fun parseSequenceRequest(json: JSONObject): SequenceRequest {
        val observationId = json.optString("observationId").trim()
        if (observationId.isEmpty() || observationId.length > MAX_TEXT_CHARS) {
            throw InvalidSequencePayloadException(
                index = null,
                message = "observationId must be 1-$MAX_TEXT_CHARS characters.",
            )
        }
        val actionsJson = json.optJSONArray("actions")
            ?: throw InvalidSequencePayloadException(null, "actions must be an array.")
        if (actionsJson.length() !in 1..MAX_SEQUENCE_ACTIONS) {
            throw InvalidSequencePayloadException(
                index = null,
                message = "A sequence must contain between 1 and $MAX_SEQUENCE_ACTIONS actions.",
            )
        }
        val actions = buildList(actionsJson.length()) {
            for (index in 0 until actionsJson.length()) {
                val actionJson = actionsJson.optJSONObject(index)
                    ?: throw InvalidSequencePayloadException(index, "Sequence action $index must be an object.")
                val metadata = actionJson.optJSONObject("metadata")
                if (metadata == null) {
                    throw InvalidSequencePayloadException(index, "Sequence action $index must include metadata.")
                }
                if (metadata.has("observationId")) {
                    throw InvalidSequencePayloadException(
                        index,
                        "Sequence action $index receives observationId from the phone and must not provide one.",
                    )
                }
                val action = try {
                    parsePhoneAction(actionJson)
                } catch (error: Exception) {
                    throw InvalidSequencePayloadException(
                        index,
                        "Sequence action $index is invalid: ${error.message ?: "invalid action payload"}",
                    )
                }
                if (action is OpenAppAction) {
                    throw InvalidSequencePayloadException(
                        index,
                        "dhd_execute_sequence does not support open_app; use dhd_open_app first.",
                    )
                }
                add(action)
            }
        }
        return SequenceRequest(
            observationId = observationId,
            actions = actions,
            displayRef = optionalDisplayRef(json),
        )
    }

    fun parsePhoneAction(json: JSONObject): PhoneAction {
        val metadata = parseMetadata(json.optJSONObject("metadata"))
        return when (json.optString("type")) {
            "open_app" -> {
                val packageName = json.optString("packageName")
                require(PACKAGE_PATTERN.matches(packageName)) { "packageName is not a valid Android package name." }
                OpenAppAction(packageName, metadata)
            }

            "tap" -> TapAction(
                x = json.getInt("x"),
                y = json.getInt("y"),
                metadata = metadata,
            )

            "type" -> TypeAction(json.getString("text"), metadata)

            "swipe" -> SwipeAction(
                startX = json.getInt("startX"),
                startY = json.getInt("startY"),
                endX = json.getInt("endX"),
                endY = json.getInt("endY"),
                durationMs = json.optLong("durationMs", 350L),
                metadata = metadata,
            )

            "back" -> BackAction(metadata)

            "keypress" -> KeypressAction(
                key = enumValue<KeypressKey>(json.getString("key")),
                metadata = metadata,
            )

            "wait" -> WaitAction(json.getLong("durationMs"), metadata)

            else -> throw IllegalArgumentException(
                "Unsupported action type. Use open_app, tap, type, swipe, back, keypress, or wait.",
            )
        }
    }

    fun parseMetadata(json: JSONObject?): ActionMetadata {
        require(json != null) { "action.metadata is required." }
        val purpose = json.optString("purpose").trim()
        // The companion supplies observationId for the pre-action structural
        // comparison and may supply guardRegions for strict visual checking.
        val observationId = json.optString("observationId").trim()
        val targetDescription = json.optString("targetDescription").trim()
        require(purpose.isNotEmpty() && purpose.length <= MAX_TEXT_CHARS) {
            "metadata.purpose must be 1-$MAX_TEXT_CHARS characters."
        }
        require(observationId.length <= MAX_TEXT_CHARS) {
            "metadata.observationId must be at most $MAX_TEXT_CHARS characters."
        }
        require(targetDescription.isNotEmpty() && targetDescription.length <= MAX_TEXT_CHARS) {
            "metadata.targetDescription must be 1-$MAX_TEXT_CHARS characters."
        }
        return ActionMetadata(
            purpose = purpose,
            observationId = observationId,
            targetDescription = targetDescription,
            guardRegions = parseGuardRegions(json.optJSONArray("guardRegions")),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unsupported enum value: $value")

    fun wireActionName(action: PhoneAction): String = when (action) {
        is OpenAppAction -> "open_app"
        is TapAction -> "tap"
        is TypeAction -> "type"
        is SwipeAction -> "swipe"
        is BackAction -> "back"
        is KeypressAction -> "keypress"
        is WaitAction -> "wait"
    }

    fun parseGuardRegions(array: JSONArray?): List<GuardRegion> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_GUARD_REGIONS) { "At most $MAX_GUARD_REGIONS guard regions are supported." }
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val region = array.getJSONObject(index)
                add(
                    GuardRegion(
                        left = region.getInt("left"),
                        top = region.getInt("top"),
                        right = region.getInt("right"),
                        bottom = region.getInt("bottom"),
                    ),
                )
            }
        }
    }

    fun optionalDisplayRef(json: JSONObject): String? {
        val ref = json.optString("displayRef").trim().takeIf(String::isNotEmpty) ?: return null
        require(DISPLAY_REF_PATTERN.matches(ref)) {
            "displayRef must match dsp_ followed by 14 lowercase hexadecimal characters."
        }
        return ref
    }
}
