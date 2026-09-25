package com.phonecontrol.assistant.bridge

import java.net.InetAddress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAuthorizationTest {
    private val server = BridgeHarness().server
    private val ipv6Loopback = InetAddress.getByName("::1")

    private fun withToken(token: String?): JSONObject =
        JSONObject().put("type", "status").also { json -> token?.let { json.put("authToken", it) } }

    @Test
    fun `lan peer with the paired token is authorized`() {
        assertTrue(server.isAuthorized(LAN_PEER, withToken(FIXTURE_TOKEN)))
    }

    @Test
    fun `lan peer token is trimmed before comparison`() {
        assertTrue(server.isAuthorized(LAN_PEER, withToken("  $FIXTURE_TOKEN\n")))
    }

    @Test
    fun `lan peer without a token is rejected`() {
        assertFalse(server.isAuthorized(LAN_PEER, withToken(null)))
        assertFalse(server.isAuthorized(LAN_PEER, withToken("")))
    }

    @Test
    fun `lan peer with another token is rejected`() {
        assertFalse(server.isAuthorized(LAN_PEER, withToken(FIXTURE_TOKEN.uppercase())))
        assertFalse(server.isAuthorized(LAN_PEER, withToken(FIXTURE_TOKEN.dropLast(1))))
    }

    @Test
    fun `current behavior loopback peer bypasses the token check`() {
        assertTrue(server.isAuthorized(LOOPBACK_PEER, withToken(null)))
        assertTrue(server.isAuthorized(LOOPBACK_PEER, withToken("wrong")))
        assertTrue(server.isAuthorized(ipv6Loopback, withToken(null)))
    }

    @Test
    fun `stored credentials are reused`() {
        assertEquals(FIXTURE_TOKEN, server.authenticationToken)
        assertEquals(FIXTURE_DEVICE_ID, server.deviceId)
    }

    @Test
    fun `missing or blank credentials are generated and stored`() {
        val platform = FakeBridgePlatform().apply {
            strings.clear()
            strings["bridge_auth_token"] = "   "
        }
        val generated = BridgeHarness(platform = platform).server
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
