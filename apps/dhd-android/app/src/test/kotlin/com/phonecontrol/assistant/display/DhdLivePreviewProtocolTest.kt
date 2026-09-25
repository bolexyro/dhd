package com.phonecontrol.assistant.display

import android.media.MediaCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DhdLivePreviewProtocolTest {
    @Test
    fun `writes client handshake with the daemon wire format`() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { stream ->
                DhdVirtualDisplayProtocol.writeClientHandshake(stream, "secret")
                stream.flush()
            }
        }.toByteArray()

        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertEquals(DhdVirtualDisplayProtocol.STREAM_MAGIC, input.readInt())
            assertEquals(DhdVirtualDisplayProtocol.STREAM_VERSION, input.readInt())
            assertEquals(6, input.readInt())
            val token = ByteArray(6)
            input.readFully(token)
            assertEquals("secret", String(token, Charsets.UTF_8))
        }
    }

    @Test
    fun `accepts daemon headers with absent codec configuration`() {
        val header = DhdVirtualDisplayProtocol.readStreamHeader(
            input = DataInputStream(ByteArrayInputStream(headerBytes(token = "secret", csd0Length = -1, csd1Length = -1))),
            expectedToken = "secret",
        )

        assertEquals(DhdVirtualDisplayProtocol.CODEC_AVC, header.codecMime)
        assertEquals(720, header.width)
        assertEquals(1560, header.height)
        assertNull(header.csd0)
        assertNull(header.csd1)
    }

    @Test
    fun `stream header preserves codec configuration for decoder handoff`() {
        val csd0 = byteArrayOf(0, 0, 0, 1, 103)
        val csd1 = byteArrayOf(0, 0, 0, 1, 104)
        val header = DhdVirtualDisplayProtocol.readStreamHeader(
            input = DataInputStream(
                ByteArrayInputStream(
                    headerBytes(
                        token = "secret",
                        csd0 = csd0,
                        csd1 = csd1,
                    ),
                ),
            ),
            expectedToken = "secret",
        )

        assertArrayEquals(csd0, header.csd0)
        assertArrayEquals(csd1, header.csd1)
    }

    @Test
    fun `rejects a header with the wrong stream token`() {
        assertThrows(IllegalArgumentException::class.java) {
            DhdVirtualDisplayProtocol.readStreamHeader(
                input = DataInputStream(ByteArrayInputStream(headerBytes(token = "wrong"))),
                expectedToken = "secret",
            )
        }
    }

    @Test
    fun `treats a nullable packet payload marker as end of stream`() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply {
                writeInt(0)
                writeLong(1L)
                writeInt(-1)
            }
        }

        assertNull(DhdVirtualDisplayProtocol.readPacket(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
    }

    @Test
    fun `packet reader returns packet payloads and treats clean eof as stream end`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            output.writeLong(42L)
            output.writeBytes(payload)
        }

        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
            val packet = DhdVirtualDisplayProtocol.readPacket(input)
            assertEquals(1, packet?.flags)
            assertEquals(42L, packet?.presentationTimeUs)
            assertArrayEquals(payload, packet?.data)
            assertNull(DhdVirtualDisplayProtocol.readPacket(input))
        }
    }

    @Test
    fun `reconnect delay is bounded`() {
        assertEquals(100L, DhdVirtualDisplayProtocol.reconnectDelayMs(1))
        assertEquals(200L, DhdVirtualDisplayProtocol.reconnectDelayMs(2))
        assertEquals(2_000L, DhdVirtualDisplayProtocol.reconnectDelayMs(20))
    }

    @Test
    fun `replay buffer keeps the latest keyframe and contiguous deltas`() {
        val buffer = DhdLivePreviewReplayBuffer(maxPackets = 8, maxBytes = 32)

        buffer.append(packet(flags = 0, pts = 1, value = 0))
        buffer.append(packet(flags = MediaCodec.BUFFER_FLAG_KEY_FRAME, pts = 2, value = 1))
        buffer.append(packet(flags = 0, pts = 3, value = 2))
        buffer.append(packet(flags = 0, pts = 4, value = 3))

        assertEquals(
            listOf(listOf(1), listOf(2), listOf(3)),
            buffer.snapshot().map { packet -> packet.data.map { byte -> byte.toInt() } },
        )

        buffer.append(packet(flags = MediaCodec.BUFFER_FLAG_KEY_FRAME, pts = 5, value = 4))

        assertEquals(
            listOf(listOf(4)),
            buffer.snapshot().map { packet -> packet.data.map { byte -> byte.toInt() } },
        )
    }

    @Test
    fun `replay buffer stops before packet and byte bounds and resumes at next keyframe`() {
        val buffer = DhdLivePreviewReplayBuffer(maxPackets = 8, maxBytes = 3)

        buffer.append(packet(flags = MediaCodec.BUFFER_FLAG_KEY_FRAME, pts = 1, value = 1))
        buffer.append(packet(flags = 0, pts = 2, value = 2))
        buffer.append(packet(flags = 0, pts = 3, value = 3))
        buffer.append(packet(flags = 0, pts = 4, value = 4))
        buffer.append(packet(flags = 0, pts = 5, value = 5))

        assertEquals(emptyList<DhdVirtualDisplayProtocol.Packet>(), buffer.snapshot())

        buffer.append(packet(flags = MediaCodec.BUFFER_FLAG_KEY_FRAME, pts = 6, value = 6))
        buffer.append(packet(flags = 0, pts = 7, value = 7))

        assertEquals(
            listOf(listOf(6), listOf(7)),
            buffer.snapshot().map { packet -> packet.data.map { byte -> byte.toInt() } },
        )

        val packetBoundBuffer = DhdLivePreviewReplayBuffer(maxPackets = 3, maxBytes = 32)
        packetBoundBuffer.append(
            packet(flags = MediaCodec.BUFFER_FLAG_KEY_FRAME, pts = 8, value = 8),
        )
        packetBoundBuffer.append(packet(flags = 0, pts = 9, value = 9))
        packetBoundBuffer.append(packet(flags = 0, pts = 10, value = 10))
        packetBoundBuffer.append(packet(flags = 0, pts = 11, value = 11))

        assertEquals(emptyList<DhdVirtualDisplayProtocol.Packet>(), packetBoundBuffer.snapshot())

        packetBoundBuffer.append(
            packet(flags = MediaCodec.BUFFER_FLAG_KEY_FRAME, pts = 12, value = 12),
        )
        packetBoundBuffer.append(packet(flags = 0, pts = 13, value = 13))

        assertEquals(
            listOf(listOf(12), listOf(13)),
            packetBoundBuffer.snapshot()
                .map { packet -> packet.data.map { byte -> byte.toInt() } },
        )
    }

    @Test
    fun `replay buffer does not hand an oversized keyframe to a replacement decoder`() {
        val buffer = DhdLivePreviewReplayBuffer(maxPackets = 4, maxBytes = 2)

        buffer.append(packet(flags = MediaCodec.BUFFER_FLAG_KEY_FRAME, pts = 1, value = 1))
        buffer.append(
            DhdVirtualDisplayProtocol.Packet(
                flags = MediaCodec.BUFFER_FLAG_KEY_FRAME,
                presentationTimeUs = 2,
                data = byteArrayOf(2, 3, 4),
            ),
        )

        assertEquals(emptyList<DhdVirtualDisplayProtocol.Packet>(), buffer.snapshot())
    }

    private fun packet(flags: Int, pts: Long, value: Int): DhdVirtualDisplayProtocol.Packet =
        DhdVirtualDisplayProtocol.Packet(flags, pts, byteArrayOf(value.toByte()))

    private fun headerBytes(
        token: String,
        csd0Length: Int = 0,
        csd1Length: Int = 0,
        csd0: ByteArray? = null,
        csd1: ByteArray? = null,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).apply {
            writeInt(DhdVirtualDisplayProtocol.STREAM_MAGIC)
            writeInt(DhdVirtualDisplayProtocol.STREAM_VERSION)
            writeString(token)
            writeString(DhdVirtualDisplayProtocol.CODEC_AVC)
            writeInt(720)
            writeInt(1560)
            writeBytes(csd0 ?: if (csd0Length >= 0) ByteArray(csd0Length) else null)
            writeBytes(csd1 ?: if (csd1Length >= 0) ByteArray(csd1Length) else null)
        }
    }.toByteArray()

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeBytes(bytes: ByteArray?) {
        writeInt(bytes?.size ?: -1)
        if (bytes != null) write(bytes)
    }
}
