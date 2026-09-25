package com.phonecontrol.assistant.display

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class DhdLivePreviewPhase {
    CONNECTING,
    LIVE,
    ERROR,
    CLOSED,
}

/** Playback state exposed to the app UI and task-display adapter. */
data class DhdLivePreviewState(
    val phase: DhdLivePreviewPhase,
    val message: String? = null,
    val attempt: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
) {
    companion object {
        fun connecting(attempt: Int = 0): DhdLivePreviewState =
            DhdLivePreviewState(
                phase = DhdLivePreviewPhase.CONNECTING,
                attempt = attempt,
            )

        fun live(attempt: Int, width: Int, height: Int): DhdLivePreviewState =
            DhdLivePreviewState(
                phase = DhdLivePreviewPhase.LIVE,
                attempt = attempt,
                width = width,
                height = height,
            )

        fun error(message: String, attempt: Int): DhdLivePreviewState =
            DhdLivePreviewState(
                phase = DhdLivePreviewPhase.ERROR,
                message = message,
                attempt = attempt,
            )

        fun closed(): DhdLivePreviewState =
            DhdLivePreviewState(phase = DhdLivePreviewPhase.CLOSED)
    }
}

/**
 * Owns one authenticated phone-local AVC stream for a task display. UI
 * surfaces are short-lived subscribers: replacing inline with fullscreen
 * replaces only the decoder target and replays the latest bounded GOP; it
 * does not tear down the native stream connection.
 */
