package com.phonecontrol.assistant.developer

import com.phonecontrol.assistant.maintenance.DhdMaintenanceClient
import com.phonecontrol.assistant.maintenance.buildDhdMaintenanceStartCommand
import com.phonecontrol.assistant.testing.CanonicalJson
import com.phonecontrol.assistant.testing.Goldens
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DhdMaintenanceProtocolTest {
    private fun requestBytes(token: String, command: List<String>, binaryOutput: Boolean): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { DhdMaintenanceProtocol.writeRequest(it, token, command, binaryOutput) }
        }.toByteArray()

    private fun responseBytes(exitCode: Int, timedOut: Boolean, stdout: ByteArray?, stderr: ByteArray?): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { DhdMaintenanceProtocol.writeResponse(it, exitCode, timedOut, stdout, stderr) }
        }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun dataInput(block: DataOutputStream.() -> Unit): DataInputStream =
        DataInputStream(ByteArrayInputStream(ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()))

    @Test
    fun `request and response bytes are pinned`() {
        val request = requestBytes("tok", listOf("input", "tap", "10", "20"), binaryOutput = false)
        val response = responseBytes(0, false, "ok".toByteArray(), "".toByteArray())
        val fixture = JSONObject()
            .put("magic", "0x44484431")
            .put("version", 1)
            .put(
                "request",
                JSONObject()
                    .put("token", "tok")
                    .put("command", JSONArray(listOf("input", "tap", "10", "20")))
                    .put("binaryOutput", false)
                    .put("hex", request.hex()),
            )
            .put(
                "response",
                JSONObject()
                    .put("exitCode", 0)
                    .put("timedOut", false)
                    .put("stdout", "ok")
                    .put("stderr", "")
                    .put("hex", response.hex()),
            )
        Goldens.assertMatches("fixtures/maintenance/request_response.json", CanonicalJson.render(fixture))
        assertEquals(
            "44484431" + "00000001" + "00000003" + "746f6b" + "00000004" +
                "00000005" + "696e707574" + "00000003" + "746170" + "00000002" + "3130" + "00000002" + "3230" + "00",
            request.hex(),
        )
        assertEquals(
            "44484431" + "00000001" + "00000000" + "00" + "00000002" + "6f6b" + "00000000",
            response.hex(),
        )
    }

    @Test
    fun `requests and responses round trip`() {
        val request = DhdMaintenanceProtocol.readRequest(
            DataInputStream(ByteArrayInputStream(requestBytes("töken", listOf("dhd-display", "capture", "run-1"), true))),
        )
        assertEquals("töken", request.token)
        assertEquals(listOf("dhd-display", "capture", "run-1"), request.command)
        assertTrue(request.binaryOutput)

        val response = DhdMaintenanceProtocol.readResponse(
            DataInputStream(ByteArrayInputStream(responseBytes(-1, true, null, "late".toByteArray()))),
        )
        assertEquals(-1, response.exitCode)
        assertTrue(response.timedOut)
        assertArrayEquals(ByteArray(0), response.stdout)
        assertEquals("late", String(response.stderr))
    }

    @Test
    fun `request limits are enforced on write`() {
        requestBytes("t".repeat(128), List(32) { "a".repeat(4096) }, false)
        val cases = listOf(
            Triple("t".repeat(129), listOf("true"), "Maintenance protocol value is too long."),
            Triple("token", emptyList(), "Invalid maintenance command length."),
            Triple("token", List(33) { "true" }, "Invalid maintenance command length."),
            Triple("token", listOf("a".repeat(4097)), "Maintenance protocol value is too long."),
            Triple("token", listOf("é".repeat(2049)), "Maintenance protocol value is too long."),
        )
        cases.forEach { (token, command, message) ->
            assertEquals(message, assertThrows(IOException::class.java) { requestBytes(token, command, false) }.message)
        }
        assertEquals(
            "Maintenance protocol values must not be null.",
            assertThrows(IOException::class.java) { requestBytes("token", listOf("true", null) as List<String>, false) }.message,
        )
    }

    @Test
    fun `malformed frames are rejected on read`() {
        fun readError(block: DataOutputStream.() -> Unit): String? =
            assertThrows(IOException::class.java) { DhdMaintenanceProtocol.readRequest(dataInput(block)) }.message
        assertEquals("Invalid maintenance protocol magic.", readError { writeInt(0x44484432); writeInt(1) })
        assertEquals("Unsupported maintenance protocol version.", readError { writeInt(0x44484431); writeInt(2) })
        assertEquals("Maintenance protocol value is too long.", readError { writeInt(0x44484431); writeInt(1); writeInt(129) })
        assertEquals("Maintenance protocol value is too long.", readError { writeInt(0x44484431); writeInt(1); writeInt(-1) })
        assertEquals(
            "Invalid maintenance command length.",
            readError { writeInt(0x44484431); writeInt(1); writeInt(0); writeInt(0) },
        )
        assertEquals(
            "Invalid maintenance command length.",
            readError { writeInt(0x44484431); writeInt(1); writeInt(0); writeInt(33) },
        )
        assertEquals(
            "Maintenance command output is too large.",
            assertThrows(IOException::class.java) {
                DhdMaintenanceProtocol.readResponse(
                    dataInput { writeInt(0x44484431); writeInt(1); writeInt(0); writeBoolean(false); writeInt(16 * 1024 * 1024 + 1) },
                )
            }.message,
        )
    }

    @Test
    fun `tokens are compared exactly`() {
        assertTrue(DhdMaintenanceProtocol.tokensEqual("abc", "abc"))
        assertFalse(DhdMaintenanceProtocol.tokensEqual("abc", "abd"))
        assertFalse(DhdMaintenanceProtocol.tokensEqual("abc", null))
        assertFalse(DhdMaintenanceProtocol.tokensEqual(null, null))
    }

    @Test
    fun `daemon advertises its capability string and rejects unknown executables`() {
        val service = DhdNativeDisplayService()
        val capabilities = DhdMaintenanceDaemon.execute(listOf("dhd-capabilities"), false, service)
        assertEquals(0, capabilities.exitCode)
        assertEquals(
            "DHD-MAINTENANCE/10 display-lifecycle=1 live-avc=1 display-capture=1 display-density-override=1 display-reconciliation=1 display-logical-canvas=1",
            String(capabilities.stdout),
        )
        assertEquals(DhdMaintenanceDaemon.CAPABILITIES, String(capabilities.stdout))

        val rejected = DhdMaintenanceDaemon.execute(listOf("sh", "-c", "id"), false, service)
        assertEquals(DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE, rejected.exitCode)
        assertEquals("DHD maintenance rejected executable: sh", rejected.stderr)
        assertEquals(
            "DHD maintenance rejected an empty command.",
            DhdMaintenanceDaemon.execute(emptyList(), false, service).stderr,
        )
        assertEquals(
            "DHD display operation is unsupported: resize",
            DhdMaintenanceDaemon.execute(listOf("dhd-display", "resize"), false, service).stderr,
        )
    }

    @Test
    fun `client and daemon round trip over loopback`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val daemon = thread {
            repeat(3) {
                server.accept().use { socket ->
                    DhdMaintenanceDaemon.handleClient(socket, "secret", DhdNativeDisplayService())
                }
            }
        }
        try {
            val client = DhdMaintenanceClient(server.localPort, "secret")
            assertEquals(
                DhdMaintenanceClient.CompatibilityCheck(
                    compatible = true,
                    detail = "compatible daemon ${DhdMaintenanceDaemon.CAPABILITIES}",
                ),
                client.checkCompatibility(),
            )
            val rejected = client.execute(listOf("sh"))
            assertNull(rejected.exitCode)
            assertEquals("DHD maintenance rejected executable: sh", rejected.stderr)

            val wrongToken = DhdMaintenanceClient(server.localPort, "wrong").execute(listOf("dhd-capabilities"))
            assertNull(wrongToken.exitCode)
            assertEquals("DHD maintenance authentication failed.", wrongToken.stderr)
            assertEquals(0, wrongToken.stdout.size)
        } finally {
            daemon.join(5_000)
            server.close()
        }
    }

    @Test
    fun `capability parsing requires every native display feature`() {
        val client = DhdMaintenanceClient(port = 40_000, token = "unused")
        val current = client.parseCapabilities("  ${DhdMaintenanceDaemon.CAPABILITIES}\n")!!
        assertEquals(
            DhdMaintenanceClient.Capabilities(
                version = 10,
                displayLifecycle = true,
                liveAvc = true,
                displayCapture = true,
                displayReconciliation = true,
                displayLogicalCanvas = true,
            ),
            current,
        )
        assertTrue(current.supportsNativeDisplay)
        assertFalse(
            client.parseCapabilities(
                "DHD-MAINTENANCE/9 display-lifecycle=1 live-avc=1 display-capture=1 display-reconciliation=1 display-logical-canvas=1",
            )!!.supportsNativeDisplay,
        )
        assertFalse(
            client.parseCapabilities(
                "DHD-MAINTENANCE/10 display-lifecycle=1 live-avc=1 display-capture=1 display-reconciliation=1",
            )!!.supportsNativeDisplay,
        )
        assertTrue(
            client.parseCapabilities(
                "DHD-MAINTENANCE/11 display-logical-canvas=1 display-reconciliation=1 display-capture=1 live-avc=1 display-lifecycle=1",
            )!!.supportsNativeDisplay,
        )
        assertNull(client.parseCapabilities("DHD-MAINTENANCE/x display-lifecycle=1"))
        assertNull(client.parseCapabilities(""))
        assertEquals(7, client.parseCapabilities("7")!!.version)
        assertEquals(10, DhdMaintenanceClient.REQUIRED_CAPABILITY_VERSION)
    }

    @Test
    fun `start command launches the daemon class through app_process`() {
        assertEquals(
            "(CLASSPATH='/data/app/com.dhd.assistant/base.apk' /system/bin/setsid /system/bin/app_process /system/bin " +
                "--nice-name='dhd_maintenance' 'com.phonecontrol.assistant.developer.DhdMaintenanceDaemon' " +
                "'--port=38123' '--token=abc123') </dev/null >'/sdcard/Android/data/com.dhd.assistant/files/dhd-maintenance.log' 2>&1 &",
            buildDhdMaintenanceStartCommand(
                apkPath = "/data/app/com.dhd.assistant/base.apk",
                port = 38_123,
                token = "abc123",
                logPath = "/sdcard/Android/data/com.dhd.assistant/files/dhd-maintenance.log",
            ),
        )
        assertTrue(buildDhdMaintenanceStartCommand("/a b/it's.apk", 1024, "t", "/log").startsWith("(CLASSPATH='/a b/it'\"'\"'s.apk' "))
        assertEquals(
            "Maintenance port is invalid.",
            assertThrows(IllegalArgumentException::class.java) { buildDhdMaintenanceStartCommand("/a.apk", 1023, "t", "/log") }.message,
        )
        assertThrows(IllegalArgumentException::class.java) { buildDhdMaintenanceStartCommand("/a.apk", 65_536, "t", "/log") }
    }
}
