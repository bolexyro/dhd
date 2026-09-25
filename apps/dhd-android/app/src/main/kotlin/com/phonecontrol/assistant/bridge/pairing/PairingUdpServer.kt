package com.phonecontrol.assistant.bridge.pairing

import com.phonecontrol.assistant.bridge.BridgePlatform
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_REQUEST_CHARS
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class PairingReturnAddress(
    val socket: DatagramSocket,
    val address: InetAddress,
    val port: Int,
)

internal class PairingUdpServer(
    private val protocol: PairingProtocol<PairingReturnAddress>,
    private val platform: BridgePlatform,
    private val scope: CoroutineScope,
    private val bindHost: String,
    private val port: Int,
    private val tag: String,
) {
    @Volatile private var pairingSocket: DatagramSocket? = null

    fun run() {
        try {
            val socket = DatagramSocket(port, InetAddress.getByName(bindHost))
            pairingSocket = socket
            val buffer = ByteArray(MAX_REQUEST_CHARS)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                runCatching { handlePacket(socket, packet) }
                    .onFailure { error ->
                        if (!socket.isClosed) {
                            platform.logWarning(tag, "Could not handle a pairing discovery packet", error)
                        }
                    }
            }
        } catch (_: SocketException) {
            // Closing the pairing socket is the normal shutdown path.
        } catch (error: Throwable) {
            platform.logWarning(tag, "Pairing discovery stopped", error)
        } finally {
            pairingSocket = null
        }
    }

    fun handlePacket(socket: DatagramSocket, packet: DatagramPacket) {
        val payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val peer = PairingReturnAddress(socket, packet.address, packet.port)
        when (val step = protocol.receive(payload, peer)) {
            PairingStep.Ignored -> Unit
            is PairingStep.Reply -> send(peer, step.response)
            is PairingStep.AwaitingApproval -> {
                send(peer, step.response)
                scope.launch {
                    delay(PairingProtocol.PAIRING_APPROVAL_TIMEOUT_MS)
                    protocol.expireApproval(step.requestId)
                }
            }
        }
    }

    fun respond(approved: Boolean): Boolean {
        val delivery = protocol.respond(approved) ?: return false
        // The approval callback is invoked by Compose on the main thread;
        // keep the UDP write on the bridge's IO scope so Android never blocks
        // or rejects it as network work on the UI thread.
        scope.launch {
            runCatching {
                send(delivery.peer, delivery.response)
            }.onFailure { error ->
                platform.logWarning(tag, "Could not send the companion pairing response", error)
            }
        }
        return true
    }

    fun stop() {
        pairingSocket?.close()
        pairingSocket = null
        protocol.clear()
    }

    private fun send(peer: PairingReturnAddress, response: JSONObject) {
        val bytes = response.toString().toByteArray(Charsets.UTF_8)
        peer.socket.send(DatagramPacket(bytes, bytes.size, peer.address, peer.port))
    }
}
