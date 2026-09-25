package com.phonecontrol.assistant.bridge.transport

import com.phonecontrol.assistant.bridge.BRIDGE_LOG_TAG
import com.phonecontrol.assistant.bridge.BridgePlatform
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class BridgeTcpServer(
    private val port: Int,
    private val bindHost: String,
    private val scope: CoroutineScope,
    private val platform: BridgePlatform,
    private val onRequestLine: suspend (line: String?, reply: BridgeReply) -> Unit,
) {
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        scope.launch {
            try {
                val socket = ServerSocket(
                    port,
                    16,
                    InetAddress.getByName(bindHost),
                )
                serverSocket = socket
                while (isActive) {
                    val client = socket.accept()
                    launch { handleClient(client) }
                }
            } catch (_: java.net.SocketException) {
                // Closing the server socket is the normal shutdown path.
            } catch (error: Throwable) {
                platform.logError(BRIDGE_LOG_TAG, "Development bridge stopped", error)
            }
        }
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            onRequestLine(reader.readLine(), NdjsonWriter(writer))
        }
    }
}
