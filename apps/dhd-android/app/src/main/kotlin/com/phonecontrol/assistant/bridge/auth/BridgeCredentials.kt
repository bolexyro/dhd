package com.phonecontrol.assistant.bridge.auth

import com.phonecontrol.assistant.bridge.BridgePlatform
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

internal class BridgeCredentials(
    platform: BridgePlatform,
    newUuid: () -> UUID,
) {
    val authenticationToken: String = platform.storedString(KEY_AUTH_TOKEN)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: newUuid().toString().replace("-", "").also { token ->
            platform.storeString(KEY_AUTH_TOKEN, token)
        }
    val deviceId: String = platform.storedString(KEY_DEVICE_ID)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: newUuid().toString().also { id ->
            platform.storeString(KEY_DEVICE_ID, id)
        }

    fun isAuthorized(json: JSONObject): Boolean {
        val candidate = json.optString("authToken").trim().toByteArray(Charsets.UTF_8)
        val expected = authenticationToken.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(candidate, expected)
    }

    companion object {
        const val PREFERENCES_NAME = "dhd_companion_link"
        const val KEY_AUTH_TOKEN = "bridge_auth_token"
        const val KEY_DEVICE_ID = "device_id"
    }
}
