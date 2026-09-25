package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.ScreenProtection
import com.phonecontrol.assistant.observation.ForegroundAppResult
import com.phonecontrol.assistant.observation.ObservationCaptureResult
import com.phonecontrol.assistant.execution.PhoneActionTransport
import com.phonecontrol.assistant.observation.PhoneObservationSource
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplayCapture
import com.phonecontrol.assistant.execution.TaskDisplayCloseResult
import com.phonecontrol.assistant.execution.TaskDisplayGeometry
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplaySpec
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.TaskDisplayTarget
import com.phonecontrol.assistant.execution.TransportResult
import com.phonecontrol.assistant.execution.taskDisplayReference
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.testing.CanonicalJson
import com.phonecontrol.assistant.testing.FixedDeviceInfo
import com.phonecontrol.assistant.testing.Goldens
import com.phonecontrol.assistant.testing.JvmBase64Codec
import com.phonecontrol.assistant.testing.MutableClock
import com.phonecontrol.assistant.testing.SequentialUuids
import com.phonecontrol.assistant.domain.GuardRegion
import java.io.BufferedWriter
import java.io.StringWriter
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal val LOOPBACK_PEER: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
internal val LAN_PEER: InetAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))

internal const val FIXTURE_TOKEN = "0123456789abcdef0123456789abcdef"
internal const val FIXTURE_DEVICE_ID = "7d3c6a52-4f1b-4a8e-9c2d-5b6e7f8a9b0c"
internal const val TASK_RUN_KEY = "task-run"
internal const val TASK_DISPLAY_ID = 7
internal val SCREENSHOT_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
internal val BEFORE_SCREENSHOT_BYTES = byteArrayOf(0x62, 0x65, 0x66, 0x6f, 0x72, 0x65)

internal class FakeBridgePlatform : BridgePlatform {
    val strings = mutableMapOf(
        "bridge_auth_token" to FIXTURE_TOKEN,
        "device_id" to FIXTURE_DEVICE_ID,
    )
    var apps = listOf(
        InstalledUserApp(packageName = "com.example.shop", label = "Shop"),
        InstalledUserApp(packageName = "com.example.mail", label = "Mail"),
    )
    val labels = mutableMapOf("com.example.shop" to "Shop", "com.example.mail" to "Mail")
    val fullSizePackages = mutableSetOf<String>()
    val calls = mutableListOf<String>()

    override fun storedString(key: String): String? = strings[key]

    override fun storeString(key: String, value: String) {
        strings[key] = value
    }

    override fun launchableApps(): List<InstalledUserApp> = apps

    override fun applicationLabel(packageName: String): String =
        labels[packageName] ?: throw IllegalArgumentException("Unknown package $packageName")

    override fun isFullSizeLayoutEnabled(packageName: String): Boolean = packageName in fullSizePackages

    override fun setFullSizeLayoutEnabled(packageName: String, enabled: Boolean) {
        if (enabled) fullSizePackages += packageName else fullSizePackages -= packageName
    }

    override fun startSessionService(
        request: String,
        reasoningEffort: String,
        fastMode: Boolean,
        conversationId: String?,
    ) {
        calls += "startSessionService:$request:$reasoningEffort:$fastMode:$conversationId"
    }

    override fun showAttentionNotification(reason: String, conversationId: String?) {
        calls += "showAttentionNotification:$reason:$conversationId"
    }

    override fun showCompletionNotification(message: String, conversationId: String?) {
        calls += "showCompletionNotification:$message:$conversationId"
    }

    override fun removeAttentionNotification() {
        calls += "removeAttentionNotification"
    }

    override fun reconcileServiceLifetime() {
        calls += "reconcileServiceLifetime"
    }

    override fun logWarning(tag: String, message: String, error: Throwable) {
        calls += "warn:$message"
    }

    override fun logError(tag: String, message: String, error: Throwable) {
        calls += "error:$message"
    }
}

internal data class CaptureCall(
    val expectedPackageName: String?,
    val guardRegions: List<GuardRegion>,
    val taskSessionKey: String?,
    val displayId: Int?,
    val expectedDisplayRef: String?,
)

