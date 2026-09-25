package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.auth.BridgeCredentials
import com.phonecontrol.assistant.bridge.presence.CompanionPresence
import com.phonecontrol.assistant.bridge.protocol.ActionParser.optionalDisplayRef
import com.phonecontrol.assistant.bridge.protocol.ActionParser.parseGuardRegions
import com.phonecontrol.assistant.bridge.protocol.ActionParser.parsePhoneAction
import com.phonecontrol.assistant.bridge.protocol.ActionParser.parseSequenceRequest
import com.phonecontrol.assistant.bridge.protocol.ActionParser.wireActionName
import com.phonecontrol.assistant.bridge.protocol.BridgeJson
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.CAPTURE_ATTEMPTS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.CAPTURE_RETRY_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_AGENT_FEEDBACK_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_APP_BROWSE_RESULTS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_APP_QUERY_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_OBSERVATIONS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_REQUEST_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_TEXT_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.OPEN_SETTLE_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.PACKAGE_PATTERN
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.POST_ACTION_SETTLE_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.InvalidSequencePayloadException
import com.phonecontrol.assistant.bridge.protocol.addDisplayLimitRecovery
import com.phonecontrol.assistant.bridge.protocol.beforeScreenshotOrNull
import com.phonecontrol.assistant.bridge.protocol.buildAllowedAppsResponse
import com.phonecontrol.assistant.bridge.protocol.buildAppDisplayLayoutResponse
import com.phonecontrol.assistant.bridge.protocol.buildBrowseAppsResponse
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.protocol.failedActionCompletion
import com.phonecontrol.assistant.bridge.protocol.failureCode
import com.phonecontrol.assistant.bridge.protocol.isSuccessful
import com.phonecontrol.assistant.bridge.protocol.resultMessage
import com.phonecontrol.assistant.bridge.protocol.sessionStateName
import com.phonecontrol.assistant.bridge.protocol.staleDetailsOrNull
import com.phonecontrol.assistant.bridge.protocol.unstartedSequenceFailure
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
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.BackAction
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.KeypressAction
import com.phonecontrol.assistant.domain.KeypressKey
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.ObservationSize
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.domain.StaleObservationReason
import com.phonecontrol.assistant.domain.SwipeAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.TypeAction
import com.phonecontrol.assistant.domain.WaitAction
import com.phonecontrol.assistant.overlay.OverlayHideReason
import com.phonecontrol.assistant.session.ActionExecutionResult
import com.phonecontrol.assistant.session.AttentionResolution
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.session.defaultDhdToolPurpose
import com.phonecontrol.assistant.observation.ForegroundAppResult
import com.phonecontrol.assistant.observation.ObservationCaptureResult
import com.phonecontrol.assistant.observation.PhoneObservationSource
import com.phonecontrol.assistant.execution.TransportResult
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplayCloseResult
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.taskDisplayReference
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class PendingCompanionPairing(
    val requestId: String,
    val deviceId: String,
    val desktopName: String,
    val expiresAtEpochMs: Long,
)

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
    @Volatile private var pairingSocket: DatagramSocket? = null
    @Volatile private var started = false
    private val presence = CompanionPresence(clock)
  private val pairingStateLock = Any()
    private val discoveryNonces = LinkedHashMap<String, Long>()
  private val completedPairingResponses = LinkedHashMap<String, CompletedCompanionPairingResponse>()
  @Volatile private var pendingCompanionPairingRequest: PendingCompanionPairingRequest? = null
  private val _pendingCompanionPairing = MutableStateFlow<PendingCompanionPairing?>(null)
    private val phoneActionMutex = Mutex()
    private val overlayVisibilityGate
        get() = platform.overlayVisibilityGate()
    private val bridgeJson = BridgeJson(base64)
    private val observations = Collections.synchronizedMap(
        object : LinkedHashMap<String, ObservationSnapshot>(MAX_OBSERVATIONS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ObservationSnapshot>?): Boolean =
                size > MAX_OBSERVATIONS
        },
    )

    private data class PendingCompanionPairingRequest(
        val publicRequest: PendingCompanionPairing,
        val pairingNonce: String,
        val address: InetAddress,
        val port: Int,
        val socket: DatagramSocket,
    )

    private data class CompletedCompanionPairingResponse(
        val pairingNonce: String,
        val expiresAtEpochMs: Long,
        val response: JSONObject,
    )

    val companionConnected: StateFlow<Boolean>
        get() = presence.companionConnected

    val pendingCompanionPairing: StateFlow<PendingCompanionPairing?> =
        _pendingCompanionPairing.asStateFlow()

    /** Approve the pending desktop request and release the LAN auth token once. */
    fun approvePendingCompanionPairing(): Boolean = respondToPendingCompanionPairing(approved = true)

    /** Reject the pending desktop request without revealing the LAN auth token. */
    fun rejectPendingCompanionPairing(): Boolean = respondToPendingCompanionPairing(approved = false)

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
                platform.logError(TAG, "Development bridge stopped", error)
            }
        }
        scope.launch { runPairingDiscovery() }
        scope.launch { presence.monitor() }
    }

    fun stop() {
        started = false
        serverSocket?.close()
        serverSocket = null
        pairingSocket?.close()
        pairingSocket = null
        clearPendingCompanionPairing()
        presence.release()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun runPairingDiscovery() {
        try {
            val socket = DatagramSocket(PAIRING_DISCOVERY_PORT, InetAddress.getByName(LAN_BIND_HOST))
            pairingSocket = socket
            val buffer = ByteArray(MAX_REQUEST_CHARS)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                runCatching { handlePairingRequest(socket, packet) }
                    .onFailure { error ->
                        if (!socket.isClosed) {
                            platform.logWarning(TAG, "Could not handle a pairing discovery packet", error)
                        }
                    }
            }
        } catch (_: SocketException) {
            // Closing the pairing socket is the normal shutdown path.
        } catch (error: Throwable) {
            platform.logWarning(TAG, "Pairing discovery stopped", error)
        } finally {
            pairingSocket = null
        }
    }

    internal fun handlePairingRequest(socket: DatagramSocket, packet: DatagramPacket) {
        val request = try {
            JSONObject(
                String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
            )
        } catch (_: Throwable) {
            return
        }
        if (request.optInt("version", -1) != PAIRING_PROTOCOL_VERSION) return
        when (request.optString("type")) {
            "dhd_discover_request" -> handlePhoneDiscoveryRequest(socket, packet, request)
            "dhd_pair_approval_request" -> handlePairingApprovalRequest(socket, packet, request)
        }
    }

    private fun handlePhoneDiscoveryRequest(
        socket: DatagramSocket,
        packet: DatagramPacket,
        request: JSONObject,
    ) {
        val requestId = request.optString("requestId").trim()
        if (requestId.isBlank()) return

        val pairingNonce = newUuid().toString().replace("-", "")
        rememberDiscoveryNonce(pairingNonce)
        val response = JSONObject()
            .put("type", "dhd_discover_offer")
            .put("version", PAIRING_PROTOCOL_VERSION)
            .put("requestId", requestId)
            .put("deviceId", deviceId)
            .put("deviceName", companionDeviceName())
            .put("model", deviceInfo.model.trim())
            .put("port", listeningPort)
            .put("pairingNonce", pairingNonce)
        addLanAddresses(response)
        sendPairingResponse(socket, packet, response)
    }

    private fun handlePairingApprovalRequest(
        socket: DatagramSocket,
        packet: DatagramPacket,
        request: JSONObject,
    ) {
        val requestId = request.optString("requestId").trim()
        val candidateDeviceId = request.optString("deviceId").trim()
        val pairingNonce = request.optString("pairingNonce").trim()
        if (requestId.isBlank() || candidateDeviceId != deviceId || pairingNonce.isBlank()) return

        val completedResponse = synchronized(pairingStateLock) {
            val now = clock.wallMillis()
            completedPairingResponses.entries.removeIf { (_, value) -> value.expiresAtEpochMs <= now }
            completedPairingResponses[requestId]
                ?.takeIf { it.pairingNonce == pairingNonce }
        }
        if (completedResponse != null) {
            sendPairingResponse(socket, packet, completedResponse.response)
            return
        }

        val now = clock.wallMillis()
        val expiresAt = now + PAIRING_APPROVAL_TIMEOUT_MS
        val desktopName = request.optString("desktopName").trim()
            .ifBlank { "DHD Companion" }
            .take(MAX_DESKTOP_NAME_CHARS)
        val publicRequest = PendingCompanionPairing(
            requestId = requestId,
            deviceId = deviceId,
            desktopName = desktopName,
            expiresAtEpochMs = expiresAt,
        )
        val pending = PendingCompanionPairingRequest(
            publicRequest = publicRequest,
            pairingNonce = pairingNonce,
            address = packet.address,
            port = packet.port,
            socket = socket,
        )

        val existing = synchronized(pairingStateLock) { pendingCompanionPairingRequest }
        if (existing != null && existing.publicRequest.expiresAtEpochMs > now) {
            if (existing.publicRequest.requestId == requestId && existing.pairingNonce == pairingNonce) {
                // UDP retries from the same desktop are expected. Re-send the
                // acknowledgement instead of turning a lost packet into a
                // false pairing rejection.
                sendPairingResponse(
                    socket,
                    packet,
                    JSONObject()
                        .put("type", "dhd_pair_approval_pending")
                        .put("version", PAIRING_PROTOCOL_VERSION)
                        .put("requestId", requestId)
                        .put("deviceId", deviceId)
                        .put("expiresAtEpochMs", existing.publicRequest.expiresAtEpochMs),
                )
                return
            }
            sendPairingResponse(
                socket,
                packet,
                approvalRejection(requestId, "Another desktop pairing request is already waiting for approval."),
            )
            return
        }

        if (!consumeDiscoveryNonce(pairingNonce)) {
            sendPairingResponse(
                socket,
                packet,
                approvalRejection(requestId, "This discovery request has expired. Refresh the phone list and try again."),
            )
            return
        }

        synchronized(pairingStateLock) {
            pendingCompanionPairingRequest = pending
            _pendingCompanionPairing.value = publicRequest
        }

        sendPairingResponse(
            socket,
            packet,
            JSONObject()
                .put("type", "dhd_pair_approval_pending")
                .put("version", PAIRING_PROTOCOL_VERSION)
                .put("requestId", requestId)
                .put("deviceId", deviceId)
                .put("expiresAtEpochMs", expiresAt),
        )
        scope.launch {
            delay(PAIRING_APPROVAL_TIMEOUT_MS)
            synchronized(pairingStateLock) {
                if (pendingCompanionPairingRequest?.publicRequest?.requestId == requestId) {
                    pendingCompanionPairingRequest = null
                    _pendingCompanionPairing.value = null
                }
            }
        }
    }

    private fun addLanAddresses(response: JSONObject) {
        val addresses = JSONArray()
        lanIpv4Addresses().forEach(addresses::put)
        response.put("addresses", addresses)
    }

    private fun sendPairingResponse(
        socket: DatagramSocket,
        packet: DatagramPacket,
        response: JSONObject,
    ) {
        val bytes = response.toString().toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
    }

    private fun approvalRejection(requestId: String, message: String): JSONObject = JSONObject()
        .put("type", "dhd_pair_approval_rejected")
        .put("version", PAIRING_PROTOCOL_VERSION)
        .put("requestId", requestId)
        .put("deviceId", deviceId)
        .put("message", message)

    private fun rememberDiscoveryNonce(nonce: String) {
        val now = clock.wallMillis()
        synchronized(pairingStateLock) {
            discoveryNonces.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
            discoveryNonces[nonce] = now + DISCOVERY_NONCE_TTL_MS
            while (discoveryNonces.size > MAX_DISCOVERY_NONCES) {
                discoveryNonces.remove(discoveryNonces.keys.first())
            }
        }
    }

    private fun consumeDiscoveryNonce(nonce: String): Boolean {
        val now = clock.wallMillis()
        synchronized(pairingStateLock) {
            discoveryNonces.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
            return discoveryNonces.remove(nonce)?.let { it > now } == true
        }
    }

    private fun clearPendingCompanionPairing() {
        synchronized(pairingStateLock) {
            pendingCompanionPairingRequest = null
            _pendingCompanionPairing.value = null
            discoveryNonces.clear()
            completedPairingResponses.clear()
        }
    }

    private fun respondToPendingCompanionPairing(approved: Boolean): Boolean {
        val pending = synchronized(pairingStateLock) {
            val current = pendingCompanionPairingRequest
            if (current == null || current.publicRequest.expiresAtEpochMs <= clock.wallMillis()) {
                pendingCompanionPairingRequest = null
                _pendingCompanionPairing.value = null
                null
            } else {
                pendingCompanionPairingRequest = null
                _pendingCompanionPairing.value = null
                current
            }
        } ?: return false

        val response = if (approved) {
            JSONObject()
                .put("type", "dhd_pair_approval_offer")
                .put("version", PAIRING_PROTOCOL_VERSION)
                .put("requestId", pending.publicRequest.requestId)
                .put("deviceId", deviceId)
                .put("port", listeningPort)
                .put("token", authenticationToken)
                .also(::addLanAddresses)
        } else {
            approvalRejection(
                pending.publicRequest.requestId,
                "The phone declined the desktop companion pairing request.",
            )
        }
        synchronized(pairingStateLock) {
            completedPairingResponses[pending.publicRequest.requestId] = CompletedCompanionPairingResponse(
                pairingNonce = pending.pairingNonce,
                expiresAtEpochMs = clock.wallMillis() + COMPLETED_PAIRING_RESPONSE_TTL_MS,
                response = response,
            )
            while (completedPairingResponses.size > MAX_COMPLETED_PAIRING_RESPONSES) {
                completedPairingResponses.remove(completedPairingResponses.keys.first())
            }
        }
        // The approval callback is invoked by Compose on the main thread;
        // keep the UDP write on the bridge's IO scope so Android never blocks
        // or rejects it as network work on the UI thread.
        scope.launch {
            runCatching {
                sendPairingResponse(
                    pending.socket,
                    DatagramPacket(ByteArray(0), 0, pending.address, pending.port),
                    response,
                )
            }.onFailure { error ->
                platform.logWarning(TAG, "Could not send the companion pairing response", error)
            }
        }
        return true
    }

    private fun companionDeviceName(): String {
        val manufacturer = deviceInfo.manufacturer.trim()
        val model = deviceInfo.model.trim()
        return listOf(manufacturer, model)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
            .ifBlank { "DHD phone" }
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
                "demo_run" -> phoneActionMutex.withLock { runDemo(parseRequest(json), reply) }
                "start_session" -> startSession(requestId, json, reply)
                "status" -> status(requestId, reply)
                "heartbeat" -> heartbeat(requestId, reply)
                "companion_disconnected" -> companionDisconnected(requestId, reply)
                "pending_request" -> pendingRequest(requestId, reply)
                "claim_request" -> claimRequest(requestId, json, reply)
                "pending_steer" -> pendingSteer(requestId, json, reply)
                "claim_steer" -> claimSteer(requestId, json, reply)
                "release_steer" -> releaseSteer(requestId, json, reply)
                "complete_steer" -> completeSteer(requestId, json, reply)
                "bind_codex_thread" -> bindCodexThread(requestId, json, reply)
                "release_request" -> releaseRequest(requestId, json, reply)
                "stream_agent_message" -> streamAgentMessage(requestId, json, reply)
                "complete_session" -> completeSession(requestId, json, reply)
                "fail_session" -> failSession(requestId, json, reply)
                "allowed_apps" -> withDhdTool(json, ToolNames.LIST_ALLOWED_APPS) {
                    allowedApps(requestId, json, reply)
                }
                "browse_apps" -> withDhdTool(json, ToolNames.BROWSE_APP) {
                    browseApps(requestId, json, reply)
                }
                "set_app_display_layout" -> withDhdTool(json, ToolNames.SET_APP_DISPLAY_LAYOUT) {
                    setAppDisplayLayout(requestId, json, reply)
                }
                "list_displays" -> listDisplays(requestId, reply)
                "close_display" -> closeDisplay(requestId, json, reply)
                "foreground_app" -> withDhdTool(json, ToolNames.FOREGROUND_APP) {
                    foregroundApp(requestId, json, reply)
                }
                "observe" -> withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.OBSERVE,
                ) {
                    observe(requestId, json, reply)
                }
                "execute_action" -> withDhdTool(
                    json = json,
                    fallbackToolName = fallbackActionToolName(json),
                ) {
                    phoneActionMutex.withLock { executeAction(requestId, json, reply) }
                }
                "execute_sequence" -> withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.EXECUTE_SEQUENCE,
                ) {
                    phoneActionMutex.withLock { executeSequence(requestId, json, reply) }
                }
                "request_attention" -> withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.REQUEST_ATTENTION,
                    terminalStatus = DhdToolCallStatus.ATTENTION,
                ) {
                    requestAttention(requestId, json, reply)
                }
                "stop_session" -> stopSession(requestId, json, reply)
                else -> reply.write(errorResponse(requestId, "Unsupported bridge request type."))
            }
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            platform.logError(TAG, "Bridge request failed", error)
            reply.write(errorResponse(requestId, "The phone bridge failed: $message"))
        }
    }

    private suspend fun withDhdTool(
        json: JSONObject,
        fallbackToolName: String,
        hideDuringObservation: Boolean = false,
        terminalStatus: DhdToolCallStatus = DhdToolCallStatus.COMPLETED,
        block: suspend () -> Unit,
    ) {
        val toolName = json.optString("tool").trim().ifBlank { fallbackToolName }
        val callId = coordinator.beginToolCall(toolName, toolPurpose(toolName, json))
        val visibilityToken = if (hideDuringObservation) {
            overlayVisibilityGate?.acquire(OverlayHideReason.OBSERVATION)
        } else {
            null
        }
        try {
            block()
            coordinator.finishToolCall(callId, terminalStatus)
        } catch (error: Throwable) {
            coordinator.finishToolCall(callId, DhdToolCallStatus.FAILED)
            throw error
        } finally {
            visibilityToken?.close()
        }
    }

    internal fun fallbackActionToolName(json: JSONObject): String {
        val actionType = json.optJSONObject("action")?.optString("type")?.lowercase()
        return if (actionType == "open_app") ToolNames.OPEN_APP else ToolNames.EXECUTE
    }

    internal fun toolPurpose(toolName: String, json: JSONObject): String =
        metadataPurpose(json) ?: when (toolName) {
            ToolNames.OBSERVE -> json.optString("purpose").trim().takeIf(String::isNotBlank)
                ?: defaultDhdToolPurpose(toolName)
            ToolNames.OPEN_APP -> openingAppPurpose(json)
            ToolNames.SET_APP_DISPLAY_LAYOUT -> appDisplayLayoutPurpose(json)
            ToolNames.EXECUTE -> {
                val action = json.optJSONObject("action")
                if (action?.optString("type")?.equals("open_app", ignoreCase = true) == true) {
                    openingAppPurpose(json)
                } else {
                    defaultDhdToolPurpose(toolName)
                }
            }
            ToolNames.REQUEST_ATTENTION -> defaultDhdToolPurpose(toolName)
            else -> defaultDhdToolPurpose(toolName)
        }

    /** Read the user-visible purpose from each tool's metadata shape. */
    internal fun metadataPurpose(json: JSONObject): String? {
        val directPurpose = json.optJSONObject("metadata")
            ?.optString("purpose")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (directPurpose != null) return directPurpose

        val actionPurpose = json.optJSONObject("action")
            ?.optJSONObject("metadata")
            ?.optString("purpose")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (actionPurpose != null) return actionPurpose

        val actions = json.optJSONArray("actions")
        for (index in 0 until (actions?.length() ?: 0)) {
            val purpose = actions
                ?.optJSONObject(index)
                ?.optJSONObject("metadata")
                ?.optString("purpose")
                ?.trim()
                ?.takeIf(String::isNotBlank)
            if (purpose != null) return purpose
        }
        return null
    }

    private fun openingAppPurpose(json: JSONObject): String {
        val packageName = json.optJSONObject("action")
            ?.optString("packageName")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val label = packageName
            ?.let(::appLabel)
            ?.takeIf { it.isNotBlank() && !it.equals(packageName, ignoreCase = true) }
        return label?.let { "Opening $it" } ?: defaultDhdToolPurpose(ToolNames.OPEN_APP)
    }

    private fun appDisplayLayoutPurpose(json: JSONObject): String {
        val packageName = json.optString("packageName")
            .trim()
            .takeIf(String::isNotBlank)
        val label = packageName
            ?.let(::appLabel)
            ?.takeIf { it.isNotBlank() && !it.equals(packageName, ignoreCase = true) }
        return when (json.optString("layout").trim().lowercase(Locale.ROOT)) {
            "full_size" -> label?.let { "Fitting $it to the task display" }
                ?: "Fitting the app to the task display"
            "standard" -> label?.let { "Restoring ${it}'s standard task layout" }
                ?: "Restoring the standard task layout"
            else -> defaultDhdToolPurpose(ToolNames.SET_APP_DISPLAY_LAYOUT)
        }
    }

    private fun startSession(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val request = json.optString("request").trim()
        require(request.isNotEmpty() && request.length <= MAX_REQUEST_CHARS) {
            "request must be 1-$MAX_REQUEST_CHARS characters."
        }
        val conversationId = json.optString("conversationId").trim().ifBlank { null }
        val reasoningEffort = json.optString("reasoningEffort")
            .trim()
            .ifBlank { ReasoningEffort.default.codexValue }
        val fastMode = json.optBoolean("fastMode", false)
        if (!coordinator.start(request, conversationId, reasoningEffort, fastMode)) {
            reply.write(errorResponse(requestId, "The phone already has an active session."))
            return
        }
        val state = coordinator.state.value
        val sessionId = (state as? SessionState.Running)?.sessionId
        require(sessionId != null) { "The phone session did not enter the running state." }
        // A desktop-originated session must get the same persistent foreground
        // notification as a session started from the Compose Run button.
        runCatching {
            platform.startSessionService(request, reasoningEffort, fastMode, conversationId)
        }.onFailure { error ->
            platform.logWarning(TAG, "Could not start the foreground notification for the bridge session", error)
        }
        reply.write(
            JSONObject()
                .put("type", "started")
                .put("requestId", requestId)
                .put("ok", true)
                .put("sessionId", sessionId)
                .put("conversationId", (state as? SessionState.Running)?.conversationId ?: JSONObject.NULL)
                .put("reasoningEffort", (state as? SessionState.Running)?.reasoningEffort ?: ReasoningEffort.default.codexValue)
                .put("fastMode", (state as? SessionState.Running)?.fastMode ?: false)
                .put("message", "Phone assistant session started."),
        )
    }

    private fun status(
        requestId: String,
        reply: BridgeReply,
    ) {
        val state = coordinator.state.value
        val response = JSONObject()
            .put("type", "status")
            .put("requestId", requestId)
            .put("ok", true)
            .put("state", sessionStateName(state))
            .put("active", state is SessionState.Running || state is SessionState.Paused)
            .put("companionConnected", companionConnected.value)
        when (state) {
            is SessionState.Running -> response
                .put("sessionId", state.sessionId)
                .put("conversationId", state.conversationId ?: JSONObject.NULL)
                .put("request", state.request)
                .put("currentPurpose", state.currentPurpose)
                .put("fastMode", state.fastMode)
                .put("requestAvailable", coordinator.pendingRequest()?.sessionId == state.sessionId)
            is SessionState.Paused -> response
                .put("sessionId", state.sessionId)
                .put("conversationId", state.conversationId ?: JSONObject.NULL)
                .put("request", state.request)
                .put("currentPurpose", state.currentPurpose)
                .put("fastMode", state.fastMode)
            else -> Unit
        }
        reply.write(response)
    }

    private fun heartbeat(
        requestId: String,
        reply: BridgeReply,
    ) {
        // Keep the phone-side companion lease independent from pending work,
        // Codex startup, or a long-running task request.
        presence.markSeen()
        reply.write(
            JSONObject()
                .put("type", "heartbeat")
                .put("requestId", requestId)
                .put("ok", true)
                .put("message", "Desktop companion heartbeat acknowledged."),
        )
    }

    private fun companionDisconnected(
        requestId: String,
        reply: BridgeReply,
    ) {
        presence.release()
        reply.write(
            JSONObject()
                .put("type", "companion_disconnected")
                .put("requestId", requestId)
                .put("ok", true)
                .put("message", "Desktop companion presence released."),
        )
    }

    private fun pendingRequest(
        requestId: String,
        reply: BridgeReply,
    ) {
        // The companion's normal pending-request poll doubles as its
        // heartbeat. The phone uses this to render the existing recovery card
        // without exposing the request or requiring another protocol.
        presence.markSeen()
        val pending = coordinator.pendingRequest()
        val response = JSONObject()
            .put("type", "pending_request")
            .put("requestId", requestId)
            .put("ok", true)
            .put("available", pending != null)
            .put("warmupRequested", presence.consumeCodexWarmupRequest())
        if (pending != null) {
            response
                .put("sessionId", pending.sessionId)
                .put("conversationId", pending.conversationId ?: JSONObject.NULL)
                .put("codexThreadId", pending.codexThreadId ?: JSONObject.NULL)
                .put("reasoningEffort", pending.reasoningEffort)
                .put("fastMode", pending.fastMode)
                .put("continuation", pending.isContinuation)
                .put("request", pending.request)
        }
        reply.write(response)
    }

    private fun claimRequest(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val expectedSessionId = json.optString("sessionId").trim().ifBlank { null }
        val claimed = coordinator.claimRequest(expectedSessionId)
        if (claimed == null) {
            reply.write(
                errorResponse(requestId, "No unclaimed running phone request matched the supplied sessionId.")
                    .put("code", BridgeErrorCodes.REQUEST_NOT_AVAILABLE),
            )
            return
        }
        reply.write(
            JSONObject()
                .put("type", "request_claimed")
                .put("requestId", requestId)
                .put("ok", true)
                .put("sessionId", claimed.sessionId)
                .put("conversationId", claimed.conversationId ?: JSONObject.NULL)
                .put("codexThreadId", claimed.codexThreadId ?: JSONObject.NULL)
                .put("reasoningEffort", claimed.reasoningEffort)
                .put("fastMode", claimed.fastMode)
                .put("continuation", claimed.isContinuation)
                .put("request", claimed.request)
                .put("message", "Phone request claimed by the desktop Codex companion."),
        )
    }

    private fun pendingSteer(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        presence.markSeen()
        val expectedSessionId = json.optString("sessionId").trim().ifBlank { null }
        val state = coordinator.state.value
        val pending = coordinator.pendingSteer(expectedSessionId)
        val response = JSONObject()
            .put("type", "pending_steer")
            .put("requestId", requestId)
            .put("ok", true)
            .put("active", state is SessionState.Running)
            .put("attentionPending", coordinator.attentionPending())
            .put("available", pending != null && !coordinator.attentionPending())
        if (pending != null) {
            response
                .put("steerId", pending.steerId)
                .put("sessionId", pending.sessionId)
        }
        reply.write(response)
    }

    private fun claimSteer(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val claimed = coordinator.claimSteer(sessionId, steerId)
        if (claimed == null) {
            reply.write(
                errorResponse(requestId, "No unclaimed steer matched the supplied session and steer id.")
                    .put("code", BridgeErrorCodes.STEER_NOT_AVAILABLE),
            )
            return
        }
        reply.write(
            JSONObject()
                .put("type", "steer_claimed")
                .put("requestId", requestId)
                .put("ok", true)
                .put("steerId", claimed.steerId)
                .put("sessionId", claimed.sessionId)
                .put("text", claimed.text),
        )
    }

    private fun releaseSteer(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val released = coordinator.releaseSteer(sessionId, steerId)
        reply.write(
            JSONObject()
                .put("type", "steer_released")
                .put("requestId", requestId)
                .put("ok", released)
                .put("sessionId", sessionId)
                .put("steerId", steerId)
                .put("released", released),
        )
    }

    private fun completeSteer(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val completed = coordinator.completeSteer(sessionId, steerId)
        reply.write(
            JSONObject()
                .put("type", "steer_completed")
                .put("requestId", requestId)
                .put("ok", completed)
                .put("sessionId", sessionId)
                .put("steerId", steerId)
                .put("delivered", completed),
        )
    }

    private fun releaseRequest(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        val released = coordinator.releaseRequest(sessionId)
        reply.write(
            JSONObject()
                .put("type", "request_released")
                .put("requestId", requestId)
                .put("ok", true)
                .put("sessionId", sessionId)
                .put("released", released),
        )
    }

    private fun bindCodexThread(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val conversationId = json.optString("conversationId").trim()
        val codexThreadId = json.optString("codexThreadId").trim()
        require(conversationId.isNotEmpty()) { "conversationId is required." }
        require(codexThreadId.isNotEmpty()) { "codexThreadId is required." }
        val bound = coordinator.bindCodexThread(conversationId, codexThreadId)
        reply.write(
            JSONObject()
                .put("type", "codex_thread_bound")
                .put("requestId", requestId)
                .put("ok", bound)
                .put("conversationId", conversationId)
                .put("codexThreadId", codexThreadId),
        )
    }

    private fun failSession(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        if (coordinator.state.value.sessionIdOrNull != sessionId) {
            reply.write(
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", BridgeErrorCodes.SESSION_NOT_RUNNING),
            )
            return
        }
        val reason = json.optString("reason", "The desktop Codex turn failed.")
            .trim()
            .ifBlank { "The desktop Codex turn failed." }
            .take(MAX_AGENT_FEEDBACK_CHARS)
        val failed = coordinator.fail(reason)
        if (failed) {
            platform.showAttentionNotification(
                "DHD stopped: $reason",
                coordinator.state.value.conversationIdOrNull,
            )
        }
        platform.reconcileServiceLifetime()
        reply.write(
            JSONObject()
                .put("type", "session_failed")
                .put("requestId", requestId)
                .put("ok", failed)
                .put("message", reason),
        )
    }

    private fun streamAgentMessage(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        presence.markSeen()
        val sessionId = json.optString("sessionId").trim()
        val messageId = json.optString("messageId").trim()
        val text = json.optString("text")
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(messageId.isNotEmpty() && messageId.length <= MAX_TEXT_CHARS) {
            "messageId must be 1-$MAX_TEXT_CHARS characters."
        }
        require(text.length <= MAX_AGENT_FEEDBACK_CHARS) {
            "text must be at most $MAX_AGENT_FEEDBACK_CHARS characters."
        }
        if (coordinator.state.value.sessionIdOrNull != sessionId) {
            reply.write(
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", BridgeErrorCodes.SESSION_NOT_RUNNING),
            )
            return
        }
        val streamed = coordinator.streamAgentMessage(sessionId, messageId, text)
        reply.write(
            JSONObject()
                .put("type", "agent_message_streamed")
                .put("requestId", requestId)
                .put("ok", streamed)
                .put("sessionId", sessionId)
                .put("messageId", messageId),
        )
    }

    private fun completeSession(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        val message = json.optString("message", "Your DHD task is ready to review.")
            .trim()
            .ifBlank { "Your DHD task is ready to review." }
            .take(MAX_TEXT_CHARS)
        val feedback = json.optString("feedback")
            .trim()
            .ifBlank { null }
            ?.take(MAX_AGENT_FEEDBACK_CHARS)
        val agentMessageId = json.optString("agentMessageId")
            .trim()
            .ifBlank { null }
            ?.take(MAX_TEXT_CHARS)
        val activeSessionId = coordinator.state.value.sessionIdOrNull
        if (activeSessionId != sessionId) {
            reply.write(
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", BridgeErrorCodes.SESSION_NOT_RUNNING),
            )
            return
        }
        val completionMessage = feedback ?: message
        val completed = coordinator.complete(
            completionMessage,
            agentFeedback = feedback,
            agentMessageId = agentMessageId,
        )
        if (completed) {
            platform.showCompletionNotification(completionMessage, coordinator.state.value.conversationIdOrNull)
        }
        platform.removeAttentionNotification()
        platform.reconcileServiceLifetime()
        reply.write(
            JSONObject()
                .put("type", "session_completed")
                .put("requestId", requestId)
                .put("ok", completed)
                .put("sessionId", sessionId)
                .put("conversationId", coordinator.state.value.conversationIdOrNull ?: JSONObject.NULL)
                .put("message", completionMessage)
                .put("feedback", feedback ?: JSONObject.NULL),
        )
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
            when (val resolution = resolveDisplayTarget(displayRef = requestedDisplayRef)) {
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
                when (val captured = captureWithRetry(
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
                        remember(captured.snapshot)
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

    private fun allowedApps(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val fullAccess = fullAccessProvider()
        val includeAll = json.optBoolean("includeAll", false)
        val allowedPackages = if (fullAccess) emptySet() else allowedPackagesProvider()
        coordinator.recordPurpose(
            purpose = when {
                includeAll && fullAccess -> "Listing all launchable apps"
                includeAll -> "Listing all allowed launchable apps"
                else -> "Listing allowed apps"
            },
            toolName = ToolNames.LIST_ALLOWED_APPS,
        )
        reply.write(
            buildAllowedAppsResponse(
                requestId = requestId,
                fullAccess = fullAccess,
                includeAll = includeAll,
                allowedPackages = allowedPackages,
                apps = if (includeAll) {
                    platform.launchableApps()
                        .filter { fullAccess || it.packageName in allowedPackages }
                } else {
                    emptyList()
                },
            ),
        )
    }

    private fun browseApps(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val query = json.optString("query").trim()
        if (query.isEmpty() || query.length > MAX_APP_QUERY_CHARS) {
            reply.write(
                errorResponse(requestId, "App search requires a query between 1 and $MAX_APP_QUERY_CHARS characters.")
                    .put("code", BridgeErrorCodes.INVALID_APP_QUERY),
            )
            return
        }

        coordinator.recordPurpose(
            purpose = "Browsing installed apps",
            targetDescription = query,
            toolName = ToolNames.BROWSE_APP,
        )

        val fullAccess = fullAccessProvider()
        val allowedPackages = if (fullAccess) emptySet() else allowedPackagesProvider()
        val candidates = platform.launchableApps()
            .asSequence()
            .filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .toList()
        val returnedApps = candidates.take(MAX_APP_BROWSE_RESULTS)
        reply.write(
            buildBrowseAppsResponse(
                requestId = requestId,
                query = query,
                fullAccess = fullAccess,
                allowedPackages = allowedPackages,
                apps = returnedApps,
                truncated = candidates.size > returnedApps.size,
            ),
        )
    }

    private fun setAppDisplayLayout(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val packageName = json.optString("packageName").trim()
        if (!PACKAGE_PATTERN.matches(packageName)) {
            reply.write(
                errorResponse(requestId, "packageName is not a valid Android package name.")
                    .put("code", BridgeErrorCodes.INVALID_PACKAGE),
            )
            return
        }

        val layout = json.optString("layout").trim().lowercase(Locale.ROOT)
        val enabled = when (layout) {
            "full_size" -> true
            "standard" -> false
            else -> {
                reply.write(
                    errorResponse(requestId, "layout must be either full_size or standard.")
                        .put("code", BridgeErrorCodes.INVALID_APP_DISPLAY_LAYOUT),
                )
                return
            }
        }

        val app = platform.launchableApps()
            .firstOrNull { it.packageName == packageName }
        if (app == null) {
            reply.write(
                errorResponse(requestId, "No launchable app matches packageName=$packageName.")
                    .put("code", BridgeErrorCodes.APP_NOT_FOUND),
            )
            return
        }

        val fullAccess = fullAccessProvider()
        val allowed = fullAccess || packageName in allowedPackagesProvider()
        if (!allowed) {
            reply.write(
                errorResponse(requestId, "The app is not allowed for the current DHD access mode.")
                    .put("code", BridgeErrorCodes.APP_NOT_ALLOWED),
            )
            return
        }

        coordinator.recordPurpose(
            purpose = if (enabled) "Saving full-size app layout" else "Restoring standard app layout",
            targetDescription = app.label,
            toolName = ToolNames.SET_APP_DISPLAY_LAYOUT,
        )
        val changed = platform.isFullSizeLayoutEnabled(packageName) != enabled
        platform.setFullSizeLayoutEnabled(packageName, enabled)
        reply.write(
            buildAppDisplayLayoutResponse(
                requestId = requestId,
                packageName = packageName,
                appLabel = app.label,
                layout = layout,
                changed = changed,
            ),
        )
    }

    private suspend fun listDisplays(
        requestId: String,
        reply: BridgeReply,
    ) {
        val backend = taskDisplayBackend
        if (backend == null) {
            reply.write(
                errorResponse(requestId, "The task display registry is unavailable.")
                    .put("code", BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE),
            )
            return
        }
        val displays = currentDisplayJson(backend)
        reply.write(
            JSONObject()
                .put("type", "displays")
                .put("requestId", requestId)
                .put("ok", true)
                .put("displays", JSONArray(displays))
                .put("count", displays.size)
                .put("message", if (displays.isEmpty()) "No task displays are available." else "Returned active and retained task displays."),
        )
    }

    private suspend fun closeDisplay(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val backend = taskDisplayBackend
        if (backend == null) {
            reply.write(
                errorResponse(requestId, "The task display registry is unavailable.")
                    .put("code", BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE),
            )
            return
        }
        val displayRef = try {
            optionalDisplayRef(json)
        } catch (error: IllegalArgumentException) {
            reply.write(errorResponse(requestId, error.message ?: "displayRef is invalid.").put("code", BridgeErrorCodes.INVALID_DISPLAY_REF))
            return
        }
        if (displayRef == null) {
            reply.write(
                errorResponse(requestId, "displayRef is required to close a display safely. Call dhd_list_displays first and use the matching displayRef.")
                    .put("code", BridgeErrorCodes.DISPLAY_REFERENCE_REQUIRED),
            )
            return
        }
        backend.activeDisplaySessions()
        val record = backend.displayRecords.value.firstOrNull { it.displayRef == displayRef }
        if (record == null) {
            reply.write(
                errorResponse(requestId, "No task display matches the supplied displayRef. Call dhd_list_displays to see the available displays.")
                    .put("code", BridgeErrorCodes.DISPLAY_NOT_FOUND),
            )
            return
        }
        val activeRunKey = coordinator.activeSessionId()
        if (activeRunKey != null && backend.isDisplayClaimedByRun(record.displayId, activeRunKey)) {
            reply.write(
                errorResponse(requestId, "The selected task display is being used by an active DHD run. Stop the active run first, then close the display.")
                    .put("code", BridgeErrorCodes.DISPLAY_IN_USE),
            )
            return
        }
        when (val result = backend.closeTaskDisplay(record.displayId, displayRef)) {
            is TaskDisplayCloseResult.Rejected -> reply.write(
                errorResponse(requestId, result.message).put("code", result.code),
            )

            is TaskDisplayCloseResult.Closed -> reply.write(
                JSONObject()
                    .put("type", "display_closed")
                    .put("requestId", requestId)
                    .put("ok", true)
                    .put("displayRef", result.record.displayRef)
                    .put("appLabel", appLabel(result.record.packageName))
                    .put("status", result.record.status.name.lowercase())
                    .put("message", "The selected task display was ended."),
            )
        }
    }

    /**
     * Return the same actionable inventory as dhd_list_displays. Keeping this
     * in one path means a model can use a displayRef from a recovery response
     * without first making another list call.
     */
    private suspend fun currentDisplayJson(
        backend: TaskDisplayBackend,
    ): List<JSONObject> {
        // Joining the registry's reconciliation job here ensures a freshly
        // started app does not report stale persisted records before native
        // sessions have been adopted or marked unavailable.
        backend.activeDisplaySessions()
        val now = clock.wallMillis()
        return backend.displayRecords.value
            .asSequence()
            .filter { record ->
                record.status != TaskDisplayStatus.ENDED &&
                    record.status != TaskDisplayStatus.EXPIRED &&
                    (record.expiresAtEpochMs == null || record.expiresAtEpochMs > now)
            }
            .map { record -> displayJson(record, now) }
            .toList()
    }

    private suspend fun displayInventoryForRecovery(
        backend: TaskDisplayBackend,
    ): List<JSONObject> = try {
        currentDisplayJson(backend)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        // The limit response is still useful when reconciliation is briefly
        // unavailable; return an empty, well-formed inventory instead of
        // replacing the actionable allocator error with a registry error.
        emptyList()
    }

    private fun displayJson(
        record: com.phonecontrol.assistant.execution.TaskDisplayRecord,
        now: Long = clock.wallMillis(),
    ): JSONObject = JSONObject()
        .put("displayRef", record.displayRef)
        .put("appLabel", appLabel(record.packageName))
        .put("packageName", record.packageName)
        .put("status", record.status.name.lowercase())
        .put("width", record.width)
        .put("height", record.height)
        .put("densityDpi", record.densityDpi)
        .put("createdAtEpochMs", record.createdAtEpochMs)
        .put("terminalAtEpochMs", record.terminalAtEpochMs ?: JSONObject.NULL)
        .put("expiresAtEpochMs", record.expiresAtEpochMs ?: JSONObject.NULL)
        .put(
            "remainingRetentionMs",
            record.expiresAtEpochMs?.let { expiresAt -> (expiresAt - now).coerceAtLeast(0L) }
                ?: JSONObject.NULL,
        )
        .put("lastPurpose", record.lastPurpose)
        .put("error", record.error ?: JSONObject.NULL)

    private fun appLabel(packageName: String): String = runCatching {
        platform.applicationLabel(packageName)
    }.getOrDefault(packageName)

    private suspend fun resolveDisplayTarget(
        displayRef: String?,
        fallbackDisplayId: Int? = null,
        fallbackDisplayRef: String? = null,
        claimForRun: Boolean = true,
    ): TaskDisplayResolution {
        val backend = taskDisplayBackend
            ?: return TaskDisplayResolution.Unavailable(
                code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
                message = "The task display registry is unavailable; call dhd_open_app to create a task display.",
            )
        val runSessionKey = coordinator.activeSessionId()
        if (claimForRun && taskDisplayRequiredProvider() && runSessionKey == null) {
            return TaskDisplayResolution.Unavailable(
                code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
                message = "No active task display run is available. Call dhd_open_app from an active DHD task first.",
            )
        }
        val selectedDisplayRef = displayRef ?: fallbackDisplayRef
        return if (selectedDisplayRef != null) {
            backend.activeDisplaySessions()
            val record = backend.displayRecords.value.firstOrNull { it.displayRef == selectedDisplayRef }
                ?: return TaskDisplayResolution.Unavailable(
                    code = BridgeErrorCodes.DISPLAY_NOT_FOUND,
                    message = "No task display matches the supplied displayRef. Call dhd_list_displays to see the available displays.",
                )
            backend.resolveDisplay(
                displayId = record.displayId,
                claimForSessionKey = if (claimForRun) runSessionKey else null,
                expectedDisplayRef = selectedDisplayRef,
            )
        } else if (fallbackDisplayId != null) {
            backend.resolveDisplay(
                displayId = fallbackDisplayId,
                claimForSessionKey = if (claimForRun) runSessionKey else null,
            )
        } else {
            backend.resolveDefaultDisplay(
                claimForSessionKey = if (claimForRun) runSessionKey else null,
            )
        }
    }

    private suspend fun observe(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val purpose = json.optString("purpose").trim().take(MAX_TEXT_CHARS)
            .ifBlank { "Observing current screen" }
        coordinator.recordPurpose(
            purpose = purpose,
            targetDescription = json.optString("targetDescription").trim().take(MAX_TEXT_CHARS).ifBlank { null },
            toolName = ToolNames.OBSERVE,
        )
        if (!coordinator.awaitPhoneAccessForTool()) {
            reply.write(
                errorResponse(requestId, "Phone access is no longer available; DHD could not observe the phone.")
                    .put("code", BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE),
            )
            return
        }
        val requestedDisplayRef = optionalDisplayRef(json)
        val target = if (taskDisplayRequiredProvider() || requestedDisplayRef != null) {
            when (val resolution = resolveDisplayTarget(
                displayRef = requestedDisplayRef,
            )) {
                is TaskDisplayResolution.Ready -> resolution.target
                is TaskDisplayResolution.Unavailable -> {
                    reply.write(errorResponse(requestId, resolution.message).put("code", resolution.code))
                    return
                }
            }
        } else {
            null
        }
        when (val captured = captureWithRetry(
            expectedPackageName = null,
            guardRegions = emptyList(),
            taskSessionKey = target?.session?.sessionKey ?: coordinator.activeSessionId(),
            displayId = target?.session?.displayId,
            expectedDisplayRef = target?.displayRef,
        )) {
            is ObservationCaptureResult.Failed -> reply.write(
                errorResponse(requestId, captured.message).put("code", captured.code),
            )
            is ObservationCaptureResult.Succeeded -> {
                remember(captured.snapshot)
                reply.write(bridgeJson.observationResponse(requestId, captured.snapshot, captured.screenshot))
            }
        }
    }

    private suspend fun foregroundApp(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        coordinator.recordPurpose(
            purpose = "Checking foreground app",
            toolName = ToolNames.FOREGROUND_APP,
        )
        if (!coordinator.awaitPhoneAccessForTool()) {
            reply.write(
                errorResponse(requestId, "Phone access is no longer available; DHD could not check the phone.")
                    .put("code", BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE),
            )
            return
        }
        val requestedDisplayRef = optionalDisplayRef(json)
        val target = if (taskDisplayRequiredProvider() || requestedDisplayRef != null) {
            when (val resolution = resolveDisplayTarget(
                displayRef = requestedDisplayRef,
            )) {
                is TaskDisplayResolution.Ready -> resolution.target
                is TaskDisplayResolution.Unavailable -> {
                    reply.write(errorResponse(requestId, resolution.message).put("code", resolution.code))
                    return
                }
            }
        } else {
            null
        }
        when (val result = observationProvider.getForegroundApp(
            taskSessionKey = target?.session?.sessionKey ?: coordinator.activeSessionId(),
            displayId = target?.session?.displayId,
            expectedDisplayRef = target?.displayRef,
        )) {
            is ForegroundAppResult.Failed -> reply.write(
                errorResponse(requestId, result.message).put("code", result.code),
            )

            is ForegroundAppResult.Succeeded -> reply.write(
                JSONObject()
                    .put("type", "foreground_app")
                    .put("requestId", requestId)
                    .put("ok", true)
                    .put("packageName", result.app.packageName)
                    .put("activityName", result.app.activityName)
                    .put("rotation", result.app.rotation)
                    .put("width", result.app.width)
                    .put("height", result.app.height)
                    .put(
                        "screenProtection",
                        JSONObject()
                            .put("status", result.app.screenProtection.status.name.lowercase())
                            .put("requiresUserAttention", result.app.screenProtection.requiresUserAttention)
                            .put("signals", JSONArray(result.app.screenProtection.signals))
                            .put("reason", result.app.screenProtection.reason ?: JSONObject.NULL),
                    )
                    .put("message", "The current foreground app is ${result.app.packageName}.")
                    .also { response -> target?.displayRef?.let { response.put("displayRef", it) } },
            )
        }
    }

    private suspend fun executeAction(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val actionJson = json.optJSONObject("action")
            ?: throw IllegalArgumentException("action must be an object.")
        val parsedAction = parsePhoneAction(actionJson)
        val observationId = parsedAction.metadata.observationId.trim()
        val suppliedObservation = synchronized(observations) {
            observationId.takeIf(String::isNotBlank)?.let { observations[it] }
        }
        val runSessionKey = coordinator.activeSessionId()
        if (taskDisplayRequiredProvider() && runSessionKey == null) {
            reply.write(
                failedActionCompletion(
                    requestId = requestId,
                    action = wireActionName(parsedAction),
                    code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
                    message = "No active task display is available; the physical display was not touched.",
                ),
            )
            return
        }
        val requestedDisplayRef = optionalDisplayRef(json)
        val targetResolution = if (requestedDisplayRef != null ||
            suppliedObservation != null ||
            parsedAction !is OpenAppAction
        ) {
            resolveDisplayTarget(
                displayRef = requestedDisplayRef,
                fallbackDisplayId = suppliedObservation?.displayId,
                fallbackDisplayRef = suppliedObservation?.taskSessionKey?.let { taskSessionKey ->
                    taskDisplayReference(taskSessionKey, suppliedObservation.displayId)
                },
            )
        } else {
            null
        }
        val target = when (targetResolution) {
            null -> null
            is TaskDisplayResolution.Ready -> targetResolution.target
            is TaskDisplayResolution.Unavailable -> {
                // If there is no retained display, open_app is allowed to
                // create a fresh one under the current run. Any other
                // resolution failure is actionable and must reach the model.
                if (parsedAction is OpenAppAction &&
                    requestedDisplayRef == null &&
                    targetResolution.code == BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE
                ) {
                    null
                } else {
                    reply.write(
                        failedActionCompletion(
                            requestId = requestId,
                            action = wireActionName(parsedAction),
                            code = targetResolution.code,
                            message = targetResolution.message,
                        ),
                    )
                    return
                }
            }
        }
        if (target != null && suppliedObservation != null &&
            (suppliedObservation.displayId != target.session.displayId ||
                suppliedObservation.taskSessionKey != target.session.sessionKey)
        ) {
            reply.write(
                failedActionCompletion(
                    requestId = requestId,
                    action = wireActionName(parsedAction),
                    code = BridgeErrorCodes.DISPLAY_CHANGED,
                    message = "The supplied observation belongs to a different task display; call dhd_observe with the selected display before retrying.",
                ),
            )
            return
        }
        val taskSessionKey = target?.session?.sessionKey ?: runSessionKey
        val observation = if (suppliedObservation != null) {
            suppliedObservation
        } else if (parsedAction is OpenAppAction && observationId.isBlank()) {
            // Launch is setup rather than an input against a model-selected
            // screen. A task display does not have a meaningful pre-launch
            // physical baseline: the task backend creates the virtual display
            // and launches the allowlisted package atomically. Legacy calls
            // without a task session retain the physical baseline behavior.
            if (taskSessionKey != null) {
                null
            } else when (val captured = captureWithRetry(null, emptyList(), null)) {
                is ObservationCaptureResult.Failed -> {
                    reply.write(
                        failedActionCompletion(
                            requestId = requestId,
                            action = "open_app",
                            code = BridgeErrorCodes.OBSERVATION_FAILED,
                            message = "Could not establish a launch baseline; the app was not opened: ${captured.message}",
                        ),
                    )
                    return
                }

                is ObservationCaptureResult.Succeeded -> {
                    remember(captured.snapshot)
                    captured.snapshot
                }
            }
        } else {
            null
        }
        if (observation == null && parsedAction !is OpenAppAction) {
            reply.write(
                failedActionCompletion(
                    requestId = requestId,
                    action = wireActionName(parsedAction),
                    code = BridgeErrorCodes.OBSERVATION_MISSING,
                    message = "The supplied observationId is missing or expired; observe the phone before retrying.",
                ),
            )
            return
        }
        val action = if (
            parsedAction is OpenAppAction &&
            observationId.isBlank() &&
            observation != null
        ) {
            parsedAction.copy(
                metadata = parsedAction.metadata.copy(observationId = observation.id),
            )
        } else {
            parsedAction
        }
        // Preserve the bridge tool identity on the activity event so the live
        // tool call and its lifecycle row can be rendered as one entry.
        val activityToolName = json.optString("tool")
            .trim()
            .takeIf(String::isNotBlank)
            ?: fallbackActionToolName(json)
        val result = coordinator.executeAction(
            action = action,
            observation = observation,
            toolName = activityToolName,
            targetDisplay = target?.session,
        )
        reply.write(bridgeJson.actionResultResponse(requestId, wireActionName(action), result))
        if (!result.isSuccessful()) {
            val failureCode = result.failureCode()
            val response = JSONObject()
                .put("type", "completed")
                .put("requestId", requestId)
                .put("ok", false)
                .put("action", wireActionName(action))
                .put("message", result.resultMessage())
            failureCode?.let { response.put("code", it) }
            bridgeJson.addBeforeDebug(
                response,
                observation,
                result.beforeScreenshotOrNull(),
            )
            result.staleDetailsOrNull()?.let { details -> bridgeJson.addStaleDiagnostics(response, details) }
            if (failureCode == BridgeErrorCodes.DISPLAY_LIMIT_REACHED) {
                val backend = taskDisplayBackend
                val displays = if (backend == null) {
                    emptyList()
                } else {
                    displayInventoryForRecovery(backend)
                }
                addDisplayLimitRecovery(
                    response = response,
                    packageName = (action as? OpenAppAction)?.packageName,
                    displays = displays,
                )
            }
            reply.write(response)
            return
        }

        settleAfterAction(action)
        // A successful action may intentionally navigate to another activity,
        // system surface, or package. Capture what is actually on screen and
        // let the model decide what the new observation means.
        // An app-layout change can retire the target display while the open
        // action is executing. Resolve the post-action session again so the
        // response observes the replacement generation instead of the stale
        // pre-open session.
        val postSession = taskSessionKey?.let { key ->
            taskDisplayBackend?.current(key)
        } ?: target?.session
        when (val captured = captureWithRetry(
            expectedPackageName = null,
            guardRegions = emptyList(),
            taskSessionKey = postSession?.sessionKey ?: taskSessionKey,
            displayId = postSession?.displayId,
            expectedDisplayRef = postSession?.let { taskDisplayReference(it.sessionKey, it.displayId) },
        )) {
            is ObservationCaptureResult.Failed -> {
                reply.write(
                    failedActionCompletion(
                        requestId = requestId,
                        action = wireActionName(action),
                        code = if (captured.code == BridgeErrorCodes.OBSERVATION_FAILED) BridgeErrorCodes.POST_OBSERVATION_FAILED else captured.code,
                        message = "The action may have run, but the phone could not produce a post-action observation: ${captured.message}",
                        outcome = "unknown",
                        executed = "unknown",
                    ),
                )
            }

            is ObservationCaptureResult.Succeeded -> {
                val initialPointer = if (action is OpenAppAction) {
                    coordinator.publishCalibrationPointerEvent(captured.snapshot)
                } else {
                    null
                }
                remember(captured.snapshot)
                val response = JSONObject()
                    .put("type", "completed")
                    .put("requestId", requestId)
                    .put("ok", true)
                    .put("action", wireActionName(action))
                    .put("message", result.resultMessage())
                    .put("observation", bridgeJson.snapshotJson(captured.snapshot))
                    .put("screenshotBase64", base64.encode(captured.screenshot))
                    .put("screenshotMimeType", "image/png")
                initialPointer?.let { pointer ->
                    response.put(
                        "initialPointer",
                        JSONObject()
                            .put("x", pointer.x)
                            .put("y", pointer.y),
                    )
                }
                bridgeJson.addBeforeDebug(
                    response,
                    observation,
                    result.beforeScreenshotOrNull(),
                )
                reply.write(response)
            }
        }
    }

    private suspend fun executeSequence(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val request = try {
            parseSequenceRequest(json)
        } catch (error: InvalidSequencePayloadException) {
            reply.write(bridgeJson.invalidSequenceResponse(requestId, json, error))
            return
        }
        val observation = synchronized(observations) {
            observations[request.observationId]
        }
        if (observation == null) {
            reply.write(
                bridgeJson.sequenceResultResponse(
                    requestId,
                    unstartedSequenceFailure(
                        actions = request.actions,
                        code = BridgeErrorCodes.OBSERVATION_MISSING,
                        message = "The supplied observationId is missing or expired; observe the phone before retrying.",
                    ),
                ),
            )
            return
        }
        val runSessionKey = coordinator.activeSessionId()
        if (taskDisplayRequiredProvider() && runSessionKey == null) {
            reply.write(
                bridgeJson.sequenceResultResponse(
                    requestId,
                    unstartedSequenceFailure(
                        actions = request.actions,
                        code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
                        message = "No active task display is available; the physical display was not touched.",
                    ),
                ),
            )
            return
        }
        if (!coordinator.awaitPhoneAccessForTool()) {
            reply.write(
                bridgeJson.sequenceResultResponse(
                    requestId,
                    unstartedSequenceFailure(
                        actions = request.actions,
                        code = BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE,
                        message = "Phone access is no longer available; the sequence was not executed.",
                    ),
                ),
            )
            return
        }
        val target = if (taskDisplayRequiredProvider() || request.displayRef != null || observation.taskSessionKey != null) {
            when (val resolution = resolveDisplayTarget(
                displayRef = request.displayRef,
                fallbackDisplayId = observation.displayId,
                fallbackDisplayRef = observation.taskSessionKey?.let { taskSessionKey ->
                    taskDisplayReference(taskSessionKey, observation.displayId)
                },
            )) {
                is TaskDisplayResolution.Ready -> resolution.target
                is TaskDisplayResolution.Unavailable -> {
                    reply.write(
                        bridgeJson.sequenceResultResponse(
                            requestId,
                            unstartedSequenceFailure(
                                actions = request.actions,
                                code = resolution.code,
                                message = resolution.message,
                            ),
                        ),
                    )
                    return
                }
            }
        } else {
            null
        }
        if (target != null &&
            (observation.taskSessionKey != target.session.sessionKey ||
                observation.displayId != target.session.displayId)
        ) {
            reply.write(
                bridgeJson.sequenceResultResponse(
                    requestId,
                    unstartedSequenceFailure(
                        actions = request.actions,
                        code = BridgeErrorCodes.DISPLAY_CHANGED,
                        message = "The observation belongs to a different task display; no input was sent.",
                    ),
                ),
            )
            return
        }

        val result = SequenceExecutor(
            executeAction = { action, baseline ->
                coordinator.executeAction(
                    action = action,
                    observation = baseline,
                    toolName = ToolNames.EXECUTE_SEQUENCE,
                    targetDisplay = target?.session,
                )
            },
            captureAfterAction = { guardRegions ->
                captureWithRetry(
                    expectedPackageName = null,
                    guardRegions = guardRegions,
                    taskSessionKey = target?.session?.sessionKey ?: runSessionKey,
                    displayId = target?.session?.displayId,
                    expectedDisplayRef = target?.displayRef,
                )
            },
            rememberObservation = ::remember,
            settleAfterAction = ::settleAfterAction,
        ).execute(observation, request.actions)
        reply.write(bridgeJson.sequenceResultResponse(requestId, result, observation))
    }

    private suspend fun settleAfterAction(action: PhoneAction) {
        if (action is OpenAppAction) {
            delay(OPEN_SETTLE_DELAY_MS)
        } else if (action !is WaitAction) {
            delay(POST_ACTION_SETTLE_DELAY_MS)
        }
    }

    private fun stopSession(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val reason = json.optString("reason", "Stopped by the desktop assistant.")
            .trim()
            .ifBlank { "Stopped by the desktop assistant." }
            .take(MAX_TEXT_CHARS)
        val stopped = coordinator.stop(reason)
        platform.removeAttentionNotification()
        platform.reconcileServiceLifetime()
        reply.write(
            JSONObject()
                .put("type", "stopped")
                .put("requestId", requestId)
                .put("ok", true)
                .put("wasActive", stopped)
                .put("message", reason),
        )
    }

    private fun remember(snapshot: ObservationSnapshot) {
        synchronized(observations) {
            observations[snapshot.id] = snapshot
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
        val afterOpen = captureWithRetry(
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
        val afterTap = captureWithRetry(null, emptyList(), coordinator.activeSessionId())
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

    private suspend fun captureWithRetry(
        expectedPackageName: String?,
        guardRegions: List<GuardRegion>,
        taskSessionKey: String? = coordinator.activeSessionId(),
        displayId: Int? = null,
        expectedDisplayRef: String? = null,
    ): ObservationCaptureResult {
        if (taskDisplayRequiredProvider() && taskSessionKey == null) {
            return ObservationCaptureResult.Failed(
                message = "No active task display is available; refusing to use the physical display.",
                code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
            )
        }
        if (!coordinator.awaitPhoneAccessForTool()) {
            return ObservationCaptureResult.Failed(
                message = "Phone access is no longer available; the observation was not captured.",
                code = BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE,
            )
        }
        var last: ObservationCaptureResult = ObservationCaptureResult.Failed("No capture attempted.")
        repeat(CAPTURE_ATTEMPTS) {
            last = observationProvider.capture(
                expectedPackageName = expectedPackageName,
                guardRegions = guardRegions,
                taskSessionKey = taskSessionKey,
                displayId = displayId,
                expectedDisplayRef = expectedDisplayRef,
            )
            if (last is ObservationCaptureResult.Succeeded) return last
            delay(CAPTURE_RETRY_DELAY_MS)
        }
        return last
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

    private fun observationFailureCode(message: String): String =
        if (message.contains("Wireless Debugging", ignoreCase = true) ||
            message.contains("DHD could not execute", ignoreCase = true)
        ) {
            BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE
        } else {
            BridgeErrorCodes.OBSERVATION_FAILED
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
        const val TAG = "PhoneControlBridge"
        const val LAN_BIND_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 8765
        const val PAIRING_DISCOVERY_PORT = 8766
        const val PAIRING_PROTOCOL_VERSION = 1
        const val PAIRING_APPROVAL_TIMEOUT_MS = 60_000L
        const val DISCOVERY_NONCE_TTL_MS = 90_000L
        const val MAX_DISCOVERY_NONCES = 32
        const val COMPLETED_PAIRING_RESPONSE_TTL_MS = 10_000L
        const val MAX_COMPLETED_PAIRING_RESPONSES = 16
        const val MAX_DESKTOP_NAME_CHARS = 80
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
