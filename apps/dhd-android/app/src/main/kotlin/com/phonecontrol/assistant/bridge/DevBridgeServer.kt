package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.auth.BridgeCredentials
import com.phonecontrol.assistant.bridge.handlers.AppCatalogHandlers
import com.phonecontrol.assistant.bridge.handlers.DisplayHandlers
import com.phonecontrol.assistant.bridge.handlers.ExecuteActionHandler
import com.phonecontrol.assistant.bridge.handlers.ExecuteSequenceHandler
import com.phonecontrol.assistant.bridge.handlers.ObservationHandlers
import com.phonecontrol.assistant.bridge.handlers.SessionHandlers
import com.phonecontrol.assistant.bridge.handlers.SteerHandlers
import com.phonecontrol.assistant.bridge.pairing.PairingProtocol
import com.phonecontrol.assistant.bridge.pairing.PairingReturnAddress
import com.phonecontrol.assistant.bridge.pairing.PairingUdpServer
import com.phonecontrol.assistant.bridge.pairing.PendingCompanionPairing
import com.phonecontrol.assistant.bridge.presence.CompanionPresence
import com.phonecontrol.assistant.bridge.protocol.ActionParser.optionalDisplayRef
import com.phonecontrol.assistant.bridge.protocol.ActionParser.parseGuardRegions
import com.phonecontrol.assistant.bridge.protocol.BridgeJson
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_REQUEST_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_TEXT_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.OPEN_SETTLE_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.PACKAGE_PATTERN
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.POST_ACTION_SETTLE_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.protocol.isSuccessful
import com.phonecontrol.assistant.bridge.protocol.resultMessage
import com.phonecontrol.assistant.bridge.routing.PhoneActionLock
import com.phonecontrol.assistant.bridge.routing.ToolCallScope
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.bridge.transport.NdjsonWriter
import com.phonecontrol.assistant.core.AndroidBase64Codec
import com.phonecontrol.assistant.core.Base64Codec
import com.phonecontrol.assistant.core.BuildDeviceInfo
import com.phonecontrol.assistant.core.Clock
import com.phonecontrol.assistant.core.DeviceInfo
import com.phonecontrol.assistant.core.SystemClockClock
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.core.conversationIdOrNull
import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.session.AttentionResolution
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.observation.ObservationCaptureResult
import com.phonecontrol.assistant.observation.PhoneObservationSource
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject

/**
 * Authenticated LAN NDJSON bridge used by the development desktop companion.
 *
 * The desktop may still reach this socket through `adb forward` for local
 * development, but the normal path is a same-Wi-Fi connection to the phone's
 * LAN address. It accepts the typed development requests used by the local
 * Codex MCP adapter and runs the phone-owned policy/observation/transport
 * path. The bearer token is a development pairing boundary, not a substitute
 * for TLS or a production network protocol.
 */