internal class FakeObservationSource : PhoneObservationSource {
    val captures = ArrayDeque<ObservationCaptureResult>()
    var fallbackCapture: ObservationCaptureResult = ObservationCaptureResult.Failed(
        message = "No capture was scripted.",
    )
    var foreground: ForegroundAppResult = ForegroundAppResult.Failed(
        code = "FOREGROUND_UNAVAILABLE",
        message = "No foreground app was scripted.",
    )
    val captureCalls = mutableListOf<CaptureCall>()

    override suspend fun getForegroundApp(
        taskSessionKey: String?,
        displayId: Int?,
        expectedDisplayRef: String?,
    ): ForegroundAppResult = foreground

    override suspend fun capture(
        expectedPackageName: String?,
        guardRegions: List<GuardRegion>,
        taskSessionKey: String?,
        displayId: Int?,
        expectedDisplayRef: String?,
    ): ObservationCaptureResult {
        captureCalls += CaptureCall(expectedPackageName, guardRegions, taskSessionKey, displayId, expectedDisplayRef)
        return captures.removeFirstOrNull() ?: fallbackCapture
    }
}

internal class FakeTransport : PhoneActionTransport {
    val results = ArrayDeque<TransportResult>()
    val executed = mutableListOf<PhoneAction>()

    override suspend fun execute(action: PhoneAction, observation: ObservationSnapshot?): TransportResult {
        executed += action
        return results.removeFirstOrNull() ?: TransportResult.Succeeded("Executed ${action.type.name.lowercase()}.")
    }
}

internal class FakeTaskDisplayBackend : TaskDisplayBackend {
    val records = MutableStateFlow<List<TaskDisplayRecord>>(emptyList())
    override val displayRecords: StateFlow<List<TaskDisplayRecord>> = records
    val sessions = mutableMapOf<String, TaskDisplaySession>()
    var resolution: TaskDisplayResolution? = null
    var defaultResolution: TaskDisplayResolution = TaskDisplayResolution.Unavailable(
        code = "TASK_DISPLAY_UNAVAILABLE",
        message = "No task display is available.",
    )
    val claimedByRun = mutableSetOf<Pair<Int, String>>()
    var closeResult: TaskDisplayCloseResult? = null
    val resolveCalls = mutableListOf<String>()

    override suspend fun create(
        sessionKey: String,
        packageName: String,
        spec: TaskDisplaySpec,
    ): TaskDisplaySession = error("create is not scripted")

    override suspend fun current(sessionKey: String): TaskDisplaySession? = sessions[sessionKey]

    override suspend fun resolveDisplay(
        displayId: Int,
        claimForSessionKey: String?,
        expectedDisplayRef: String?,
    ): TaskDisplayResolution {
        resolveCalls += "resolveDisplay:$displayId:$claimForSessionKey:$expectedDisplayRef"
        return resolution ?: defaultResolution
    }

    override suspend fun resolveDefaultDisplay(claimForSessionKey: String?): TaskDisplayResolution {
        resolveCalls += "resolveDefaultDisplay:$claimForSessionKey"
        return resolution ?: defaultResolution
    }

    override suspend fun isDisplayClaimedByRun(displayId: Int, runSessionKey: String): Boolean =
        (displayId to runSessionKey) in claimedByRun

    override suspend fun capture(session: TaskDisplaySession): TaskDisplayCapture = error("capture is not scripted")

    override suspend fun attachLiveSurface(session: TaskDisplaySession, surface: android.view.Surface) = Unit

    override suspend fun detachLiveSurface(session: TaskDisplaySession, surface: android.view.Surface) = Unit

    override fun cancel(sessionKey: String) = Unit

    override suspend fun close(session: TaskDisplaySession) = Unit

    override suspend fun close(sessionKey: String) = Unit

    override suspend fun closeTaskDisplay(displayId: Int, expectedDisplayRef: String?): TaskDisplayCloseResult =
        closeResult ?: error("closeTaskDisplay is not scripted")
}

internal fun displayRecord(
    sessionKey: String = TASK_RUN_KEY,
    displayId: Int = TASK_DISPLAY_ID,
    packageName: String = "com.example.shop",
    status: TaskDisplayStatus = TaskDisplayStatus.RUNNING,
    createdAtEpochMs: Long = 1_749_999_000_000L,
    terminalAtEpochMs: Long? = null,
    expiresAtEpochMs: Long? = null,
    lastPurpose: String = "Checking the cart",
    error: String? = null,
): TaskDisplayRecord = TaskDisplayRecord(
    sessionKey = sessionKey,
    taskId = "task-$displayId",
    packageName = packageName,
    displayId = displayId,
    width = 720,
    height = 1560,
    densityDpi = 420,
    rotation = 0,
    status = status,
    createdAtEpochMs = createdAtEpochMs,
    terminalAtEpochMs = terminalAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    lastPurpose = lastPurpose,
    error = error,
)

