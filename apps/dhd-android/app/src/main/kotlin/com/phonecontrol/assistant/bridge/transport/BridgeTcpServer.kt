package com.phonecontrol.assistant.bridge.transport

import com.phonecontrol.assistant.bridge.BRIDGE_LOG_TAG
import com.phonecontrol.assistant.bridge.BridgePlatform
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_REQUEST_CHARS
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class BridgeTcpLimits(
    val readTimeoutMs: Int = 15_000,
    val maxConnections: Int = 32,
    val bindRetryDelayMs: Long = 2_000L,
)

internal class BridgeTcpServer(
    private val port: Int,
    private val bindHost: String,
    private val scope: CoroutineScope,
    private val platform: BridgePlatform,
    private val onRequestLine: suspend (line: String?, reply: BridgeReply) -> Unit,
    private val limits: BridgeTcpLimits = BridgeTcpLimits(),
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val connections = Semaphore(limits.maxConnections)

    val boundPort: Int?
        get() = serverSocket?.localPort

    @Synchronized
    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            while (isActive) {
                val socket = bindOrNull()
                if (socket != null) acceptUntilClosed(socket)
                if (isActive) delay(limits.bindRetryDelayMs)
            }
        }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        serverSocket?.close()
        serverSocket = null
    }

    private fun bindOrNull(): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(bindHost), port), BACKLOG)
        }.also { serverSocket = it }
    } catch (error: Throwable) {
        platform.logWarning(BRIDGE_LOG_TAG, "Could not bind the phone bridge; retrying", error)
        null
    }

    private fun CoroutineScope.acceptUntilClosed(socket: ServerSocket) {
        try {
            while (isActive) {
                val client = socket.accept()
                if (!connections.tryAcquire()) {
                    launch { rejectBusy(client) }
                    continue
                }
                launch {
                    try {
                        handleClient(client)
                    } finally {
                        connections.release()
                    }
                }
            }
        } catch (error: Throwable) {
            if (!socket.isClosed) {
                platform.logError(BRIDGE_LOG_TAG, "Development bridge stopped accepting; rebinding", error)
            }
        } finally {
            socket.close()
            if (serverSocket === socket) serverSocket = null
        }
    }

    private fun rejectBusy(client: Socket) {
        client.use { socket ->
            runCatching {
                NdjsonWriter(socket.bufferedWriter()).write(
                    errorResponse(null, "The phone bridge is busy. Retry the request."),
                )
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = limits.readTimeoutMs
            val reader = InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
            val line = try {
                reader.readBoundedLine(MAX_REQUEST_CHARS + 1)
            } catch (_: SocketTimeoutException) {
                return
            }
            onRequestLine(line, NdjsonWriter(socket.bufferedWriter()))
        }
    }

    private fun Socket.bufferedWriter(): BufferedWriter =
        BufferedWriter(OutputStreamWriter(getOutputStream(), Charsets.UTF_8))

    private companion object {
        const val BACKLOG = 16
    }
}

internal fun Reader.readBoundedLine(maxChars: Int): String? {
    val line = StringBuilder()
    while (line.length < maxChars) {
        val next = read()
        if (next == -1) return if (line.isEmpty()) null else line.toString()
        if (next == '\n'.code) return line.removeSuffix("\r").toString()
        line.append(next.toChar())
    }
    return line.toString()
}
