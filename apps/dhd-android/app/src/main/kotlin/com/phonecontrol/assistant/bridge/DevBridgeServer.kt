package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.auth.BridgeCredentials
import com.phonecontrol.assistant.bridge.handlers.SessionHandlers
import com.phonecontrol.assistant.bridge.handlers.SteerHandlers
import com.phonecontrol.assistant.bridge.pairing.PairingProtocol
import com.phonecontrol.assistant.bridge.pairing.PairingReturnAddress
import com.phonecontrol.assistant.bridge.pairing.PairingUdpServer
import com.phonecontrol.assistant.bridge.pairing.PendingCompanionPairing
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
    private val steers = SteerHandlers(coordinator, presence)
    private val sessions = SessionHandlers(coordinator, platform, presence)
    private val bridgeJson = BridgeJson(base64)
    private val captures = CaptureService(coordinator, observationProvider, taskDisplayRequiredProvider)
    private val displayTargets = DisplayTargetResolver(taskDisplayBackend, coordinator, taskDisplayRequiredProvider, clock, platform)

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
                    allowedApps(requestId, json, reply)
                }
                "browse_apps" -> toolCalls.withDhdTool(json, ToolNames.BROWSE_APP) {
                    browseApps(requestId, json, reply)
                }
                "set_app_display_layout" -> toolCalls.withDhdTool(json, ToolNames.SET_APP_DISPLAY_LAYOUT) {
                    setAppDisplayLayout(requestId, json, reply)
                }
                "list_displays" -> listDisplays(requestId, reply)
                "close_display" -> closeDisplay(requestId, json, reply)
                "foreground_app" -> toolCalls.withDhdTool(json, ToolNames.FOREGROUND_APP) {
                    foregroundApp(requestId, json, reply)
                }
                "observe" -> toolCalls.withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.OBSERVE,
                ) {
                    observe(requestId, json, reply)
                }
                "execute_action" -> toolCalls.withDhdTool(
                    json = json,
                    fallbackToolName = toolCalls.fallbackActionToolName(json),
                ) {
                    phoneActionLock.withLock { executeAction(requestId, json, reply) }
                }
                "execute_sequence" -> toolCalls.withDhdTool(
                    json = json,
                    fallbackToolName = ToolNames.EXECUTE_SEQUENCE,
                ) {
                    phoneActionLock.withLock { executeSequence(requestId, json, reply) }
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
        val displays = displayTargets.currentDisplayJson(backend)
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
                    .put("appLabel", platform.appLabel(result.record.packageName))
                    .put("status", result.record.status.name.lowercase())
                    .put("message", "The selected task display was ended."),
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
            when (val resolution = displayTargets.resolve(
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
        when (val captured = captures.captureWithRetry(
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
                captures.remember(captured.snapshot)
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
            when (val resolution = displayTargets.resolve(
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
        val suppliedObservation = observationId.takeIf(String::isNotBlank)?.let(captures::lookup)
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
            displayTargets.resolve(
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
            } else when (val captured = captures.captureWithRetry(null, emptyList(), null)) {
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
                    captures.remember(captured.snapshot)
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
            ?: toolCalls.fallbackActionToolName(json)
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
                    displayTargets.displayInventoryForRecovery(backend)
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
        when (val captured = captures.captureWithRetry(
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
                captures.remember(captured.snapshot)
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
        val observation = captures.lookup(request.observationId)
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
            when (val resolution = displayTargets.resolve(
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
                captures.captureWithRetry(
                    expectedPackageName = null,
                    guardRegions = guardRegions,
                    taskSessionKey = target?.session?.sessionKey ?: runSessionKey,
                    displayId = target?.session?.displayId,
                    expectedDisplayRef = target?.displayRef,
                )
            },
            rememberObservation = captures::remember,
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