internal fun displaySession(
    sessionKey: String = TASK_RUN_KEY,
    displayId: Int = TASK_DISPLAY_ID,
    packageName: String = "com.example.shop",
): TaskDisplaySession = TaskDisplaySession(
    sessionKey = sessionKey,
    taskId = "task-$displayId",
    displayId = displayId,
    geometry = TaskDisplayGeometry(720, 1560, 420, 0),
    packageName = packageName,
)

internal fun readyTarget(
    sessionKey: String = TASK_RUN_KEY,
    displayId: Int = TASK_DISPLAY_ID,
): TaskDisplayResolution.Ready = TaskDisplayResolution.Ready(
    TaskDisplayTarget(
        session = displaySession(sessionKey, displayId),
        record = displayRecord(sessionKey, displayId),
    ),
)

internal fun snapshot(
    id: String,
    packageName: String = "com.example.shop",
    activityName: String? = "com.example.shop.MainActivity",
    taskSessionKey: String? = null,
    displayId: Int = 0,
    screenProtection: ScreenProtection = ScreenProtection.VISIBLE,
): ObservationSnapshot = ObservationSnapshot(
    id = id,
    packageName = packageName,
    activityName = activityName,
    displayId = displayId,
    taskSessionKey = taskSessionKey,
    taskId = taskSessionKey?.let { "task-$displayId" },
    rotation = 0,
    width = 720,
    height = 1560,
    screenshotFingerprint = "fp-$id",
    screenProtection = screenProtection,
)

internal fun captured(snapshot: ObservationSnapshot): ObservationCaptureResult.Succeeded =
    ObservationCaptureResult.Succeeded(snapshot, SCREENSHOT_BYTES)

internal fun request(type: String, vararg fields: Pair<String, Any?>): JSONObject {
    val json = JSONObject().put("type", type).put("requestId", "req-$type")
    fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
    return json
}

internal fun actionJson(
    type: String,
    purpose: String = "Tap the cart button",
    targetDescription: String = "Cart button",
    observationId: String? = null,
    fields: Map<String, Any> = emptyMap(),
): JSONObject {
    val metadata = JSONObject()
        .put("purpose", purpose)
        .put("targetDescription", targetDescription)
    observationId?.let { metadata.put("observationId", it) }
    val json = JSONObject().put("type", type).put("metadata", metadata)
    fields.forEach { (key, value) -> json.put(key, value) }
    return json
}

