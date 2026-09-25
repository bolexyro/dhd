package com.phonecontrol.assistant.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DhdAdbClientTest {
    private val adbds = mutableListOf<FakeAdbd>()

    @After
    fun tearDown() {
        adbds.forEach(FakeAdbd::close)
    }

    private fun adbd(script: FakeAdbd.(DataInputStream, DataOutputStream) -> Unit): FakeAdbd =
        FakeAdbd(script).also(adbds::add)

    private fun client(port: Int) = DhdAdbClient(
        host = "127.0.0.1",
        port = port,
        sslContext = { SSLContext.getDefault() },
    )

    private fun connectFailure(port: Int): Throwable =
        assertThrows(Throwable::class.java) { client(port).use { it.connect() } }

    @Test
    fun `one connection runs several shell commands in a row`() {
        val adbd = adbd { input, output ->
            acceptConnection(input, output)
            serveShellCommands(input, output)
        }
        client(adbd.port).use { client ->
            client.connect()

            val pid = client.shellV2("pidof dhd_maintenance")
            val kill = client.shellV2("kill -TERM 42")

            assertEquals(0, pid.exitCode)
            assertEquals("out:pidof dhd_maintenance", String(pid.stdout, Charsets.UTF_8))
            assertEquals(0, kill.exitCode)
            assertEquals("out:kill -TERM 42", String(kill.stdout, Charsets.UTF_8))
        }
        assertEquals(
            listOf(
                buildDhdAdbShellV2Service("pidof dhd_maintenance"),
                buildDhdAdbShellV2Service("kill -TERM 42"),
            ),
            adbd.services.toList(),
        )
    }

    @Test
    fun `a refused connection is not an authorization rejection`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        assertFalse(connectFailure(closedPort).isAdbAuthorizationRejection())
    }

    @Test
    fun `adbd closing before the handshake is not an authorization rejection`() {
        val adbd = adbd { input, _ -> input.readMessage() }

        assertFalse(connectFailure(adbd.port).isAdbAuthorizationRejection())
    }

    @Test
    fun `adbd asking for legacy auth is an authorization rejection`() {
        val adbd = adbd { input, output ->
            input.readMessage()
            output.write(DhdAdbProtocol.message(DhdAdbProtocol.A_AUTH, 1, 0, ByteArray(20)))
            output.flush()
            input.readMessage()
        }

        val failure = connectFailure(adbd.port)
        assertTrue(failure.isAdbAuthorizationRejection())
        assertEquals("Wireless Debugging rejected DHD's ADB connection (A_AUTH).", failure.message)
    }

    @Test
    fun `a failed tls handshake is an authorization rejection`() {
        val adbd = adbd { input, output ->
            input.readMessage()
            output.write(DhdAdbProtocol.message(DhdAdbProtocol.A_STLS, DhdAdbProtocol.A_STLS_VERSION, 0))
            output.flush()
            input.readMessage()
            output.write(ByteArray(64) { 0x15 })
            output.flush()
            Thread.sleep(500)
        }

        assertTrue(connectFailure(adbd.port).isAdbAuthorizationRejection())
    }

    @Test
    fun `authorization rejections are found through wrapping causes`() {
        assertTrue(IOException("bootstrap", DhdAdbUnauthorizedException("rejected")).isAdbAuthorizationRejection())
        assertFalse(IOException("bootstrap", ConnectException("refused")).isAdbAuthorizationRejection())
    }

    private fun acceptConnection(input: DataInputStream, output: DataOutputStream) {
        check(input.readMessage().command == DhdAdbProtocol.A_CNXN)
        output.write(
            DhdAdbProtocol.message(DhdAdbProtocol.A_CNXN, DhdAdbProtocol.A_VERSION, DhdAdbProtocol.A_MAXDATA, "device::"),
        )
        output.flush()
    }

    private fun FakeAdbd.serveShellCommands(input: DataInputStream, output: DataOutputStream) {
        var remoteId = 100
        while (true) {
            val message = input.readMessage()
            if (message.command != DhdAdbProtocol.A_OPEN) continue
            val service = String(message.data, Charsets.UTF_8).trimEnd('\u0000')
            services += service
            val localId = message.arg0
            remoteId += 1
            val command = service.substringAfter("raw:")
            val stdout = DhdAdbProtocol.shellV2Packet(DhdAdbProtocol.SHELL_V2_STDOUT, "out:$command".toByteArray())
            val exit = DhdAdbProtocol.shellV2Packet(DhdAdbProtocol.SHELL_V2_EXIT, byteArrayOf(0))
            output.write(DhdAdbProtocol.message(DhdAdbProtocol.A_OKAY, remoteId, localId))
            output.write(DhdAdbProtocol.message(DhdAdbProtocol.A_WRTE, remoteId, localId, stdout + exit))
            output.write(DhdAdbProtocol.message(DhdAdbProtocol.A_CLSE, remoteId, localId))
            output.flush()
        }
    }

    private class FakeAdbd(script: FakeAdbd.(DataInputStream, DataOutputStream) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val services: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    script(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()))
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(1_000)
        }
    }

    private data class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private companion object {
        fun DataInputStream.readMessage(): Message {
            val header = ByteArray(24)
            readFully(header)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val length = buffer.int
            val data = ByteArray(length)
            readFully(data)
            return Message(command, arg0, arg1, data)
        }
    }
}