class DhdLivePreviewHandle internal constructor(
    val session: DhdVirtualDisplaySession,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val socketReference = AtomicReference<Socket?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFlow = MutableStateFlow(DhdLivePreviewState.connecting())
    private val streamHeader = MutableStateFlow<DhdVirtualDisplayProtocol.StreamHeader?>(null)
    private val surfaceMutex = Mutex()
    private val packetDispatchMutex = Mutex()
    private val decoderLock = Any()
    private var activeDecoder: ActiveDecoder? = null
    private var desiredSurface: Surface? = null
    private val nextDecoderId = AtomicLong(0L)
    private val replayBuffer = DhdLivePreviewReplayBuffer()
    @Volatile
    private var connectionAttempt = 0
    private val streamJob: Job

    /** Connecting becomes LIVE only after MediaCodec reports a rendered frame. */
    val state: StateFlow<DhdLivePreviewState> = stateFlow.asStateFlow()

    init {
        streamJob = scope.launch { reconnectingStreamLoop() }
    }

    /** Attach a new UI surface without reconnecting the display stream. */
    suspend fun attachSurface(surface: Surface) = surfaceMutex.withLock {
        require(surface.isValid) { "The live preview surface is no longer valid." }
        check(!closed.get()) { "The live preview controller is closed." }
        synchronized(decoderLock) {
            desiredSurface = surface
        }
        stateFlow.value = DhdLivePreviewState.connecting(connectionAttempt)
        stopActiveDecoder()
        // Do not hold the surface lease waiting for a network header. A
        // destroyed view must be detachable even while the daemon is offline.
        streamHeader.value?.let { attachDecoder(surface, it) }
    }

    private suspend fun attachDecoder(
        surface: Surface,
        header: DhdVirtualDisplayProtocol.StreamHeader,
    ) {
        stateFlow.value = DhdLivePreviewState.connecting(connectionAttempt)
        stopActiveDecoder()
        if (closed.get()) throw CancellationException("The live preview controller is closed.")

        val channel = Channel<DhdVirtualDisplayProtocol.Packet>(
            capacity = DhdVirtualDisplayProtocol.REPLAY_WINDOW_MAX_PACKETS +
                DhdVirtualDisplayProtocol.DECODER_QUEUE_HEADROOM,
        )
        val decoderId = nextDecoderId.incrementAndGet()
        val codec = MediaCodec.createDecoderByType(header.codecMime)
        try {
            val format = MediaFormat.createVideoFormat(header.codecMime, header.width, header.height)
            header.csd0?.takeIf(ByteArray::isNotEmpty)?.let {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(it))
            }
            header.csd1?.takeIf(ByteArray::isNotEmpty)?.let {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(it))
            }
            codec.configure(format, surface, null, 0)
            codec.setOnFrameRenderedListener(
                object : MediaCodec.OnFrameRenderedListener {
                    override fun onFrameRendered(
                        codec: MediaCodec,
                        presentationTimeUs: Long,
                        nanoTime: Long,
                    ) {
                        if (isCurrentDecoder(decoderId) && !closed.get()) {
                            stateFlow.value = DhdLivePreviewState.live(
                                attempt = connectionAttempt,
                                width = header.width,
                                height = header.height,
                            )
                        }
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            codec.start()
            val decoder = ActiveDecoder(
                id = decoderId,
                surface = surface,
                channel = channel,
                codec = codec,
            )
            packetDispatchMutex.withLock {
                val replayPackets = replayBuffer.snapshot()
                synchronized(decoderLock) {
                    if (closed.get()) throw CancellationException("The live preview controller is closed.")
                    activeDecoder = decoder
                    replayPackets.forEach { packet ->
                        check(channel.trySend(packet).isSuccess) {
                            "The live preview replay window exceeded decoder queue capacity."
                        }
                    }
                    decoder.job = scope.launch { decodePackets(decoder) }
                }
            }
        } catch (error: Throwable) {
            channel.close()
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (error !is CancellationException && !closed.get()) {
                publishError(previewFailureMessage(error), connectionAttempt)
            }
            throw error
        }
    }

    /** Stop decoding into a destroyed UI surface but keep the stream alive. */
    suspend fun detachSurface(surface: Surface) = surfaceMutex.withLock {
        val matches = synchronized(decoderLock) {
            val matches = desiredSurface === surface || activeDecoder?.surface === surface
            if (desiredSurface === surface) desiredSurface = null
            matches
        }
        if (matches) stopActiveDecoder()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stateFlow.value = DhdLivePreviewState.closed()
        socketReference.getAndSet(null)?.let { socket ->
            runCatching { socket.close() }
        }
        synchronized(decoderLock) {
            activeDecoder?.channel?.close()
            activeDecoder = null
            desiredSurface = null
        }
        // Child decoder jobs own MediaCodec teardown. Cancellation wakes both
        // the packet reader and any codec input-buffer wait loops.
        streamJob.cancel()
        scope.cancel()
    }

    private suspend fun reconnectingStreamLoop() {
        var failedAttempts = 0
        while (!closed.get() && currentCoroutineContext().isActive) {
            val attempt = failedAttempts + 1
            connectionAttempt = attempt
            stateFlow.value = DhdLivePreviewState.connecting(attempt)
            streamHeader.value = null
            // A new TCP stream gets a fresh native keyframe. Do not replay a
            // GOP from the previous connection while waiting for it; that
            // GOP may represent the display before the reconnect.
            replayBuffer.clear()
            val socket = Socket()
            socketReference.set(socket)
            try {
                socket.tcpNoDelay = true
                // The stream is allowed to be quiet while the app is static.
                // Native close/EOF and the display-liveness monitor are the
                // failure signals; an idle video interval is not.
                socket.soTimeout = DhdVirtualDisplayProtocol.HEADER_READ_TIMEOUT_MS
                socket.connect(
                    InetSocketAddress("127.0.0.1", session.streamPort),
                    DhdVirtualDisplayProtocol.CONNECT_TIMEOUT_MS,
                )
                val output = DataOutputStream(socket.getOutputStream())
                DhdVirtualDisplayProtocol.writeClientHandshake(output, session.streamToken)
                output.flush()
                val input = DataInputStream(socket.getInputStream())
                val header = DhdVirtualDisplayProtocol.readStreamHeader(input, session.streamToken)
                validateHeader(header)
                // Header readiness is bounded, but an established display
                // may legitimately produce no bytes while its pixels remain
                // unchanged.
                socket.soTimeout = 0
                streamHeader.value = header
                failedAttempts = 0
                attachDesiredSurfaceIfNeeded()
                readPackets(input)
                if (closed.get()) return
                throw IOException("The live preview stream ended.")
            } catch (_: CancellationException) {
                if (closed.get()) return
                throw CancellationException("The live preview decoder was cancelled.")
            } catch (failure: Throwable) {
                if (closed.get()) return
                streamHeader.value = null
                surfaceMutex.withLock { stopActiveDecoder() }
                failedAttempts++
                // Do not leave the UI marked LIVE while the decoder is gone;
                // otherwise a reconnect can look like a healthy stale frame.
                stateFlow.value = DhdLivePreviewState.connecting(failedAttempts)
                if (failedAttempts >= DhdVirtualDisplayProtocol.MAX_CONNECTION_ATTEMPTS) {
                    publishError(previewFailureMessage(failure), failedAttempts)
                }
                delay(DhdVirtualDisplayProtocol.reconnectDelayMs(failedAttempts))
            } finally {
                socketReference.compareAndSet(socket, null)
                runCatching { socket.close() }
            }
        }
    }

    /** Recreate the decoder after a stream reconnect without UI involvement. */
    private suspend fun attachDesiredSurfaceIfNeeded() = surfaceMutex.withLock {
        val surface = synchronized(decoderLock) {
            desiredSurface?.takeUnless { activeDecoder != null }
        } ?: return@withLock
        if (!surface.isValid || closed.get()) {
            if (!surface.isValid) {
                synchronized(decoderLock) {
                    if (desiredSurface === surface) desiredSurface = null
                }
            }
            return@withLock
        }
        streamHeader.value?.let { attachDecoder(surface, it) }
    }

    private suspend fun readPackets(input: DataInputStream) {
        while (!closed.get() && currentCoroutineContext().isActive) {
            val packet = DhdVirtualDisplayProtocol.readPacket(input) ?: return
            packetDispatchMutex.withLock {
                replayBuffer.append(packet)
                val decoder = synchronized(decoderLock) { activeDecoder }
                if (decoder != null) {
                    try {
                        // Backpressure is intentional. Dropping an AVC packet
                        // can break the current GOP and leave the surface blank;
                        // if the decoder is being replaced, its closed channel
                        // simply means this packet is no longer needed.
                        decoder.channel.send(packet)
                    } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                        // Surface handoff/detach closes only the decoder
                        // channel; the authenticated stream remains connected.
                    }
                }
            }
        }
    }

    private suspend fun stopActiveDecoder() {
        val decoder = synchronized(decoderLock) {
            activeDecoder?.also {
                activeDecoder = null
                it.channel.close()
            }
        } ?: return
        decoder.job?.cancelAndJoin()
    }

    private fun isCurrentDecoder(decoderId: Long): Boolean = synchronized(decoderLock) {
        activeDecoder?.id == decoderId
    }

    private suspend fun decodePackets(decoder: ActiveDecoder) {
        try {
            while (!closed.get() && currentCoroutineContext().isActive) {
                drainDecoder(decoder.codec)
                val received = withTimeoutOrNull(DhdVirtualDisplayProtocol.DRAIN_POLL_MS) {
                    decoder.channel.receiveCatching()
                } ?: continue
                if (received.isClosed) break
                val packet = received.getOrNull() ?: break
                val inputIndex = waitForInputBuffer(decoder.codec)
                if (inputIndex < 0) break
                val inputBuffer = decoder.codec.getInputBuffer(inputIndex)
                    ?: throw IOException("The AVC decoder returned no input buffer.")
                if (packet.data.size > inputBuffer.capacity()) {
                    throw IOException("The AVC packet exceeds the decoder input buffer.")
                }
                inputBuffer.clear()
                inputBuffer.put(packet.data)
                decoder.codec.queueInputBuffer(
                    inputIndex,
                    0,
                    packet.data.size,
                    packet.presentationTimeUs,
                    packet.flags,
                )
            }

            repeat(DhdVirtualDisplayProtocol.FINAL_DRAIN_POLLS) {
                val rendered = drainDecoder(decoder.codec)
                if (rendered == 0) delay(DhdVirtualDisplayProtocol.DRAIN_POLL_MS)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!closed.get() && isCurrentDecoder(decoder.id)) {
                publishError(previewFailureMessage(error), connectionAttempt)
                // A decoder failure can happen while the stream is quiet. Wake
                // the blocking packet reader so the connection loop can create
                // a fresh codec and request a new decodable GOP automatically.
                socketReference.get()?.let { socket ->
                    runCatching { socket.close() }
                }
            }
        } finally {
            decoder.channel.close()
            synchronized(decoderLock) {
                if (activeDecoder?.id == decoder.id) activeDecoder = null
            }
            runCatching { decoder.codec.stop() }
            runCatching { decoder.codec.release() }
        }
    }

    private suspend fun waitForInputBuffer(codec: MediaCodec): Int {
        while (!closed.get() && currentCoroutineContext().isActive) {
            val index = codec.dequeueInputBuffer(DhdVirtualDisplayProtocol.CODEC_TIMEOUT_US)
            if (index >= 0) return index
            // Backpressure can require output draining before another input
            // buffer becomes available; do that here instead of timing out.
            drainDecoder(codec)
            delay(DhdVirtualDisplayProtocol.INPUT_BUFFER_RETRY_DELAY_MS)
        }
        return -1
    }

    private fun drainDecoder(
        codec: MediaCodec,
    ): Int {
        var rendered = 0
        val info = MediaCodec.BufferInfo()
        repeat(DhdVirtualDisplayProtocol.MAX_DRAIN_OUTPUTS) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0)
            when {
                outputIndex >= 0 -> {
                    codec.releaseOutputBuffer(outputIndex, true)
                    rendered++
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return rendered
            }
        }
        return rendered
    }

    private fun validateHeader(header: DhdVirtualDisplayProtocol.StreamHeader) {
        require(header.codecMime == session.codecMime) {
            "The live preview codec does not match the display session."
        }
        require(header.width == session.width && header.height == session.height) {
            "The live preview geometry does not match the display session."
        }
        require(header.width in 1..DhdVirtualDisplayProtocol.MAX_GEOMETRY) {
            "The live preview width is outside the supported range."
        }
        require(header.height in 1..DhdVirtualDisplayProtocol.MAX_GEOMETRY) {
            "The live preview height is outside the supported range."
        }
    }

    private fun publishError(message: String, attempt: Int) {
        if (!closed.get()) stateFlow.value = DhdLivePreviewState.error(message, attempt)
    }

    private fun previewFailureMessage(error: Throwable): String =
        error.message?.trim()?.takeIf(String::isNotEmpty)
            ?: "The live preview stream could not be decoded."

    private class ActiveDecoder(
        val id: Long,
        val surface: Surface,
        val channel: Channel<DhdVirtualDisplayProtocol.Packet>,
        val codec: MediaCodec,
    ) {
        @Volatile
        var job: Job? = null
    }
}

