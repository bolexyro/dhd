package com.phonecontrol.assistant.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DhdAdbClientTest {
    private val adbd = FakeAdbd()

    @After
    fun tearDown() {
        adbd.close()
    }

    @Test
    fun `one connection runs several shell commands in a row`() {
        DhdAdbClient(
            host = "127.0.0.1",
            port = adbd.port,
            sslContext = { SSLContext.getDefault() },
        ).use { client ->
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

    private class FakeAdbd : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val services: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val worker = thread(isDaemon = true) { serve() }

        private fun serve() {
            runCatching {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    check(input.readMessage().command == DhdAdbProtocol.A_CNXN)
                    output.write(DhdAdbProtocol.message(DhdAdbProtocol.A_CNXN, DhdAdbProtocol.A_VERSION, DhdAdbProtocol.A_MAXDATA, "device::"))
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