internal class BridgeHarness(
    taskDisplayRequired: Boolean = false,
    fullAccess: Boolean = false,
    withBackend: Boolean = true,
    phoneAccessReady: Boolean = true,
    val platform: FakeBridgePlatform = FakeBridgePlatform(),
) {
    val observations = FakeObservationSource()
    val transport = FakeTransport()
    val backend = FakeTaskDisplayBackend()
    val clock = MutableClock()
    var allowedPackages = setOf("com.example.shop")
    var fullAccessEnabled = fullAccess
    var phoneAccessAvailable = phoneAccessReady
    private val aliases = linkedMapOf<String, String>()

    val coordinator = SessionCoordinator(
        enabledPackagesProvider = { allowedPackages },
        policyEngine = PolicyEngine(),
        transport = transport,
        fullAccessProvider = { fullAccessEnabled },
        taskDisplayRequiredProvider = { taskDisplayRequired },
        taskDisplayBackend = if (withBackend) backend else null,
        phoneAccessReadyProvider = { phoneAccessAvailable },
    )

    val server = CompanionBridgeServer(
        platform = platform,
        coordinator = coordinator,
        observationProvider = observations,
        allowedPackagesProvider = { allowedPackages },
        port = 8765,
        fullAccessProvider = { fullAccessEnabled },
        taskDisplayRequiredProvider = { taskDisplayRequired },
        taskDisplayBackend = if (withBackend) backend else null,
        base64 = JvmBase64Codec,
        clock = clock,
        deviceInfo = FixedDeviceInfo(),
        newUuid = SequentialUuids(),
        lanAddressProvider = { listOf("192.168.1.42") },
    )

    fun alias(actual: String, placeholder: String) {
        aliases[actual] = placeholder
    }

    fun startSession(
        request: String = "Buy milk",
        conversationId: String? = "conversation-1",
    ): String {
        assertTrue(coordinator.start(request, conversationId))
        return aliasCurrentSession()!!
    }

    fun enqueueSteer(text: String): String {
        val steerId = coordinator.enqueueSteer(text)!!.steerId
        alias(steerId, "{{steerId}}")
        return steerId
    }

    fun prepareTaskDisplay() {
        backend.records.value = listOf(displayRecord())
        backend.resolution = readyTarget()
        backend.sessions[TASK_RUN_KEY] = displaySession()
    }

    private fun aliasCurrentSession(): String? {
        val sessionId = when (val state = coordinator.state.value) {
            SessionState.Idle -> null
            is SessionState.Running -> state.sessionId
            is SessionState.Paused -> state.sessionId
            is SessionState.Stopped -> state.sessionId
            is SessionState.Completed -> state.sessionId
        } ?: return null
        if (sessionId !in aliases) {
            val existing = aliases.values.count { it.startsWith("{{sessionId") }
            val placeholder = if (existing == 0) "{{sessionId}}" else "{{sessionId${existing + 1}}}"
            alias(sessionId, placeholder)
            listOf(TASK_DISPLAY_ID, 8, 9).forEach { displayId ->
                alias(
                    taskDisplayReference(sessionId, displayId),
                    placeholder.removeSuffix("}}") + "DisplayRef@$displayId}}",
                )
            }
        }
        return sessionId
    }

    suspend fun send(request: JSONObject, peer: InetAddress = LOOPBACK_PEER): List<JSONObject> =
        sendLine(resolvePlaceholders(request.toString()), peer).map(::JSONObject)

    suspend fun sendLine(line: String?, peer: InetAddress = LOOPBACK_PEER): List<String> {
        val output = StringWriter()
        val writer = BufferedWriter(output)
        server.handleRequestLine(line, peer, writer)
        writer.flush()
        aliasCurrentSession()
        val text = output.toString()
        val separator = System.lineSeparator()
        assertTrue("Every response must end with a line separator", text.isEmpty() || text.endsWith(separator))
        return text.split(separator).filter(String::isNotEmpty).onEach { line ->
            assertFalse("Response lines must not contain raw newlines", line.contains('\n'))
        }
    }

    fun normalize(line: String): String = aliases.entries.fold(line) { text, (actual, placeholder) ->
        text.replace(actual, placeholder)
    }

    private fun resolvePlaceholders(text: String): String = aliases.entries.fold(text) { current, (actual, placeholder) ->
        current.replace(placeholder, actual)
    }

    suspend fun exchange(
        name: String,
        request: JSONObject,
        peer: InetAddress = LOOPBACK_PEER,
        normalizeResponse: (JSONObject) -> Unit = {},
    ): List<JSONObject> {
        val lines = sendLine(resolvePlaceholders(request.toString()), peer)
        val responses = lines.map { JSONObject(normalize(it)) }.onEach(normalizeResponse)
        val fixture = JSONObject()
            .put("peer", peerName(peer))
            .put("request", request)
            .put("response", JSONArray(responses))
        Goldens.assertMatches("fixtures/bridge/$name.json", CanonicalJson.render(fixture))
        return lines.map(::JSONObject)
    }

    suspend fun rawExchange(
        name: String,
        line: String?,
        description: JSONObject,
        peer: InetAddress = LOOPBACK_PEER,
    ): List<JSONObject> {
        val lines = sendLine(line, peer)
        val fixture = JSONObject()
            .put("peer", peerName(peer))
            .put("rawRequest", description)
            .put("response", JSONArray(lines.map { JSONObject(normalize(it)) }))
        Goldens.assertMatches("fixtures/bridge/$name.json", CanonicalJson.render(fixture))
        return lines.map(::JSONObject)
    }

    private fun peerName(peer: InetAddress): String = if (peer.isLoopbackAddress) "loopback" else "lan"
}