/**
 * Keeps one complete, bounded AVC replay window. If the next delta would
 * exceed a bound, the cached GOP is invalidated: retaining its old prefix
 * while the live decoder advances would let a replacement decoder combine
 * stale reference frames with newer deltas.
 */
internal class DhdLivePreviewReplayBuffer(
    private val maxPackets: Int = DhdVirtualDisplayProtocol.REPLAY_WINDOW_MAX_PACKETS,
    private val maxBytes: Int = DhdVirtualDisplayProtocol.REPLAY_WINDOW_MAX_BYTES,
) {
    private val packets = ArrayDeque<DhdVirtualDisplayProtocol.Packet>()
    private var bytes = 0
    private var acceptingDeltas = false

    @Synchronized
    fun append(packet: DhdVirtualDisplayProtocol.Packet) {
        if ((packet.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            packets.clear()
            if (packet.data.size <= maxBytes && maxPackets > 0) {
                bytes = packet.data.size
                packets.addLast(packet)
                acceptingDeltas = true
            } else {
                bytes = 0
                acceptingDeltas = false
            }
            return
        }
        if (!acceptingDeltas) return
        if (packets.size >= maxPackets || packet.data.size > maxBytes - bytes) {
            packets.clear()
            bytes = 0
            acceptingDeltas = false
            return
        }
        packets.addLast(packet)
        bytes += packet.data.size
    }

    @Synchronized
    fun snapshot(): List<DhdVirtualDisplayProtocol.Packet> = packets.toList()

    @Synchronized
    fun clear() {
        packets.clear()
        bytes = 0
        acceptingDeltas = false
    }
}

internal object DhdVirtualDisplayProtocol {
    const val COMMAND = "dhd-display"
    const val CREATE = "create"
    const val ATTACH = "attach"
    const val DETACH = "detach"
    const val CAPTURE = "capture"
    const val LIST = "list"
    const val CLOSE = "close"
    const val CLOSE_ALL = "close-all"
    const val CREATED_TYPE = "dhd_display_created"
    const val CODEC_AVC = "video/avc"
    const val STREAM_MAGIC = 0x44485631 // DHV1
    const val STREAM_VERSION = 1
    const val CONNECT_TIMEOUT_MS = 2_000
    const val HEADER_READ_TIMEOUT_MS = 10_000
    const val MAX_CONNECTION_ATTEMPTS = 3
    const val REPLAY_WINDOW_MAX_PACKETS = 180
    const val REPLAY_WINDOW_MAX_BYTES = 8 * 1024 * 1024
    const val DECODER_QUEUE_HEADROOM = 32
    const val INPUT_BUFFER_RETRY_DELAY_MS = 8L
    const val CODEC_TIMEOUT_US = 20_000L
    const val DRAIN_POLL_MS = 16L
    const val FINAL_DRAIN_POLLS = 12
    const val MAX_DRAIN_OUTPUTS = 32
    const val MAX_GEOMETRY = 4_096

    data class StreamHeader(
        val codecMime: String,
        val width: Int,
        val height: Int,
        val csd0: ByteArray?,
        val csd1: ByteArray?,
    )

    data class Packet(
        val flags: Int,
        val presentationTimeUs: Long,
        val data: ByteArray,
    )

    fun writeClientHandshake(output: DataOutputStream, token: String) {
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        require(tokenBytes.isNotEmpty() && tokenBytes.size <= 128) {
            "DHD display stream token is outside the supported range."
        }
        output.writeInt(STREAM_MAGIC)
        output.writeInt(STREAM_VERSION)
        output.writeInt(tokenBytes.size)
        output.write(tokenBytes)
    }

    fun readStreamHeader(input: DataInputStream, expectedToken: String): StreamHeader {
        require(input.readInt() == STREAM_MAGIC) { "Invalid DHD display stream magic." }
        require(input.readInt() == STREAM_VERSION) { "Unsupported DHD display stream version." }
        val token = readString(input, 128)
        require(
            MessageDigest.isEqual(
                token.toByteArray(Charsets.UTF_8),
                expectedToken.toByteArray(Charsets.UTF_8),
            ),
        ) { "DHD display stream authentication failed." }
        val codec = readString(input, 64)
        require(codec == CODEC_AVC) { "Unsupported DHD display stream codec: $codec" }
        val width = input.readInt()
        val height = input.readInt()
        require(width in 1..MAX_GEOMETRY && height in 1..MAX_GEOMETRY) {
            "Invalid DHD display stream geometry."
        }
        val csd0 = readBytes(input, 1 shl 20)
        val csd1 = readBytes(input, 1 shl 20)
        return StreamHeader(codec, width, height, csd0, csd1)
    }

    fun readPacket(input: DataInputStream): Packet? {
        val flags: Int
        try {
            flags = input.readInt()
        } catch (_: EOFException) {
            return null
        }
        val pts = input.readLong()
        val data = readBytes(input, 4 * 1024 * 1024) ?: return null
        return Packet(flags, pts, data)
    }

    private fun readString(input: DataInputStream, maxBytes: Int): String {
        val length = input.readInt()
        require(length in 0..maxBytes) { "DHD display stream string is too long." }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readBytes(input: DataInputStream, maxBytes: Int): ByteArray? {
        val length = input.readInt()
        if (length == -1) return null
        require(length in 0..maxBytes) { "DHD display stream packet is too large." }
        if (length == 0) return ByteArray(0)
        return ByteArray(length).also(input::readFully)
    }

    fun reconnectDelayMs(attempt: Int): Long =
        if (attempt >= 5) 2_000L else (100L shl (attempt - 1).coerceAtLeast(0))
}
