package com.phonecontrol.assistant.bridge

import android.content.Context
import com.phonecontrol.assistant.apps.InstalledUserApp
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
import com.phonecontrol.assistant.execution.ForegroundAppResult
import com.phonecontrol.assistant.execution.ObservationCaptureResult
import com.phonecontrol.assistant.execution.PhoneObservationSource
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
    constructor(
        context: Context,
        coordinator: SessionCoordinator,
        observationProvider: PhoneObservationSource,
        allowedPackagesProvider: () -> Set<String>,
        port: Int = DEFAULT_PORT,
        fullAccessProvider: () -> Boolean = { false },
        taskDisplayRequiredProvider: () -> Boolean = { false },
        taskDisplayBackend: TaskDisplayBackend? = null,
    ) : this(
        platform = AndroidBridgePlatform(context, PREFERENCES_NAME),
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

    val authenticationToken: String = platform.storedString(KEY_AUTH_TOKEN)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: newUuid().toString().replace("-", "").also { token ->
            platform.storeString(KEY_AUTH_TOKEN, token)
        }
    val deviceId: String = platform.storedString(KEY_DEVICE_ID)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: newUuid().toString().also { id ->
            platform.storeString(KEY_DEVICE_ID, id)
        }
    val listeningPort: Int = port
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pairingSocket: DatagramSocket? = null
    @Volatile private var started = false
  @Volatile private var lastCompanionSeenElapsedMs: Long = 0L
  private val _companionConnected = MutableStateFlow(false)
  private val pairingStateLock = Any()
    private val discoveryNonces = LinkedHashMap<String, Long>()
  private val completedPairingResponses = LinkedHashMap<String, CompletedCompanionPairingResponse>()
  @Volatile private var pendingCompanionPairingRequest: PendingCompanionPairingRequest? = null
  private val _pendingCompanionPairing = MutableStateFlow<PendingCompanionPairing?>(null)
  private val codexWarmupRequested = AtomicBoolean(false)
    private val phoneActionMutex = Mutex()
    private val overlayVisibilityGate
        get() = platform.overlayVisibilityGate()
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

    val companionConnected: StateFlow<Boolean> = _companionConnected.asStateFlow()

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
        scope.launch { monitorCompanionPresence() }
    }

    fun stop() {
        started = false
        serverSocket?.close()
        serverSocket = null
        pairingSocket?.close()
        pairingSocket = null
        clearPendingCompanionPairing()
        lastCompanionSeenElapsedMs = 0L
        _companionConnected.value = false
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun monitorCompanionPresence() {
        while (currentCoroutineContext().isActive) {
            val lastSeen = lastCompanionSeenElapsedMs
            val connected = lastSeen > 0L &&
                clock.elapsedMillis() - lastSeen <= COMPANION_PRESENCE_TIMEOUT_MS
            if (_companionConnected.value != connected) {
                _companionConnected.value = connected
            }
            delay(COMPANION_PRESENCE_CHECK_INTERVAL_MS)
        }
    }

    private fun markCompanionSeen() {
        lastCompanionSeenElapsedMs = clock.elapsedMillis()
        _companionConnected.value = true
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
        codexWarmupRequested.set(true)
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            handleRequestLine(reader.readLine(), socket.inetAddress, writer)
        }
    }

    internal suspend fun handleRequestLine(
        line: String?,
        peerAddress: InetAddress,
        writer: BufferedWriter,
    ) {
        if (line == null) {
            write(writer, errorResponse(null, "The bridge received an empty request."))
            return
        }
        if (line.length > MAX_REQUEST_CHARS) {
            write(writer, errorResponse(null, "The bridge request is too large."))
            return
        }
        val json = try {
            JSONObject(line)
        } catch (error: IllegalArgumentException) {
            write(writer, errorResponse(null, error.message ?: "Invalid bridge request."))
            return
        } catch (error: JSONException) {
            write(writer, errorResponse(null, "The bridge request must be valid JSON."))
            return
        }
        val requestId = json.optString("requestId").ifBlank { newUuid().toString() }

        if (!isAuthorized(peerAddress, json)) {
            write(
                writer,
                errorResponse(requestId, "The phone bridge rejected this network connection. Pair the desktop companion in DHD settings.")
                    .put("code", "AUTH_REQUIRED"),
            )
            return
        }

        // Dashboard status checks are read-only health probes and must not
        // keep the worker's liveness lease alive after the worker stops.
        // Worker traffic still refreshes presence independently of the
        // current task or Codex polling phase.
        val requestType = json.optString("type")
        if (requestType != "status" && requestType != "companion_disconnected") {
            markCompanionSeen()
        }
        write(
            writer,
            JSONObject()
                .put("type", "accepted")
                .put("requestId", requestId)
                .put("message", "${requestType.ifBlank { "bridge" }} accepted by the phone."),
        )
        try {
            when (requestType) {
                "demo_run" -> phoneActionMutex.withLock { runDemo(parseRequest(json), writer) }
                "start_session" -> startSession(requestId, json, writer)
                "status" -> status(requestId, writer)
                "heartbeat" -> heartbeat(requestId, writer)
                "companion_disconnected" -> companionDisconnected(requestId, writer)
                "pending_request" -> pendingRequest(requestId, writer)
                "claim_request" -> claimRequest(requestId, json, writer)
                "pending_steer" -> pendingSteer(requestId, json, writer)
                "claim_steer" -> claimSteer(requestId, json, writer)
                "release_steer" -> releaseSteer(requestId, json, writer)
                "complete_steer" -> completeSteer(requestId, json, writer)
                "bind_codex_thread" -> bindCodexThread(requestId, json, writer)
                "release_request" -> releaseRequest(requestId, json, writer)
                "stream_agent_message" -> streamAgentMessage(requestId, json, writer)
                "complete_session" -> completeSession(requestId, json, writer)
                "fail_session" -> failSession(requestId, json, writer)
                "allowed_apps" -> withDhdTool(json, ToolNames.LIST_ALLOWED_APPS) {
                    allowedApps(requestId, json, writer)
                }
                "browse_apps" -> withDhdTool(json, ToolNames.BROWSE_APP) {
                    browseApps(requestId, json, writer)
                }
                "set_app_display_layout" -> withDhdTool(json, ToolNames.SET_APP_DISPLAY_LAYOUT) {
                    setAppDisplayLayout(requestId, json, writer)
                }
                "list_displays" -> listDisplays(requestId, writer)
                "close_display" -> closeDisplay(requestId, json, writer)
                "foreground_app" -> withDhdTool(json, ToolNames.FOREGROUND_APP) {
                    foregroundApp(requestId, json, writer)
                }
                "observe" -> withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.OBSERVE,
                ) {
                    observe(requestId, json, writer)
                }
                "execute_action" -> withDhdTool(
                    json = json,
                    fallbackToolName = fallbackActionToolName(json),
                ) {
                    phoneActionMutex.withLock { executeAction(requestId, json, writer) }
                }
                "execute_sequence" -> withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.EXECUTE_SEQUENCE,
                ) {
                    phoneActionMutex.withLock { executeSequence(requestId, json, writer) }
                }
                "request_attention" -> withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.REQUEST_ATTENTION,
                    terminalStatus = DhdToolCallStatus.ATTENTION,
                ) {
                    requestAttention(requestId, json, writer)
                }
                "stop_session" -> stopSession(requestId, json, writer)
                else -> write(writer, errorResponse(requestId, "Unsupported bridge request type."))
            }
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            platform.logError(TAG, "Bridge request failed", error)
            write(writer, errorResponse(requestId, "The phone bridge failed: $message"))
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
        writer: BufferedWriter,
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
            write(writer, errorResponse(requestId, "The phone already has an active session."))
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
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val state = coordinator.state.value
        val response = JSONObject()
            .put("type", "status")
            .put("requestId", requestId)
            .put("ok", true)
            .put("state", stateName(state))
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
        write(writer, response)
    }

    private fun heartbeat(
        requestId: String,
        writer: BufferedWriter,
    ) {
        // Keep the phone-side companion lease independent from pending work,
        // Codex startup, or a long-running task request.
        markCompanionSeen()
        write(
            writer,
            JSONObject()
                .put("type", "heartbeat")
                .put("requestId", requestId)
                .put("ok", true)
                .put("message", "Desktop companion heartbeat acknowledged."),
        )
    }

    private fun companionDisconnected(
        requestId: String,
        writer: BufferedWriter,
    ) {
        lastCompanionSeenElapsedMs = 0L
        _companionConnected.value = false
        write(
            writer,
            JSONObject()
                .put("type", "companion_disconnected")
                .put("requestId", requestId)
                .put("ok", true)
                .put("message", "Desktop companion presence released."),
        )
    }

    private fun pendingRequest(
        requestId: String,
        writer: BufferedWriter,
    ) {
        // The companion's normal pending-request poll doubles as its
        // heartbeat. The phone uses this to render the existing recovery card
        // without exposing the request or requiring another protocol.
        markCompanionSeen()
        val pending = coordinator.pendingRequest()
        val response = JSONObject()
            .put("type", "pending_request")
            .put("requestId", requestId)
            .put("ok", true)
            .put("available", pending != null)
            .put("warmupRequested", codexWarmupRequested.getAndSet(false))
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
        write(writer, response)
    }

    private fun claimRequest(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val expectedSessionId = json.optString("sessionId").trim().ifBlank { null }
        val claimed = coordinator.claimRequest(expectedSessionId)
        if (claimed == null) {
            write(
                writer,
                errorResponse(requestId, "No unclaimed running phone request matched the supplied sessionId.")
                    .put("code", "REQUEST_NOT_AVAILABLE"),
            )
            return
        }
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        markCompanionSeen()
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
        write(writer, response)
    }

    private fun claimSteer(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val claimed = coordinator.claimSteer(sessionId, steerId)
        if (claimed == null) {
            write(
                writer,
                errorResponse(requestId, "No unclaimed steer matched the supplied session and steer id.")
                    .put("code", "STEER_NOT_AVAILABLE"),
            )
            return
        }
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val released = coordinator.releaseSteer(sessionId, steerId)
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val completed = coordinator.completeSteer(sessionId, steerId)
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        val released = coordinator.releaseRequest(sessionId)
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val conversationId = json.optString("conversationId").trim()
        val codexThreadId = json.optString("codexThreadId").trim()
        require(conversationId.isNotEmpty()) { "conversationId is required." }
        require(codexThreadId.isNotEmpty()) { "codexThreadId is required." }
        val bound = coordinator.bindCodexThread(conversationId, codexThreadId)
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        if (coordinator.state.value.sessionIdOrNull != sessionId) {
            write(
                writer,
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", "SESSION_NOT_RUNNING"),
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
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        markCompanionSeen()
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
            write(
                writer,
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", "SESSION_NOT_RUNNING"),
            )
            return
        }
        val streamed = coordinator.streamAgentMessage(sessionId, messageId, text)
        write(
            writer,
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
        writer: BufferedWriter,
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
            write(
                writer,
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", "SESSION_NOT_RUNNING"),
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
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val reason = json.optString("reason")
            .trim()
            .ifBlank { "The phone assistant needs your attention." }
            .take(MAX_TEXT_CHARS)
        val sessionId = coordinator.activeSessionId()
        if (sessionId == null) {
            write(
                writer,
                errorResponse(requestId, "The phone assistant has no active session to interrupt.")
                    .put("code", "SESSION_NOT_RUNNING"),
            )
            return
        }
        val requestedDisplayRef = optionalDisplayRef(json)
        val target = if (taskDisplayRequiredProvider() || requestedDisplayRef != null) {
            when (val resolution = resolveDisplayTarget(displayRef = requestedDisplayRef)) {
                is TaskDisplayResolution.Ready -> resolution.target
                is TaskDisplayResolution.Unavailable -> {
                    write(
                        writer,
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
            write(
                writer,
                errorResponse(requestId, "The phone assistant is already waiting for the user's attention.")
                    .put("code", "ATTENTION_ALREADY_PENDING"),
            )
            return
        }
        platform.showAttentionNotification(reason, coordinator.state.value.conversationIdOrNull)
        when (attention.await()) {
            AttentionResolution.Cancelled -> write(
                writer,
                JSONObject()
                    .put("type", "attention_cancelled")
                    .put("requestId", requestId)
                    .put("ok", false)
                    .put("sessionId", sessionId)
                    .put("code", "SESSION_STOPPED")
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
                            .put("observation", snapshotJson(captured.snapshot))
                            .put("screenshotBase64", base64.encode(captured.screenshot))
                            .put("screenshotMimeType", "image/png")
                    }
                }
                write(writer, response)
            }
        }
    }

    private fun allowedApps(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
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
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val query = json.optString("query").trim()
        if (query.isEmpty() || query.length > MAX_APP_QUERY_CHARS) {
            write(
                writer,
                errorResponse(requestId, "App search requires a query between 1 and $MAX_APP_QUERY_CHARS characters.")
                    .put("code", "INVALID_APP_QUERY"),
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
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val packageName = json.optString("packageName").trim()
        if (!PACKAGE_PATTERN.matches(packageName)) {
            write(
                writer,
                errorResponse(requestId, "packageName is not a valid Android package name.")
                    .put("code", "INVALID_PACKAGE"),
            )
            return
        }

        val layout = json.optString("layout").trim().lowercase(Locale.ROOT)
        val enabled = when (layout) {
            "full_size" -> true
            "standard" -> false
            else -> {
                write(
                    writer,
                    errorResponse(requestId, "layout must be either full_size or standard.")
                        .put("code", "INVALID_APP_DISPLAY_LAYOUT"),
                )
                return
            }
        }

        val app = platform.launchableApps()
            .firstOrNull { it.packageName == packageName }
        if (app == null) {
            write(
                writer,
                errorResponse(requestId, "No launchable app matches packageName=$packageName.")
                    .put("code", "APP_NOT_FOUND"),
            )
            return
        }

        val fullAccess = fullAccessProvider()
        val allowed = fullAccess || packageName in allowedPackagesProvider()
        if (!allowed) {
            write(
                writer,
                errorResponse(requestId, "The app is not allowed for the current DHD access mode.")
                    .put("code", "APP_NOT_ALLOWED"),
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
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val backend = taskDisplayBackend
        if (backend == null) {
            write(
                writer,
                errorResponse(requestId, "The task display registry is unavailable.")
                    .put("code", "TASK_DISPLAY_UNAVAILABLE"),
            )
            return
        }
        val displays = currentDisplayJson(backend)
        write(
            writer,
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
        writer: BufferedWriter,
    ) {
        val backend = taskDisplayBackend
        if (backend == null) {
            write(
                writer,
                errorResponse(requestId, "The task display registry is unavailable.")
                    .put("code", "TASK_DISPLAY_UNAVAILABLE"),
            )
            return
        }
        val displayRef = try {
            optionalDisplayRef(json)
        } catch (error: IllegalArgumentException) {
            write(writer, errorResponse(requestId, error.message ?: "displayRef is invalid.").put("code", "INVALID_DISPLAY_REF"))
            return
        }
        if (displayRef == null) {
            write(
                writer,
                errorResponse(requestId, "displayRef is required to close a display safely. Call dhd_list_displays first and use the matching displayRef.")
                    .put("code", "DISPLAY_REFERENCE_REQUIRED"),
            )
            return
        }
        backend.activeDisplaySessions()
        val record = backend.displayRecords.value.firstOrNull { it.displayRef == displayRef }
        if (record == null) {
            write(
                writer,
                errorResponse(requestId, "No task display matches the supplied displayRef. Call dhd_list_displays to see the available displays.")
                    .put("code", "DISPLAY_NOT_FOUND"),
            )
            return
        }
        val activeRunKey = coordinator.activeSessionId()
        if (activeRunKey != null && backend.isDisplayClaimedByRun(record.displayId, activeRunKey)) {
            write(
                writer,
                errorResponse(requestId, "The selected task display is being used by an active DHD run. Stop the active run first, then close the display.")
                    .put("code", "DISPLAY_IN_USE"),
            )
            return
        }
        when (val result = backend.closeTaskDisplay(record.displayId, displayRef)) {
            is TaskDisplayCloseResult.Rejected -> write(
                writer,
                errorResponse(requestId, result.message).put("code", result.code),
            )

            is TaskDisplayCloseResult.Closed -> write(
                writer,
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

    private fun optionalDisplayRef(json: JSONObject): String? {
        val ref = json.optString("displayRef").trim().takeIf(String::isNotEmpty) ?: return null
        require(DISPLAY_REF_PATTERN.matches(ref)) {
            "displayRef must match dsp_ followed by 14 lowercase hexadecimal characters."
        }
        return ref
    }

    private suspend fun resolveDisplayTarget(
        displayRef: String?,
        fallbackDisplayId: Int? = null,
        fallbackDisplayRef: String? = null,
        claimForRun: Boolean = true,
    ): TaskDisplayResolution {
        val backend = taskDisplayBackend
            ?: return TaskDisplayResolution.Unavailable(
                code = "TASK_DISPLAY_UNAVAILABLE",
                message = "The task display registry is unavailable; call dhd_open_app to create a task display.",
            )
        val runSessionKey = coordinator.activeSessionId()
        if (claimForRun && taskDisplayRequiredProvider() && runSessionKey == null) {
            return TaskDisplayResolution.Unavailable(
                code = "TASK_DISPLAY_UNAVAILABLE",
                message = "No active task display run is available. Call dhd_open_app from an active DHD task first.",
            )
        }
        val selectedDisplayRef = displayRef ?: fallbackDisplayRef
        return if (selectedDisplayRef != null) {
            backend.activeDisplaySessions()
            val record = backend.displayRecords.value.firstOrNull { it.displayRef == selectedDisplayRef }
                ?: return TaskDisplayResolution.Unavailable(
                    code = "DISPLAY_NOT_FOUND",
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
        writer: BufferedWriter,
    ) {
        val purpose = json.optString("purpose").trim().take(MAX_TEXT_CHARS)
            .ifBlank { "Observing current screen" }
        coordinator.recordPurpose(
            purpose = purpose,
            targetDescription = json.optString("targetDescription").trim().take(MAX_TEXT_CHARS).ifBlank { null },
            toolName = ToolNames.OBSERVE,
        )
        if (!coordinator.awaitPhoneAccessForTool()) {
            write(
                writer,
                errorResponse(requestId, "Phone access is no longer available; DHD could not observe the phone.")
                    .put("code", "DEVELOPER_MODE_UNAVAILABLE"),
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
                    write(writer, errorResponse(requestId, resolution.message).put("code", resolution.code))
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
            is ObservationCaptureResult.Failed -> write(
                writer,
                errorResponse(requestId, captured.message).put("code", captured.code),
            )
            is ObservationCaptureResult.Succeeded -> {
                remember(captured.snapshot)
                writeObservation(writer, requestId, captured.snapshot, captured.screenshot)
            }
        }
    }

    private suspend fun foregroundApp(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        coordinator.recordPurpose(
            purpose = "Checking foreground app",
            toolName = ToolNames.FOREGROUND_APP,
        )
        if (!coordinator.awaitPhoneAccessForTool()) {
            write(
                writer,
                errorResponse(requestId, "Phone access is no longer available; DHD could not check the phone.")
                    .put("code", "DEVELOPER_MODE_UNAVAILABLE"),
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
                    write(writer, errorResponse(requestId, resolution.message).put("code", resolution.code))
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
            is ForegroundAppResult.Failed -> write(
                writer,
                errorResponse(requestId, result.message).put("code", result.code),
            )

            is ForegroundAppResult.Succeeded -> write(
                writer,
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
        writer: BufferedWriter,
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
            write(
                writer,
                JSONObject()
                    .put("type", "completed")
                    .put("requestId", requestId)
                    .put("ok", false)
                    .put("action", wireActionName(parsedAction))
                    .put("outcome", "failed")
                    .put("executed", false)
                    .put("code", "TASK_DISPLAY_UNAVAILABLE")
                    .put("message", "No active task display is available; the physical display was not touched."),
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
                    targetResolution.code == "TASK_DISPLAY_UNAVAILABLE"
                ) {
                    null
                } else {
                    write(
                        writer,
                        JSONObject()
                            .put("type", "completed")
                            .put("requestId", requestId)
                            .put("ok", false)
                            .put("action", wireActionName(parsedAction))
                            .put("outcome", "failed")
                            .put("executed", false)
                            .put("code", targetResolution.code)
                            .put("message", targetResolution.message),
                    )
                    return
                }
            }
        }
        if (target != null && suppliedObservation != null &&
            (suppliedObservation.displayId != target.session.displayId ||
                suppliedObservation.taskSessionKey != target.session.sessionKey)
        ) {
            write(
                writer,
                JSONObject()
                    .put("type", "completed")
                    .put("requestId", requestId)
                    .put("ok", false)
                    .put("action", wireActionName(parsedAction))
                    .put("outcome", "failed")
                    .put("executed", false)
                    .put("code", "DISPLAY_CHANGED")
                    .put("message", "The supplied observation belongs to a different task display; call dhd_observe with the selected display before retrying."),
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
                    write(
                        writer,
                        JSONObject()
                            .put("type", "completed")
                            .put("requestId", requestId)
                            .put("ok", false)
                            .put("action", "open_app")
                            .put("outcome", "failed")
                            .put("executed", false)
                            .put("code", "OBSERVATION_FAILED")
                            .put(
                                "message",
                                "Could not establish a launch baseline; the app was not opened: ${captured.message}",
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
            write(
                writer,
                JSONObject()
                    .put("type", "completed")
                    .put("requestId", requestId)
                    .put("ok", false)
                    .put("action", wireActionName(parsedAction))
                    .put("outcome", "failed")
                    .put("executed", false)
                    .put("code", "OBSERVATION_MISSING")
                    .put("message", "The supplied observationId is missing or expired; observe the phone before retrying."),
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
        writeActionResult(writer, requestId, wireActionName(action), result)
        if (!result.isSuccessful()) {
            val failureCode = result.failureCode()
            val response = JSONObject()
                .put("type", "completed")
                .put("requestId", requestId)
                .put("ok", false)
                .put("action", wireActionName(action))
                .put("message", result.failureMessage())
            failureCode?.let { response.put("code", it) }
            addBeforeDebug(
                response,
                observation,
                result.beforeScreenshotOrNull(),
            )
            result.staleDetailsOrNull()?.let { details -> addStaleDiagnostics(response, details) }
            if (failureCode == "DISPLAY_LIMIT_REACHED") {
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
            write(writer, response)
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
                write(
                    writer,
                    JSONObject()
                        .put("type", "completed")
                        .put("requestId", requestId)
                        .put("ok", false)
                        .put("action", wireActionName(action))
                        .put("outcome", "unknown")
                        .put("executed", "unknown")
                        .put("code", if (captured.code == "OBSERVATION_FAILED") "POST_OBSERVATION_FAILED" else captured.code)
                        .put("message", "The action may have run, but the phone could not produce a post-action observation: ${captured.message}"),
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
                    .put("message", result.successMessage())
                    .put("observation", snapshotJson(captured.snapshot))
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
                addBeforeDebug(
                    response,
                    observation,
                    result.beforeScreenshotOrNull(),
                )
                write(writer, response)
            }
        }
    }

    private suspend fun executeSequence(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val request = try {
            parseSequenceRequest(json)
        } catch (error: InvalidSequencePayloadException) {
            writeInvalidSequenceResult(writer, requestId, json, error)
            return
        }
        val observation = synchronized(observations) {
            observations[request.observationId]
        }
        if (observation == null) {
            val firstAction = request.actions.first()
            val failure = SequenceStepResult(
                index = 0,
                action = wireActionName(firstAction),
                status = SequenceStepResult.Status.FAILED,
                message = "The supplied observationId is missing or expired; observe the phone before retrying.",
                code = "OBSERVATION_MISSING",
                outcome = "failed",
                executed = false,
            )
            writeSequenceResult(
                writer,
                requestId,
                SequenceExecutionResult(
                    requestedSteps = request.actions.size,
                    steps = listOf(failure),
                    failure = failure,
                ),
            )
            return
        }
        val runSessionKey = coordinator.activeSessionId()
        if (taskDisplayRequiredProvider() && runSessionKey == null) {
            val firstAction = request.actions.first()
            val failure = SequenceStepResult(
                index = 0,
                action = wireActionName(firstAction),
                status = SequenceStepResult.Status.FAILED,
                message = "No active task display is available; the physical display was not touched.",
                code = "TASK_DISPLAY_UNAVAILABLE",
                outcome = "failed",
                executed = false,
            )
            writeSequenceResult(
                writer,
                requestId,
                SequenceExecutionResult(
                    requestedSteps = request.actions.size,
                    steps = listOf(failure),
                    failure = failure,
                ),
            )
            return
        }
        if (!coordinator.awaitPhoneAccessForTool()) {
            val firstAction = request.actions.first()
            val failure = SequenceStepResult(
                index = 0,
                action = wireActionName(firstAction),
                status = SequenceStepResult.Status.FAILED,
                message = "Phone access is no longer available; the sequence was not executed.",
                code = "DEVELOPER_MODE_UNAVAILABLE",
                outcome = "failed",
                executed = false,
            )
            writeSequenceResult(
                writer,
                requestId,
                SequenceExecutionResult(
                    requestedSteps = request.actions.size,
                    steps = listOf(failure),
                    failure = failure,
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
                    val firstAction = request.actions.first()
                    val failure = SequenceStepResult(
                        index = 0,
                        action = wireActionName(firstAction),
                        status = SequenceStepResult.Status.FAILED,
                        message = resolution.message,
                        code = resolution.code,
                        outcome = "failed",
                        executed = false,
                    )
                    writeSequenceResult(
                        writer,
                        requestId,
                        SequenceExecutionResult(
                            requestedSteps = request.actions.size,
                            steps = listOf(failure),
                            failure = failure,
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
            val firstAction = request.actions.first()
            val failure = SequenceStepResult(
                index = 0,
                action = wireActionName(firstAction),
                status = SequenceStepResult.Status.FAILED,
                message = "The observation belongs to a different task display; no input was sent.",
                code = "DISPLAY_CHANGED",
                outcome = "failed",
                executed = false,
            )
            writeSequenceResult(
                writer,
                requestId,
                SequenceExecutionResult(
                    requestedSteps = request.actions.size,
                    steps = listOf(failure),
                    failure = failure,
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
        writeSequenceResult(writer, requestId, result, observation)
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
        writer: BufferedWriter,
    ) {
        val reason = json.optString("reason", "Stopped by the desktop assistant.")
            .trim()
            .ifBlank { "Stopped by the desktop assistant." }
            .take(MAX_TEXT_CHARS)
        val stopped = coordinator.stop(reason)
        platform.removeAttentionNotification()
        platform.reconcileServiceLifetime()
        write(
            writer,
            JSONObject()
                .put("type", "stopped")
                .put("requestId", requestId)
                .put("ok", true)
                .put("wasActive", stopped)
                .put("message", reason),
        )
    }

    internal fun parseSequenceRequest(json: JSONObject): SequenceRequest {
        val observationId = json.optString("observationId").trim()
        if (observationId.isEmpty() || observationId.length > MAX_TEXT_CHARS) {
            throw InvalidSequencePayloadException(
                index = null,
                message = "observationId must be 1-$MAX_TEXT_CHARS characters.",
            )
        }
        val actionsJson = json.optJSONArray("actions")
            ?: throw InvalidSequencePayloadException(null, "actions must be an array.")
        if (actionsJson.length() !in 1..MAX_SEQUENCE_ACTIONS) {
            throw InvalidSequencePayloadException(
                index = null,
                message = "A sequence must contain between 1 and $MAX_SEQUENCE_ACTIONS actions.",
            )
        }
        val actions = buildList(actionsJson.length()) {
            for (index in 0 until actionsJson.length()) {
                val actionJson = actionsJson.optJSONObject(index)
                    ?: throw InvalidSequencePayloadException(index, "Sequence action $index must be an object.")
                val metadata = actionJson.optJSONObject("metadata")
                if (metadata == null) {
                    throw InvalidSequencePayloadException(index, "Sequence action $index must include metadata.")
                }
                if (metadata.has("observationId")) {
                    throw InvalidSequencePayloadException(
                        index,
                        "Sequence action $index receives observationId from the phone and must not provide one.",
                    )
                }
                val action = try {
                    parsePhoneAction(actionJson)
                } catch (error: Exception) {
                    throw InvalidSequencePayloadException(
                        index,
                        "Sequence action $index is invalid: ${error.message ?: "invalid action payload"}",
                    )
                }
                if (action is OpenAppAction) {
                    throw InvalidSequencePayloadException(
                        index,
                        "dhd_execute_sequence does not support open_app; use dhd_open_app first.",
                    )
                }
                add(action)
            }
        }
        return SequenceRequest(
            observationId = observationId,
            actions = actions,
            displayRef = optionalDisplayRef(json),
        )
    }

    private fun writeInvalidSequenceResult(
        writer: BufferedWriter,
        requestId: String,
        json: JSONObject,
        error: InvalidSequencePayloadException,
    ) {
        val actions = json.optJSONArray("actions")
        val response = JSONObject()
            .put("type", "completed")
            .put("requestId", requestId)
            .put("ok", false)
            .put("action", "sequence")
            .put("requestedSteps", actions?.length() ?: 0)
            .put("completedSteps", 0)
            .put("message", error.message ?: "The sequence payload is invalid.")
            .put("code", "INVALID_PAYLOAD")
            .put("outcome", "failed")
            .put("executed", false)
        val steps = JSONArray()
        error.index?.let { index ->
            val action = actions
                ?.optJSONObject(index)
                ?.optString("type")
                ?.trim()
                ?.ifBlank { null }
                ?: "unknown"
            steps.put(
                JSONObject()
                    .put("index", index)
                    .put("action", action)
                    .put("status", "failed")
                    .put("message", error.message ?: "The sequence action is invalid.")
                    .put("code", "INVALID_PAYLOAD")
                    .put("outcome", "failed")
                    .put("executed", false),
            )
            response.put("failedStep", index)
        }
        response.put("steps", steps)
        write(writer, response)
    }

    internal fun parsePhoneAction(json: JSONObject): PhoneAction {
        val metadata = parseMetadata(json.optJSONObject("metadata"))
        return when (json.optString("type")) {
            "open_app" -> {
                val packageName = json.optString("packageName")
                require(PACKAGE_PATTERN.matches(packageName)) { "packageName is not a valid Android package name." }
                OpenAppAction(packageName, metadata)
            }

            "tap" -> TapAction(
                x = json.getInt("x"),
                y = json.getInt("y"),
                metadata = metadata,
            )

            "type" -> TypeAction(json.getString("text"), metadata)

            "swipe" -> SwipeAction(
                startX = json.getInt("startX"),
                startY = json.getInt("startY"),
                endX = json.getInt("endX"),
                endY = json.getInt("endY"),
                durationMs = json.optLong("durationMs", 350L),
                metadata = metadata,
            )

            "back" -> BackAction(metadata)

            "keypress" -> KeypressAction(
                key = enumValue<KeypressKey>(json.getString("key")),
                metadata = metadata,
            )

            "wait" -> WaitAction(json.getLong("durationMs"), metadata)

            else -> throw IllegalArgumentException(
                "Unsupported action type. Use open_app, tap, type, swipe, back, keypress, or wait.",
            )
        }
    }

    internal fun parseMetadata(json: JSONObject?): ActionMetadata {
        require(json != null) { "action.metadata is required." }
        val purpose = json.optString("purpose").trim()
        // The companion supplies observationId for the pre-action structural
        // comparison and may supply guardRegions for strict visual checking.
        val observationId = json.optString("observationId").trim()
        val targetDescription = json.optString("targetDescription").trim()
        require(purpose.isNotEmpty() && purpose.length <= MAX_TEXT_CHARS) {
            "metadata.purpose must be 1-$MAX_TEXT_CHARS characters."
        }
        require(observationId.length <= MAX_TEXT_CHARS) {
            "metadata.observationId must be at most $MAX_TEXT_CHARS characters."
        }
        require(targetDescription.isNotEmpty() && targetDescription.length <= MAX_TEXT_CHARS) {
            "metadata.targetDescription must be 1-$MAX_TEXT_CHARS characters."
        }
        return ActionMetadata(
            purpose = purpose,
            observationId = observationId,
            targetDescription = targetDescription,
            guardRegions = parseGuardRegions(json.optJSONArray("guardRegions")),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unsupported enum value: $value")

    internal fun wireActionName(action: PhoneAction): String = when (action) {
        is OpenAppAction -> "open_app"
        is TapAction -> "tap"
        is TypeAction -> "type"
        is SwipeAction -> "swipe"
        is BackAction -> "back"
        is KeypressAction -> "keypress"
        is WaitAction -> "wait"
    }

    private fun remember(snapshot: ObservationSnapshot) {
        synchronized(observations) {
            observations[snapshot.id] = snapshot
        }
    }

    private fun addBeforeDebug(
        response: JSONObject,
        observation: ObservationSnapshot?,
        screenshot: ByteArray?,
    ) {
        if (observation == null || screenshot == null) return
        response
            .put("beforeObservation", snapshotJson(observation))
            .put("beforeScreenshotBase64", base64.encode(screenshot))
            .put("beforeScreenshotMimeType", "image/png")
    }

    /** Attach machine-readable freshness diagnostics without changing the
     * action's safe rejection semantics. */
    private fun addStaleDiagnostics(
        response: JSONObject,
        details: StaleObservationDiagnostics,
    ) {
        response
            .put("inputSent", false)
            .put("approvedObservationId", details.approvedObservationId)
        details.currentObservationId?.let { response.put("currentObservationId", it) }
        response.put(
            "reasons",
            JSONArray(details.reasons.map(::staleReasonJson)),
        )
    }

    private fun staleReasonJson(reason: StaleObservationReason): JSONObject = JSONObject()
        .put("code", reason.code.name)
        .put("approved", staleReasonValue(reason.approved))
        .put("current", staleReasonValue(reason.current))
        .also { json ->
            reason.guardRegion?.let { region ->
                json.put(
                    "guardRegion",
                    JSONObject()
                        .put("left", region.left)
                        .put("top", region.top)
                        .put("right", region.right)
                        .put("bottom", region.bottom),
                )
            }
        }

    private fun staleReasonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is ObservationSize -> JSONObject()
            .put("width", value.width)
            .put("height", value.height)
        else -> value
    }

    private fun writeObservation(
        writer: BufferedWriter,
        requestId: String,
        snapshot: ObservationSnapshot,
        screenshot: ByteArray,
    ) {
        write(
            writer,
            JSONObject()
                .put("type", "observation")
                .put("requestId", requestId)
                .put("ok", true)
                .put("observation", snapshotJson(snapshot))
                .put("screenshotBase64", base64.encode(screenshot))
                .put("screenshotMimeType", "image/png"),
        )
    }

    private fun writeSequenceResult(
        writer: BufferedWriter,
        requestId: String,
        result: SequenceExecutionResult,
        beforeObservation: ObservationSnapshot? = null,
    ) {
        val response = JSONObject()
            .put("type", "completed")
            .put("requestId", requestId)
            .put("ok", result.ok)
            .put("action", "sequence")
            .put("requestedSteps", result.requestedSteps)
            .put("completedSteps", result.completedSteps)
            .put(
                "message",
                if (result.ok) {
                    "Executed ${result.requestedSteps} typed phone actions and returned a fresh observation."
                } else {
                    result.failure?.message ?: "The phone sequence failed."
                },
            )
        val steps = JSONArray()
        result.steps.forEach { step ->
            val stepJson = JSONObject()
                .put("index", step.index)
                .put("action", step.action)
                .put("status", step.status.name.lowercase())
                .put("message", step.message)
            step.observationId?.let { stepJson.put("observationId", it) }
            step.code?.let { stepJson.put("code", it) }
            step.outcome?.let { stepJson.put("outcome", it) }
            step.executed?.let { stepJson.put("executed", it) }
            step.details?.let { addStaleDiagnostics(stepJson, it) }
            steps.put(stepJson)
        }
        response.put("steps", steps)
        result.failure?.let { failure ->
            response
                .put("failedStep", failure.index)
                .put("code", failure.code ?: "SEQUENCE_FAILED")
                .put("outcome", failure.outcome ?: "failed")
                .put("executed", failure.executed ?: "unknown")
            failure.details?.let { addStaleDiagnostics(response, it) }
        }
        result.finalObservation?.let { captured ->
            response
                .put("observation", snapshotJson(captured.snapshot))
                .put("screenshotBase64", base64.encode(captured.screenshot))
                .put("screenshotMimeType", "image/png")
            if (beforeObservation != null) {
                addBeforeDebug(response, beforeObservation, result.beforeScreenshot)
            }
        }
        write(writer, response)
    }

    private fun snapshotJson(snapshot: ObservationSnapshot): JSONObject = JSONObject()
        .put("id", snapshot.id)
        .put("packageName", snapshot.packageName)
        .put("activityName", snapshot.activityName ?: JSONObject.NULL)
        .put("rotation", snapshot.rotation)
        .put("width", snapshot.width)
        .put("height", snapshot.height)
        .put("screenshotFingerprint", snapshot.screenshotFingerprint)
        .put(
            "screenProtection",
            JSONObject()
                .put("status", snapshot.screenProtection.status.name.lowercase())
                .put("requiresUserAttention", snapshot.screenProtection.requiresUserAttention)
                .put("signals", JSONArray(snapshot.screenProtection.signals))
                .put("reason", snapshot.screenProtection.reason ?: JSONObject.NULL),
        )
        .also { json ->
            snapshot.taskSessionKey?.let { sessionKey ->
                json.put("displayRef", taskDisplayReference(sessionKey, snapshot.displayId))
            }
        }

    private fun stateName(state: SessionState): String = when (state) {
        SessionState.Idle -> "idle"
        is SessionState.Running -> "running"
        is SessionState.Paused -> "paused"
        is SessionState.Stopped -> "stopped"
        is SessionState.Completed -> "completed"
    }

    private suspend fun runDemo(request: DemoRequest, writer: BufferedWriter) {
        val startedSession = coordinator.start("Desktop Codex demo: ${request.purpose}")
        if (!startedSession) {
            write(writer, errorResponse(request.requestId, "The phone already has an active session."))
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
        writeActionResult(writer, request.requestId, "open_app", openResult)
        if (!openResult.isSuccessful()) {
            failSession(writer, request, openResult.failureMessage())
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
                failSession(writer, request, afterOpen.message)
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
        writeActionResult(writer, request.requestId, "tap", tapResult)
        if (!tapResult.isSuccessful()) {
            failSession(writer, request, tapResult.failureMessage())
            return
        }

        delay(POST_ACTION_SETTLE_DELAY_MS)
        val afterTap = captureWithRetry(null, emptyList(), coordinator.activeSessionId())
        when (afterTap) {
            is ObservationCaptureResult.Failed -> {
                failSession(writer, request, "Tap completed, but the post-action observation failed: ${afterTap.message}")
                return
            }

            is ObservationCaptureResult.Succeeded -> {
                coordinator.complete("Demo completed; the phone returned a fresh observation.")
                write(
                    writer,
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
                code = "TASK_DISPLAY_UNAVAILABLE",
            )
        }
        if (!coordinator.awaitPhoneAccessForTool()) {
            return ObservationCaptureResult.Failed(
                message = "Phone access is no longer available; the observation was not captured.",
                code = "DEVELOPER_MODE_UNAVAILABLE",
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

    private fun failSession(writer: BufferedWriter, request: DemoRequest, message: String) {
        coordinator.stop("Demo stopped: $message")
        write(writer, errorResponse(request.requestId, message))
    }

    private fun writeActionResult(
        writer: BufferedWriter,
        requestId: String,
        action: String,
        result: ActionExecutionResult,
    ) {
        val successful = result.isSuccessful()
        val response = JSONObject()
            .put("type", "action_result")
            .put("requestId", requestId)
            .put("action", action)
            .put("ok", successful)
        when (result) {
            is ActionExecutionResult.TransportFinished -> {
                when (val transportResult = result.result) {
                    is TransportResult.Succeeded -> response.put("message", transportResult.message)
                    is TransportResult.Rejected -> response
                        .put("code", transportResult.code.name)
                        .put("message", transportResult.message)
                        .also { transportResult.details?.let { details -> addStaleDiagnostics(it, details) } }
                    is TransportResult.Unsupported -> response.put("message", transportResult.message)
                }
            }

            is ActionExecutionResult.PolicyRejected -> response
                .put("code", "POLICY_REJECTED")
                .put("message", result.message)
                .also { result.details?.let { details -> addStaleDiagnostics(it, details) } }
            ActionExecutionResult.SessionNotRunning -> response
                .put("code", "SESSION_NOT_RUNNING")
                .put("message", "The phone session is no longer running.")
        }
        write(writer, response)
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

    internal fun parseGuardRegions(array: JSONArray?): List<GuardRegion> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_GUARD_REGIONS) { "At most $MAX_GUARD_REGIONS guard regions are supported." }
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val region = array.getJSONObject(index)
                add(
                    GuardRegion(
                        left = region.getInt("left"),
                        top = region.getInt("top"),
                        right = region.getInt("right"),
                        bottom = region.getInt("bottom"),
                    ),
                )
            }
        }
    }

    private fun errorResponse(requestId: String?, message: String): JSONObject = JSONObject()
        .put("type", "error")
        .put("requestId", requestId ?: JSONObject.NULL)
        .put("ok", false)
        .put("message", message)

    private fun observationFailureCode(message: String): String =
        if (message.contains("Wireless Debugging", ignoreCase = true) ||
            message.contains("DHD could not execute", ignoreCase = true)
        ) {
            "DEVELOPER_MODE_UNAVAILABLE"
        } else {
            "OBSERVATION_FAILED"
        }

    private fun write(writer: BufferedWriter, json: JSONObject) {
        writer.write(json.toString())
        writer.newLine()
        writer.flush()
    }

    internal fun isAuthorized(peerAddress: InetAddress, json: JSONObject): Boolean {
        // adb forward presents the desktop peer as loopback. Keep this local
        // development path compatible without requiring a token, while every
        // actual LAN peer must prove possession of the paired token.
        if (peerAddress.isLoopbackAddress) return true
        val candidate = json.optString("authToken").trim().toByteArray(Charsets.UTF_8)
        val expected = authenticationToken.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(candidate, expected)
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

    internal data class SequenceRequest(
        val observationId: String,
        val actions: List<PhoneAction>,
        val displayRef: String? = null,
    )

    internal class InvalidSequencePayloadException(
        val index: Int?,
        message: String,
    ) : IllegalArgumentException(message)

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
        const val PREFERENCES_NAME = "dhd_companion_link"
        const val KEY_AUTH_TOKEN = "bridge_auth_token"
        const val KEY_DEVICE_ID = "device_id"
        // The companion uses short-lived TCP polls. Allow several missed
        // polls before showing a disconnect so one Wi-Fi/scheduler hiccup
        // does not flap the phone UI offline.
        const val COMPANION_PRESENCE_TIMEOUT_MS = 15_000L
        const val COMPANION_PRESENCE_CHECK_INTERVAL_MS = 1_000L
        const val MAX_REQUEST_CHARS = 16_384
        const val MAX_TEXT_CHARS = 240
        const val MAX_AGENT_FEEDBACK_CHARS = 4_000
        const val MAX_APP_QUERY_CHARS = 120
        const val MAX_APP_BROWSE_RESULTS = 25
        const val MAX_GUARD_REGIONS = 8
        const val MAX_SEQUENCE_ACTIONS = 16
        const val MAX_OBSERVATIONS = 64
        const val OPEN_SETTLE_DELAY_MS = 750L
        const val POST_ACTION_SETTLE_DELAY_MS = 350L
        const val CAPTURE_ATTEMPTS = 5
        const val CAPTURE_RETRY_DELAY_MS = 250L
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
        val DISPLAY_REF_PATTERN = Regex("dsp_[a-f0-9]{14}")
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

internal fun buildAllowedAppsResponse(
    requestId: String,
    fullAccess: Boolean,
    allowedPackages: Set<String>,
    includeAll: Boolean = false,
    apps: List<InstalledUserApp> = emptyList(),
): JSONObject {
    val response = JSONObject()
        .put("type", "allowed_apps")
        .put("requestId", requestId)
        .put("ok", true)
        .put("fullAccess", fullAccess)
        .put("accessMode", if (fullAccess) "full_access" else "allowlist")
        .put("canListAllApps", fullAccess)

    if (includeAll) {
        response
            .put("apps", JSONArray(apps.map(::buildAppResponse)))
            .put("count", apps.size)
            .put(
                "message",
                if (fullAccess) {
                    "Full Access is enabled. Returned all launchable apps on the phone."
                } else {
                    "Restricted access is enabled. Returned all launchable apps in the allowlist."
                },
            )
    } else if (fullAccess) {
        response.put("message", "Full Access is enabled. You can use any launchable app on the phone.")
    } else {
        val packages = allowedPackages.toList().sorted()
        response
            .put("allowedPackages", JSONArray(packages))
            .put("count", packages.size)
    }

    return response
}

internal fun buildBrowseAppsResponse(
    requestId: String,
    query: String,
    fullAccess: Boolean,
    allowedPackages: Set<String> = emptySet(),
    apps: List<InstalledUserApp>,
    truncated: Boolean,
): JSONObject = JSONObject()
    .put("type", "browse_apps")
    .put("requestId", requestId)
    .put("ok", true)
    .put("query", query)
    .put("fullAccess", fullAccess)
    .put("accessMode", if (fullAccess) "full_access" else "allowlist")
    .put(
        "apps",
        JSONArray(
            apps.map { app ->
                buildAppResponse(
                    app = app,
                    canUse = fullAccess || app.packageName in allowedPackages,
                )
            },
        ),
    )
    .put("count", apps.size)
    .put("truncated", truncated)

internal fun buildAppDisplayLayoutResponse(
    requestId: String,
    packageName: String,
    appLabel: String,
    layout: String,
    changed: Boolean,
): JSONObject {
    val fullSize = layout == "full_size"
    val layoutDescription = if (fullSize) "full-size" else "standard"
    return JSONObject()
        .put("type", "app_display_layout_updated")
        .put("requestId", requestId)
        .put("ok", true)
        .put("appLabel", appLabel)
        .put("packageName", packageName)
        .put("layout", layout)
        .put("fullSizeLayoutEnabled", fullSize)
        .put("changed", changed)
        .put("appliesNextOpen", true)
        .put("requiresFreshDisplay", changed)
        .put("currentDisplayUnchanged", true)
        .put("displayGeometryUnchanged", true)
        .put(
            "message",
            if (changed) {
                "$layoutDescription app layout saved for $appLabel. The next dhd_open_app call without displayRef will use a fresh DHD task display with this layout."
            } else {
                "$layoutDescription app layout is already active for $appLabel. Future compatible opens may reuse the current DHD task display."
            },
        )
}

/**
 * Add an actionable display inventory to a session-limit failure. The list
 * intentionally contains only displayRefs and user-facing metadata so the
 * agent can close or reuse a display without receiving native display IDs or
 * coordinator/session keys.
 */
internal fun addDisplayLimitRecovery(
    response: JSONObject,
    packageName: String?,
    displays: List<JSONObject>,
): JSONObject {
    val target = packageName?.trim()?.takeIf(String::isNotEmpty) ?: "the requested app"
    return response
        .put(
            "message",
            "The DHD virtual-display session limit was reached while opening $target. " +
                "The displays array lists the active and retained displays. " +
                "Close an unused display with dhd_close_display using its exact displayRef " +
                "(stop its active run first if needed), then retry dhd_open_app. " +
                "To reuse a retained display instead, pass its displayRef to dhd_open_app.",
        )
        .put("displays", JSONArray(displays))
        .put("count", displays.size)
}

private fun buildAppResponse(app: InstalledUserApp, canUse: Boolean? = null): JSONObject {
    val response = JSONObject()
        .put("appLabel", app.label)
        .put("packageName", app.packageName)
    if (canUse != null) response.put("canUse", canUse)
    return response
}

private fun ActionExecutionResult.isSuccessful(): Boolean = this is ActionExecutionResult.TransportFinished &&
    this.result is TransportResult.Succeeded

private fun ActionExecutionResult.beforeScreenshotOrNull(): ByteArray? = when (this) {
    is ActionExecutionResult.TransportFinished ->
        (result as? TransportResult.Succeeded)?.beforeScreenshot
    is ActionExecutionResult.PolicyRejected,
    ActionExecutionResult.SessionNotRunning -> null
}

private fun ActionExecutionResult.staleDetailsOrNull(): StaleObservationDiagnostics? = when (this) {
    is ActionExecutionResult.TransportFinished -> (result as? TransportResult.Rejected)?.details
    is ActionExecutionResult.PolicyRejected -> details
    ActionExecutionResult.SessionNotRunning -> null
}

private fun ActionExecutionResult.failureMessage(): String = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Rejected -> result.message
        is TransportResult.Unsupported -> result.message
        is TransportResult.Succeeded -> result.message
    }
    is ActionExecutionResult.PolicyRejected -> message
    ActionExecutionResult.SessionNotRunning -> "The phone session is no longer running."
}

private fun ActionExecutionResult.successMessage(): String = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Succeeded -> result.message
        is TransportResult.Rejected -> result.message
        is TransportResult.Unsupported -> result.message
    }
    is ActionExecutionResult.PolicyRejected -> message
    ActionExecutionResult.SessionNotRunning -> "The phone session is no longer running."
}

private fun ActionExecutionResult.failureCode(): String? = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Rejected -> result.code.name
        is TransportResult.Unsupported -> "UNSUPPORTED_ACTION"
        is TransportResult.Succeeded -> null
    }
    is ActionExecutionResult.PolicyRejected -> code
    ActionExecutionResult.SessionNotRunning -> "SESSION_NOT_RUNNING"
}
