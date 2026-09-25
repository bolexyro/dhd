package com.phonecontrol.assistant.maintenance

import com.phonecontrol.assistant.developer.DhdMaintenanceWire
import com.phonecontrol.assistant.execution.PhoneProcessResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Client for the long-lived shell-UID DHD maintenance daemon. */
internal class DhdMaintenanceClient(
    private val port: Int,
    private val token: String,
    private val connectTimeoutMs: Int = 500,
    private val readTimeoutMs: Int = 20_000,
) {
    data class CompatibilityCheck(
        val compatible: Boolean,
        val detail: String,
    )

    /** Capabilities advertised by the daemon currently bound to this port. */
    data class Capabilities(
        val version: Int,
        val displayLifecycle: Boolean,
        val liveAvc: Boolean,
        val displayCapture: Boolean,
        val displayReconciliation: Boolean = false,
        val displayLogicalCanvas: Boolean = false,
    ) {
        val supportsNativeDisplay: Boolean
            get() = version >= REQUIRED_CAPABILITY_VERSION &&
                displayLifecycle && liveAvc && displayCapture && displayReconciliation &&
                displayLogicalCanvas
    }

    fun execute(
        command: List<String>,
        binaryOutput: Boolean = false,
        onStarted: (() -> Unit)? = null,
    ): PhoneProcessResult {
        require(command.isNotEmpty()) { "A maintenance command must not be empty." }
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = readTimeoutMs
            socket.connect(InetSocketAddress(LOOPBACK, port), connectTimeoutMs)
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            DhdMaintenanceWire.writeRequest(output, token, command, binaryOutput)
            output.flush()
            // The request is now on the daemon's loopback socket. This is the
            // closest boundary the app can observe before the daemon starts
            // the actual /system/bin process, and avoids showing the press
            // pulse while the client is still connecting or serializing args.
            onStarted?.invoke()
            return DhdMaintenanceWire.readResult(input)
        }
    }

    fun isReady(): Boolean = runCatching {
        execute(listOf("true")).exitCode == 0
    }.getOrDefault(false)

    fun capabilities(): Capabilities? = runCatching {
        val result = execute(listOf("dhd-capabilities"))
        if (result.exitCode != 0 || result.timedOut) return@runCatching null
        parseCapabilities(String(result.stdout, Charsets.UTF_8))
    }.getOrNull()

    /**
     * Keep the reason for a failed health check. The old Boolean-only probe
     * made a dead daemon, an unavailable socket, and a malformed capability
     * response indistinguishable in the UI and logcat.
     */
    fun checkCompatibility(): CompatibilityCheck {
        return try {
            val result = execute(listOf("dhd-capabilities"))
            if (result.timedOut) {
                CompatibilityCheck(false, "capability probe timed out")
            } else if (result.exitCode != 0) {
                CompatibilityCheck(
                    false,
                    "capability probe exited ${result.exitCode}: ${result.stderr.ifBlank { "no stderr" }}",
                )
            } else {
                val raw = String(result.stdout, Charsets.UTF_8)
                val parsed = parseCapabilities(raw)
                if (parsed == null) {
                    CompatibilityCheck(false, "capability response was malformed")
                } else if (!parsed.supportsNativeDisplay) {
                    CompatibilityCheck(false, "daemon capabilities are incompatible: ${raw.trim()}")
                } else {
                    CompatibilityCheck(true, "compatible daemon ${raw.trim()}")
                }
            }
        } catch (error: Throwable) {
            CompatibilityCheck(
                false,
                "${error::class.java.simpleName}: ${error.message ?: "no message"}",
            )
        }
    }

    fun isCompatible(): Boolean = checkCompatibility().compatible

    internal fun parseCapabilities(value: String): Capabilities? {
        val tokens = value.trim().split(Regex("\\s+"))
        val version = tokens.firstOrNull()
            ?.removePrefix("DHD-MAINTENANCE/")
            ?.toIntOrNull()
            ?: return null
        return Capabilities(
            version = version,
            displayLifecycle = "display-lifecycle=1" in tokens,
            liveAvc = "live-avc=1" in tokens,
            displayCapture = "display-capture=1" in tokens,
            displayReconciliation = "display-reconciliation=1" in tokens,
            displayLogicalCanvas = "display-logical-canvas=1" in tokens,
        )
    }

    companion object {
        const val REQUIRED_CAPABILITY_VERSION = 10
        private const val LOOPBACK = "127.0.0.1"
        private val random = SecureRandom()

        fun newToken(): String = buildString {
            repeat(32) { append("0123456789abcdef"[random.nextInt(16)]) }
        }

        fun newPort(): Int = 38_000 + random.nextInt(20_000)
    }
}
