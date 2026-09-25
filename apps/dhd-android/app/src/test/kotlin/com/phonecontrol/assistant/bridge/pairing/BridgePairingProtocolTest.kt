package com.phonecontrol.assistant.bridge.pairing

import com.phonecontrol.assistant.bridge.BridgeHarness
import com.phonecontrol.assistant.bridge.FIXTURE_DEVICE_ID
import com.phonecontrol.assistant.testing.CanonicalJson
import com.phonecontrol.assistant.testing.Goldens
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BridgePairingProtocolTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val harness = BridgeHarness()
    private val serverSocket = DatagramSocket(0, loopback)
    private val desktopSocket = DatagramSocket(0, loopback).apply { soTimeout = 2_000 }
    private val otherDesktopSocket = DatagramSocket(0, loopback).apply { soTimeout = 2_000 }

    @After
    fun closeSockets() {
        serverSocket.close()
        desktopSocket.close()
        otherDesktopSocket.close()
    }

    private fun discoverRequest(requestId: String = "discover-1"): JSONObject = JSONObject()
        .put("type", "dhd_discover_request")
        .put("version", 1)
        .put("requestId", requestId)

    private fun approvalRequest(
        pairingNonce: String,
        requestId: String = "pair-1",
        desktopName: String? = "Studio Mac",
        deviceId: String = FIXTURE_DEVICE_ID,
    ): JSONObject = JSONObject()
        .put("type", "dhd_pair_approval_request")
        .put("version", 1)
        .put("requestId", requestId)
        .put("deviceId", deviceId)
        .put("pairingNonce", pairingNonce)
        .also { json -> desktopName?.let { json.put("desktopName", it) } }

    private fun deliver(request: JSONObject, from: DatagramSocket = desktopSocket) {
        deliverRaw(request.toString(), from)
    }

    private fun deliverRaw(text: String, from: DatagramSocket = desktopSocket) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        harness.server.pairingServer.handlePacket(
            serverSocket,
            DatagramPacket(bytes, bytes.size, from.localAddress, from.localPort),
        )
    }

    private fun receive(on: DatagramSocket = desktopSocket): JSONObject {
        val buffer = ByteArray(16_384)
        val packet = DatagramPacket(buffer, buffer.size)
        on.receive(packet)
        return JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
    }

    private fun assertNoResponse(on: DatagramSocket = desktopSocket) {
        on.soTimeout = 200
        try {
            val unexpected = receive(on)
            fail("Expected no pairing response but received $unexpected")
        } catch (_: SocketTimeoutException) {
        } finally {
            on.soTimeout = 2_000
        }
    }

    private fun exchange(name: String, request: JSONObject, from: DatagramSocket = desktopSocket): JSONObject {
        deliver(request, from)
        val response = receive(from)
        assertGolden(name, request, response)
        return response
    }

    private fun assertGolden(name: String, request: JSONObject?, response: JSONObject) {
        val fixture = JSONObject()
            .put("transport", "udp")
            .put("port", 8766)
            .put("request", request ?: JSONObject.NULL)
            .put("response", response)
        Goldens.assertMatches("fixtures/bridge/pairing.$name.json", CanonicalJson.render(fixture))
    }

    private fun discoverNonce(requestId: String = "discover-1"): String {
        deliver(discoverRequest(requestId))
        return receive().getString("pairingNonce")
    }

    @Test
    fun `discovery offers the device identity and a fresh nonce`() {
        val offer = exchange("dhd_discover_offer", discoverRequest())
        assertEquals("0000000000000dd00000000000000001", offer.getString("pairingNonce"))
    }

    @Test
    fun `approval request waits for the user`() {
        val nonce = discoverNonce()
        exchange("dhd_pair_approval_pending", approvalRequest(nonce))
        assertEquals(
            PendingCompanionPairing(
                requestId = "pair-1",
                deviceId = FIXTURE_DEVICE_ID,
                desktopName = "Studio Mac",
                expiresAtEpochMs = harness.clock.wall + 60_000L,
            ),
            harness.server.pendingCompanionPairing.value,
        )
    }

    @Test
    fun `approval releases the token once to the waiting desktop`() {
        val nonce = discoverNonce()
        deliver(approvalRequest(nonce))
        receive()
        assertTrue(harness.server.approvePendingCompanionPairing())
        assertGolden("dhd_pair_approval_offer", null, receive())
        assertNull(harness.server.pendingCompanionPairing.value)
        assertFalse(harness.server.approvePendingCompanionPairing())
    }

    @Test
    fun `rejection tells the desktop the phone declined`() {
        val nonce = discoverNonce()
        deliver(approvalRequest(nonce))
        receive()
        assertTrue(harness.server.rejectPendingCompanionPairing())
        assertGolden("dhd_pair_approval_rejected.declined", null, receive())
    }

    @Test
    fun `retry of the pending request is acknowledged again`() {
        val nonce = discoverNonce()
        deliver(approvalRequest(nonce))
        val first = receive()
        harness.clock.advance(5_000L)
        deliver(approvalRequest(nonce))
        assertEquals(first.toString(), receive().toString())
    }

    @Test
    fun `second desktop is refused while a request is pending`() {
        val nonce = discoverNonce()
        deliver(approvalRequest(nonce))
        receive()
        exchange(
            "dhd_pair_approval_rejected.already_pending",
            approvalRequest(nonce, requestId = "pair-2", desktopName = "Other Mac"),
            from = otherDesktopSocket,
        )
    }

    @Test
    fun `a new request is accepted once the pending one expired`() {
        val firstNonce = discoverNonce("discover-1")
        deliver(approvalRequest(firstNonce))
        receive()
        harness.clock.advance(60_000L)
        val secondNonce = discoverNonce("discover-2")
        deliver(approvalRequest(secondNonce, requestId = "pair-2"), otherDesktopSocket)
        assertEquals("dhd_pair_approval_pending", receive(otherDesktopSocket).getString("type"))
        assertEquals("pair-2", harness.server.pendingCompanionPairing.value?.requestId)
    }

    @Test
    fun `approval after the pending request expired does nothing`() {
        val nonce = discoverNonce()
        deliver(approvalRequest(nonce))
        receive()
        harness.clock.advance(60_000L)
        assertFalse(harness.server.approvePendingCompanionPairing())
        assertNull(harness.server.pendingCompanionPairing.value)
        assertNoResponse()
    }

    @Test
    fun `discovery nonce expires after ninety seconds`() {
        val nonce = discoverNonce()
        harness.clock.advance(90_000L)
        exchange("dhd_pair_approval_rejected.nonce_expired", approvalRequest(nonce))
    }

    @Test
    fun `discovery nonce is still valid just before its expiry`() {
        val nonce = discoverNonce()
        harness.clock.advance(89_999L)
        deliver(approvalRequest(nonce))
        assertEquals("dhd_pair_approval_pending", receive().getString("type"))
    }

    @Test
    fun `discovery nonce can only be used once`() {
        val nonce = discoverNonce()
        deliver(approvalRequest(nonce))
        receive()
        assertTrue(harness.server.rejectPendingCompanionPairing())
        receive()
        harness.clock.advance(10_000L)
        deliver(approvalRequest(nonce, requestId = "pair-2"))
        assertEquals("dhd_pair_approval_rejected", receive().getString("type"))
    }

    @Test
    fun `unknown nonce is rejected as expired`() {
        deliver(approvalRequest("feedfacefeedfacefeedfacefeedface"))
        assertEquals(
            "This discovery request has expired. Refresh the phone list and try again.",
            receive().getString("message"),
        )
    }

    @Test
    fun `completed response is replayed for ten seconds`() {
        val nonce = discoverNonce()
        deliver(approvalRequest(nonce))
        receive()
        assertTrue(harness.server.approvePendingCompanionPairing())
        val offer = receive()
        harness.clock.advance(9_999L)
        deliver(approvalRequest(nonce))
        assertEquals(offer.toString(), receive().toString())
        harness.clock.advance(1L)
        deliver(approvalRequest(nonce))
        assertEquals("dhd_pair_approval_rejected", receive().getString("type"))
    }

    @Test
    fun `completed response is not replayed for a different nonce`() {
        val nonce = discoverNonce()
        deliver(approvalRequest(nonce))
        receive()
        assertTrue(harness.server.approvePendingCompanionPairing())
        receive()
        deliver(approvalRequest("feedfacefeedfacefeedfacefeedface"))
        assertEquals("dhd_pair_approval_rejected", receive().getString("type"))
    }

    @Test
    fun `only the newest thirty two discovery nonces are kept`() {
        val first = discoverNonce("discover-0")
        repeat(32) { index -> discoverNonce("discover-${index + 1}") }
        deliver(approvalRequest(first))
        assertEquals("dhd_pair_approval_rejected", receive().getString("type"))
    }

    @Test
    fun `desktop name defaults and is truncated`() {
        val defaultNonce = discoverNonce("discover-1")
        deliver(approvalRequest(defaultNonce, desktopName = null))
        receive()
        assertEquals("DHD Companion", harness.server.pendingCompanionPairing.value?.desktopName)
        assertTrue(harness.server.rejectPendingCompanionPairing())
        receive()

        val longNonce = discoverNonce("discover-2")
        deliver(approvalRequest(longNonce, requestId = "pair-2", desktopName = "  ${"M".repeat(100)}  "))
        receive()
        assertEquals("M".repeat(80), harness.server.pendingCompanionPairing.value?.desktopName)
    }

    @Test
    fun `malformed or foreign packets are ignored`() {
        deliverRaw("not json")
        deliver(discoverRequest().put("version", 2))
        deliver(discoverRequest().put("requestId", " "))
        deliver(JSONObject().put("type", "dhd_unknown").put("version", 1))
        deliver(approvalRequest(pairingNonce = "abc", deviceId = "another-phone"))
        deliver(approvalRequest(pairingNonce = " "))
        deliver(approvalRequest(pairingNonce = "abc", requestId = " "))
        assertNoResponse()
        assertNull(harness.server.pendingCompanionPairing.value)
    }
}