class DevBridgeServer internal constructor(
    private val platform: BridgePlatform,
    private val coordinator: SessionCoordinator,
    private val observationProvider: PhoneObservationSource,
    private val allowedPackagesProvider: () -> Set<String>,
    private val port: Int,
    private val fullAccessProvider: () -> Boolean,
    /** Production DHD keeps every model observation/action on a task display. */
    private val taskDisplayRequiredProvider: () -> Boolean,
    private val taskDisplayBackend: TaskDisplayBackend?,
    private val base64: Base64Codec,
    private val clock: Clock,
    private val deviceInfo: DeviceInfo,
    private val newUuid: () -> UUID,
    private val lanAddressProvider: () -> List<String>,
) {
    internal constructor(
        platform: BridgePlatform,
        coordinator: SessionCoordinator,
        observationProvider: PhoneObservationSource,
        allowedPackagesProvider: () -> Set<String>,
        port: Int = DEFAULT_PORT,
        fullAccessProvider: () -> Boolean = { false },
        taskDisplayRequiredProvider: () -> Boolean = { false },
        taskDisplayBackend: TaskDisplayBackend? = null,
    ) : this(
        platform = platform,
        coordinator = coordinator,
        observationProvider = observationProvider,
        allowedPackagesProvider = allowedPackagesProvider,
        port = port,
        fullAccessProvider = fullAccessProvider,
        taskDisplayRequiredProvider = taskDisplayRequiredProvider,
        taskDisplayBackend = taskDisplayBackend,
        base64 = AndroidBase64Codec,
        clock = SystemClockClock,
        deviceInfo = BuildDeviceInfo,
        newUuid = UUID::randomUUID,
        lanAddressProvider = ::systemLanIpv4Addresses,
    )

    private val credentials = BridgeCredentials(platform, newUuid)
    val authenticationToken: String
        get() = credentials.authenticationToken
    val deviceId: String
        get() = credentials.deviceId
    val listeningPort: Int = port
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var started = false
    private val presence = CompanionPresence(clock)
    private val pairing = PairingProtocol<PairingReturnAddress>(
        deviceId = deviceId,
        authenticationToken = authenticationToken,
        listeningPort = listeningPort,
        deviceInfo = deviceInfo,
        clock = clock,
        newUuid = newUuid,
        lanAddresses = lanAddressProvider,
    )
    internal val pairingServer = PairingUdpServer(
        protocol = pairing,
        platform = platform,
        scope = scope,
        bindHost = LAN_BIND_HOST,
        port = PAIRING_DISCOVERY_PORT,
        tag = BRIDGE_LOG_TAG,
    )
    private val phoneActionLock = PhoneActionLock()
    private val toolCalls = ToolCallScope(coordinator, platform)
    private val appCatalog = AppCatalogHandlers(coordinator, platform, allowedPackagesProvider, fullAccessProvider)
    private val steers = SteerHandlers(coordinator, presence)
    private val sessions = SessionHandlers(coordinator, platform, presence)
    private val bridgeJson = BridgeJson(base64)
    private val captures = CaptureService(coordinator, observationProvider, taskDisplayRequiredProvider)
    private val displayTargets = DisplayTargetResolver(taskDisplayBackend, coordinator, taskDisplayRequiredProvider, clock, platform)
    private val displays = DisplayHandlers(taskDisplayBackend, coordinator, displayTargets, platform)
    private val observations = ObservationHandlers(
        coordinator,
        observationProvider,
        taskDisplayRequiredProvider,
        displayTargets,
        captures,
        bridgeJson,
    )
    private val executeActions = ExecuteActionHandler(
        coordinator,
        taskDisplayBackend,
        taskDisplayRequiredProvider,
        displayTargets,
        captures,
        bridgeJson,
        base64,
        toolCalls,
    )
    private val executeSequences = ExecuteSequenceHandler(
        coordinator,
        taskDisplayRequiredProvider,
        displayTargets,
        captures,
        bridgeJson,
    )

    val companionConnected: StateFlow<Boolean>
        get() = presence.companionConnected

    val pendingCompanionPairing: StateFlow<PendingCompanionPairing?>
        get() = pairing.pendingCompanionPairing

    /** Approve the pending desktop request and release the LAN auth token once. */
    fun approvePendingCompanionPairing(): Boolean = pairingServer.respond(approved = true)

    /** Reject the pending desktop request without revealing the LAN auth token. */
    fun rejectPendingCompanionPairing(): Boolean = pairingServer.respond(approved = false)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                val socket = ServerSocket(
                    port,
                    16,
                    InetAddress.getByName(LAN_BIND_HOST),
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
        scope.launch { pairingServer.run() }
        scope.launch { presence.monitor() }
    }

    fun stop() {
        started = false
        serverSocket?.close()
        serverSocket = null
        pairingServer.stop()
        presence.release()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * Record that the DHD UI became visible. The desktop companion consumes
     * this one-shot bit on its next pending-request poll and prewarms Codex in
     * the background, without making the Android app wait for the desktop.
     */
    fun requestCodexWarmup() {
        presence.requestCodexWarmup()
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            handleRequestLine(reader.readLine(), socket.inetAddress, NdjsonWriter(writer))
        }
    }

    internal suspend fun handleRequestLine(
        line: String?,
        peerAddress: InetAddress,
        writer: BufferedWriter,
    ) {
        handleRequestLine(line, peerAddress, NdjsonWriter(writer))
    }

    private suspend fun handleRequestLine(
        line: String?,
        peerAddress: InetAddress,
        reply: BridgeReply,
    ) {
        if (line == null) {
            reply.write(errorResponse(null, "The bridge received an empty request."))
            return
        }
        if (line.length > MAX_REQUEST_CHARS) {
            reply.write(errorResponse(null, "The bridge request is too large."))
            return
        }
        val json = try {
            JSONObject(line)
        } catch (error: IllegalArgumentException) {
            reply.write(errorResponse(null, error.message ?: "Invalid bridge request."))
            return
        } catch (error: JSONException) {
            reply.write(errorResponse(null, "The bridge request must be valid JSON."))
            return
        }
        val requestId = json.optString("requestId").ifBlank { newUuid().toString() }

        if (!credentials.isAuthorized(peerAddress, json)) {
            reply.write(
                errorResponse(requestId, "The phone bridge rejected this network connection. Pair the desktop companion in DHD settings.")
                    .put("code", BridgeErrorCodes.AUTH_REQUIRED),
            )
            return
        }

        // Dashboard status checks are read-only health probes and must not
        // keep the worker's liveness lease alive after the worker stops.
        // Worker traffic still refreshes presence independently of the
        // current task or Codex polling phase.
        val requestType = json.optString("type")
        if (requestType != "status" && requestType != "companion_disconnected") {
            presence.markSeen()
        }
        reply.write(
            JSONObject()
                .put("type", "accepted")
                .put("requestId", requestId)
                .put("message", "${requestType.ifBlank { "bridge" }} accepted by the phone."),
        )
        try {
            when (requestType) {
                "demo_run" -> phoneActionLock.withLock { runDemo(parseRequest(json), reply) }
                "start_session" -> sessions.startSession(requestId, json, reply)
                "status" -> sessions.status(requestId, reply)
                "heartbeat" -> sessions.heartbeat(requestId, reply)
                "companion_disconnected" -> sessions.companionDisconnected(requestId, reply)
                "pending_request" -> sessions.pendingRequest(requestId, reply)
                "claim_request" -> sessions.claimRequest(requestId, json, reply)
                "pending_steer" -> steers.pendingSteer(requestId, json, reply)
                "claim_steer" -> steers.claimSteer(requestId, json, reply)
                "release_steer" -> steers.releaseSteer(requestId, json, reply)
                "complete_steer" -> steers.completeSteer(requestId, json, reply)
                "bind_codex_thread" -> sessions.bindCodexThread(requestId, json, reply)
                "release_request" -> sessions.releaseRequest(requestId, json, reply)
                "stream_agent_message" -> sessions.streamAgentMessage(requestId, json, reply)
                "complete_session" -> sessions.completeSession(requestId, json, reply)
                "fail_session" -> sessions.failSession(requestId, json, reply)
                "allowed_apps" -> toolCalls.withDhdTool(json, ToolNames.LIST_ALLOWED_APPS) {
                    appCatalog.allowedApps(requestId, json, reply)
                }
                "browse_apps" -> toolCalls.withDhdTool(json, ToolNames.BROWSE_APP) {
                    appCatalog.browseApps(requestId, json, reply)
                }
                "set_app_display_layout" -> toolCalls.withDhdTool(json, ToolNames.SET_APP_DISPLAY_LAYOUT) {
                    appCatalog.setAppDisplayLayout(requestId, json, reply)
                }
                "list_displays" -> displays.listDisplays(requestId, reply)
                "close_display" -> displays.closeDisplay(requestId, json, reply)
                "foreground_app" -> toolCalls.withDhdTool(json, ToolNames.FOREGROUND_APP) {
                    observations.foregroundApp(requestId, json, reply)
                }
                "observe" -> toolCalls.withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.OBSERVE,
                ) {
                    observations.observe(requestId, json, reply)
                }
                "execute_action" -> toolCalls.withDhdTool(
                    json = json,
                    fallbackToolName = toolCalls.fallbackActionToolName(json),
                ) {
                    phoneActionLock.withLock { executeActions.executeAction(requestId, json, reply) }
                }
                "execute_sequence" -> toolCalls.withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.EXECUTE_SEQUENCE,
                ) {
                    phoneActionLock.withLock { executeSequences.executeSequence(requestId, json, reply) }
                }
                "request_attention" -> toolCalls.withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.REQUEST_ATTENTION,
                    terminalStatus = DhdToolCallStatus.ATTENTION,
                ) {
                    requestAttention(requestId, json, reply)
                }
                "stop_session" -> sessions.stopSession(requestId, json, reply)
                else -> reply.write(errorResponse(requestId, "Unsupported bridge request type."))
            }
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            platform.logError(BRIDGE_LOG_TAG, "Bridge request failed", error)
            reply.write(errorResponse(requestId, "The phone bridge failed: $message"))
        }
    }

    private suspend fun requestAttention(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val reason = json.optString("reason")
            .trim()
            .ifBlank { "The phone assistant needs your attention." }
            .take(MAX_TEXT_CHARS)
        val sessionId = coordinator.activeSessionId()
        if (sessionId == null) {
            reply.write(
                errorResponse(requestId, "The phone assistant has no active session to interrupt.")
                    .put("code", BridgeErrorCodes.SESSION_NOT_RUNNING),
            )
            return
        }
        val requestedDisplayRef = optionalDisplayRef(json)
        val target = if (taskDisplayRequiredProvider() || requestedDisplayRef != null) {
            when (val resolution = displayTargets.resolve(displayRef = requestedDisplayRef)) {
                is TaskDisplayResolution.Ready -> resolution.target
                is TaskDisplayResolution.Unavailable -> {
                    reply.write(
                        errorResponse(requestId, resolution.message).put("code", resolution.code),
                    )
                    return
                }
            }
        } else {
            null
        }
        val attention = coordinator.requestAttentionWaiter(reason)
        if (attention == null) {
            reply.write(
                errorResponse(requestId, "The phone assistant is already waiting for the user's attention.")
                    .put("code", BridgeErrorCodes.ATTENTION_ALREADY_PENDING),
            )
            return
        }
        platform.showAttentionNotification(reason, coordinator.state.value.conversationIdOrNull)
        when (attention.await()) {
            AttentionResolution.Cancelled -> reply.write(
                JSONObject()
                    .put("type", "attention_cancelled")
                    .put("requestId", requestId)
                    .put("ok", false)
                    .put("sessionId", sessionId)
                    .put("code", BridgeErrorCodes.SESSION_STOPPED)
                    .put("message", "The attention step was cancelled because the phone session stopped."),
            )

            AttentionResolution.Acknowledged -> {
                platform.removeAttentionNotification()
                val response = JSONObject()
                    .put("type", "attention_resolved")
                    .put("requestId", requestId)
                    .put("ok", true)
                    .put("sessionId", sessionId)
                    .put("acknowledged", true)
                    .put("message", "The user confirmed that the attention step is complete. Observe the phone before taking the next action.")
                when (val captured = captures.captureWithRetry(
                    expectedPackageName = null,
                    guardRegions = emptyList(),
                    taskSessionKey = target?.session?.sessionKey ?: sessionId,
                    displayId = target?.session?.displayId,
                    expectedDisplayRef = target?.displayRef,
                )) {
                    is ObservationCaptureResult.Failed -> response
                        .put("observationError", captured.message)
                        .put("observationErrorCode", captured.code)
                    is ObservationCaptureResult.Succeeded -> {
                        captures.remember(captured.snapshot)
                        response
                            .put("observation", bridgeJson.snapshotJson(captured.snapshot))
                            .put("screenshotBase64", base64.encode(captured.screenshot))
                            .put("screenshotMimeType", "image/png")
                    }
                }
                reply.write(response)
            }
        }
    }

    private suspend fun runDemo(request: DemoRequest, reply: BridgeReply) {
        val startedSession = coordinator.start("Desktop Codex demo: ${request.purpose}")
        if (!startedSession) {
            reply.write(errorResponse(request.requestId, "The phone already has an active session."))
            return
        }

        val open = OpenAppAction(
            packageName = request.packageName,
            metadata = ActionMetadata(
                purpose = "Opening ${request.packageName}",
                observationId = "",
                targetDescription = request.packageName,
            ),
        )
        val openResult = coordinator.executeAction(open, null)
        reply.write(bridgeJson.actionResultResponse(request.requestId, "open_app", openResult))
        if (!openResult.isSuccessful()) {
            failSession(reply, request, openResult.resultMessage())
            return
        }

        delay(OPEN_SETTLE_DELAY_MS)
        val afterOpen = captures.captureWithRetry(
            request.packageName,
            request.guardRegions,
            coordinator.activeSessionId(),
        )
        val tapSnapshot = when (afterOpen) {
            is ObservationCaptureResult.Failed -> {
                failSession(reply, request, afterOpen.message)
                return
            }

            is ObservationCaptureResult.Succeeded -> afterOpen.snapshot
        }
        val tap = TapAction(
            x = request.x,
            y = request.y,
            metadata = ActionMetadata(
                purpose = request.purpose,
                observationId = tapSnapshot.id,
                targetDescription = request.targetDescription,
                guardRegions = request.guardRegions,
            ),
        )
        val tapResult = coordinator.executeAction(tap, tapSnapshot)
        reply.write(bridgeJson.actionResultResponse(request.requestId, "tap", tapResult))
        if (!tapResult.isSuccessful()) {
            failSession(reply, request, tapResult.resultMessage())
            return
        }

        delay(POST_ACTION_SETTLE_DELAY_MS)
        val afterTap = captures.captureWithRetry(null, emptyList(), coordinator.activeSessionId())
        when (afterTap) {
            is ObservationCaptureResult.Failed -> {
                failSession(reply, request, "Tap completed, but the post-action observation failed: ${afterTap.message}")
                return
            }

            is ObservationCaptureResult.Succeeded -> {
                coordinator.complete("Demo completed; the phone returned a fresh observation.")
                reply.write(
                    JSONObject()
                        .put("type", "completed")
                        .put("requestId", request.requestId)
                        .put("message", "Opened ${request.packageName} and tapped ${request.x},${request.y}.")
                        .put("observationId", afterTap.snapshot.id)
                        .put("width", afterTap.snapshot.width)
                        .put("height", afterTap.snapshot.height),
                )
            }
        }
    }

    private fun failSession(reply: BridgeReply, request: DemoRequest, message: String) {
        coordinator.stop("Demo stopped: $message")
        reply.write(errorResponse(request.requestId, message))
    }

    private fun parseRequest(json: JSONObject): DemoRequest {
        require(json.optString("type") == "demo_run") {
            "Only type=demo_run is accepted by the development bridge."
        }
        val packageName = json.optString("packageName")
        require(PACKAGE_PATTERN.matches(packageName)) { "packageName is not a valid Android package name." }
        require(json.has("x") && json.has("y")) { "x and y coordinates are required." }
        val x = json.getInt("x")
        val y = json.getInt("y")
        require(x >= 0 && y >= 0) { "x and y must be non-negative." }
        val purpose = json.optString("purpose", "Developer-selected demo coordinate")
        require(purpose.isNotBlank() && purpose.length <= MAX_TEXT_CHARS) { "purpose is invalid." }
        val targetDescription = json.optString("targetDescription", "developer-selected coordinate")
        require(targetDescription.isNotBlank() && targetDescription.length <= MAX_TEXT_CHARS) {
            "targetDescription is invalid."
        }
        return DemoRequest(
            requestId = json.optString("requestId").ifBlank { newUuid().toString() },
            packageName = packageName,
            x = x,
            y = y,
            purpose = purpose,
            targetDescription = targetDescription,
            guardRegions = parseGuardRegions(json.optJSONArray("guardRegions")),
        )
    }

    /** Return currently usable IPv4 addresses that the desktop can dial. */
    fun lanIpv4Addresses(): List<String> = lanAddressProvider()

    private data class DemoRequest(
        val requestId: String,
        val packageName: String,
        val x: Int,
        val y: Int,
        val purpose: String,
        val targetDescription: String,
        val guardRegions: List<GuardRegion>,
    )

    internal companion object {
        const val LAN_BIND_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 8765
        const val PAIRING_DISCOVERY_PORT = 8766
    }
}

internal fun systemLanIpv4Addresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces()
        ?.asSequence()
        ?.filter { networkInterface ->
            networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isVirtual
        }
        ?.flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
        ?.filterIsInstance<Inet4Address>()
        ?.filter { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
        ?.mapNotNull(Inet4Address::getHostAddress)
        ?.distinct()
        ?.sorted()
        ?.toList()
        ?: emptyList()
}.getOrDefault(emptyList())
