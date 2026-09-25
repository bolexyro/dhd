package com.phonecontrol.assistant.bridge.auth

import com.phonecontrol.assistant.bridge.FIXTURE_DEVICE_ID
import com.phonecontrol.assistant.bridge.FIXTURE_TOKEN
import com.phonecontrol.assistant.bridge.FakeBridgePlatform
import com.phonecontrol.assistant.testing.SequentialUuids
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCredentialsTest {
    private val credentials = BridgeCredentials(FakeBridgePlatform(), SequentialUuids())

    private fun withToken(token: String?): JSONObject =
        JSONObject().put("type", "status").also { json -> token?.let { json.put("authToken", it) } }

    @Test
    fun `request with the paired token is authorized`() {
        assertTrue(credentials.isAuthorized(withToken(FIXTURE_TOKEN)))
    }

    @Test
    fun `request token is trimmed before comparison`() {
        assertTrue(credentials.isAuthorized(withToken("  $FIXTURE_TOKEN\n")))
    }

    @Test
    fun `request without a token is rejected`() {
        assertFalse(credentials.isAuthorized(withToken(null)))
        assertFalse(credentials.isAuthorized(withToken("")))
    }

    @Test
    fun `request with another token is rejected`() {
        assertFalse(credentials.isAuthorized(withToken(FIXTURE_TOKEN.uppercase())))
        assertFalse(credentials.isAuthorized(withToken(FIXTURE_TOKEN.dropLast(1))))
    }

    @Test
    fun `stored credentials are reused`() {
        assertEquals(FIXTURE_TOKEN, credentials.authenticationToken)
        assertEquals(FIXTURE_DEVICE_ID, credentials.deviceId)
    }

    @Test
    fun `missing or blank credentials are generated and stored`() {
        val platform = FakeBridgePlatform().apply {
            strings.clear()
            strings["bridge_auth_token"] = "   "
        }
        val generated = BridgeCredentials(platform, SequentialUuids())
        assertEquals("0000000000000dd00000000000000001", generated.authenticationToken)
        assertEquals("00000000-0000-0dd0-0000-000000000002", generated.deviceId)
        assertEquals(
            mapOf(
                "bridge_auth_token" to "0000000000000dd00000000000000001",
                "device_id" to "00000000-0000-0dd0-0000-000000000002",
            ),
            platform.strings,
        )
    }
}
