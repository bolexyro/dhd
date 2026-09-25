package com.phonecontrol.assistant.bridge.pairing

import com.phonecontrol.assistant.bridge.FIXTURE_DEVICE_ID
import com.phonecontrol.assistant.bridge.FIXTURE_TOKEN
import com.phonecontrol.assistant.bridge.FakeBridgePlatform
import com.phonecontrol.assistant.testing.FixedDeviceInfo
import com.phonecontrol.assistant.testing.MutableClock
import com.phonecontrol.assistant.testing.SequentialUuids
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingUdpServerTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val replySocket = DatagramSocket(0, loopback)
    private val desktopSocket = DatagramSocket(0, loopback).apply { soTimeout = 2_000 }

    @After
    fun closeSockets() {
        replySocket.close()
        desktopSocket.close()
    }

    private fun packet(json: JSONObject): DatagramPacket {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return DatagramPacket(bytes, bytes.size, desktopSocket.localAddress, desktopSocket.localPort)
    }

    private fun receive(): JSONObject {
        val buffer = ByteArray(16_384)
        val packet = DatagramPacket(buffer, buffer.size)
        desktopSocket.receive(packet)
        return JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
    }

    @Test
    fun `the approval timeout is scheduled even when the pending reply cannot be sent`() = runTest {
        val protocol = PairingProtocol<PairingReturnAddress>(
            deviceId = FIXTURE_DEVICE_ID,
            authenticationToken = FIXTURE_TOKEN,
            listeningPort = 8765,
            deviceInfo = FixedDeviceInfo(),
            clock = MutableClock(),
            newUuid = SequentialUuids(),
            lanAddresses = { listOf("192.168.1.42") },
        )
        val server = PairingUdpServer(protocol, FakeBridgePlatform(), backgroundScope, "127.0.0.1", 0)
        server.handlePacket(
            replySocket,
            packet(JSONObject().put("type", "dhd_discover_request").put("version", 1).put("requestId", "discover-1")),
        )
        val nonce = receive().getString("pairingNonce")
        val closedSocket = DatagramSocket(0, loopback).apply { close() }

        assertThrows(SocketException::class.java) {
            server.handlePacket(
                closedSocket,
                packet(
                    JSONObject()
                        .put("type", "dhd_pair_approval_request")
                        .put("version", 1)
                        .put("requestId", "pair-1")
                        .put("deviceId", FIXTURE_DEVICE_ID)
                        .put("pairingNonce", nonce),
                ),
            )
        }
        runCurrent()
        assertNotNull(protocol.pendingCompanionPairing.value)

        advanceTimeBy(PairingProtocol.PAIRING_APPROVAL_TIMEOUT_MS + 1)

        assertNull(protocol.pendingCompanionPairing.value)
    }
}
