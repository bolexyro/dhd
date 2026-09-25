package com.phonecontrol.assistant.bridge.transport

import com.phonecontrol.assistant.bridge.BridgeHarness
import com.phonecontrol.assistant.bridge.FIXTURE_TOKEN
import com.phonecontrol.assistant.bridge.FakeBridgePlatform
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_REQUEST_CHARS
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTcpServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val platform = FakeBridgePlatform()
    private val servers = mutableListOf<BridgeTcpServer>()

    @After
    fun tearDown() {
        servers.forEach(BridgeTcpServer::stop)
        scope.cancel()
    }

    private fun server(
        port: Int = 0,
        limits: BridgeTcpLimits = BridgeTcpLimits(readTimeoutMs = 2_000, bindRetryDelayMs = 50L),
        onRequestLine: suspend (String?, BridgeReply) -> Unit = { line, reply ->
            reply.write(JSONObject().put("type", "echo").put("length", line?.length ?: -1))
        },
    ): BridgeTcpServer = BridgeTcpServer(port, LOOPBACK, scope, platform, onRequestLine, limits)
        .also(servers::add)
        .apply { start() }

    private fun BridgeTcpServer.awaitPort(): Int {
        repeat(200) {
            boundPort?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("The bridge never bound")
    }

    private fun connect(port: Int): Socket = Socket().apply {
        soTimeout = 5_000
        connect(InetSocketAddress(LOOPBACK, port), 2_000)
    }

    private fun Socket.readReply(): String? =
        BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8)).readLine()

    @Test
    fun `an endless request line is answered without waiting for a newline`() {
        val port = server().awaitPort()
        connect(port).use { socket ->
            socket.getOutputStream().write("x".repeat(MAX_REQUEST_CHARS + 100).toByteArray())
            socket.getOutputStream().flush()

            assertEquals(MAX_REQUEST_CHARS + 1, JSONObject(socket.readReply()!!).getInt("length"))
        }
    }

    @Test
    fun `a silent client is disconnected after the read timeout`() {
        val port = server(limits = BridgeTcpLimits(readTimeoutMs = 100, bindRetryDelayMs = 50L)).awaitPort()
        connect(port).use { socket ->
            assertNull(socket.readReply())
        }
    }

    @Test
    fun `connections beyond the cap are told the bridge is busy`() {
        val release = CountDownLatch(1)
        val port = server(
            limits = BridgeTcpLimits(readTimeoutMs = 5_000, maxConnections = 1, bindRetryDelayMs = 50L),
            onRequestLine = { _, reply ->
                release.await(5, TimeUnit.SECONDS)
                reply.write(JSONObject().put("type", "done"))
            },
        ).awaitPort()
        connect(port).use { first ->
            first.getOutputStream().write("{}\n".toByteArray())
            first.getOutputStream().flush()
            Thread.sleep(100)
            connect(port).use { second ->
                val busy = JSONObject(second.readReply()!!)
                assertEquals("error", busy.getString("type"))
                assertEquals("The phone bridge is busy. Retry the request.", busy.getString("message"))
            }
            release.countDown()
            assertEquals("done", JSONObject(first.readReply()!!).getString("type"))
        }
    }

    @Test
    fun `a port that is busy at start is bound once it frees up`() {
        val blocker = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        val port = blocker.localPort
        val bridge = server(port = port)
        Thread.sleep(150)
        blocker.close()

        assertEquals(port, bridge.awaitPort())
        connect(port).use { socket ->
            socket.getOutputStream().write("{}\n".toByteArray())
            socket.getOutputStream().flush()
            assertEquals("echo", JSONObject(socket.readReply()!!).getString("type"))
        }
        assertTrue(platform.calls.contains("warn:Could not bind the phone bridge; retrying"))
    }

    @Test
    fun `the companion bridge serves again after a stop and start`() {
        val port = ServerSocket(0).use { it.localPort }
        val harness = BridgeHarness(platform = platform)
        val bridge = harness.serverOnPort(port)
        try {
            bridge.start()
            bridge.stop()
            bridge.start()
            val reply = retryConnect(port) { socket ->
                socket.getOutputStream().write("""{"type":"status","requestId":"r","authToken":"$FIXTURE_TOKEN"}""".plus("\n").toByteArray())
                socket.getOutputStream().flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                reader.readLine()
                reader.readLine()
            }
            assertEquals("status", JSONObject(reply!!).getString("type"))
        } finally {
            bridge.stop()
        }
    }

    private fun <T> retryConnect(port: Int, block: (Socket) -> T): T {
        repeat(100) {
            runCatching { connect(port) }.getOrNull()?.use { return block(it) }
            Thread.sleep(20)
        }
        throw AssertionError("The bridge never accepted a connection")
    }

    @Test
    fun `bounded lines match readLine for short input`() {
        val reader = StringReader("first\r\nsecond\nlast")
        assertEquals("first", reader.readBoundedLine(10))
        assertEquals("second", reader.readBoundedLine(10))
        assertEquals("last", reader.readBoundedLine(10))
        assertNull(reader.readBoundedLine(10))
        assertEquals("abc", StringReader("abcdef\n").readBoundedLine(3))
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
