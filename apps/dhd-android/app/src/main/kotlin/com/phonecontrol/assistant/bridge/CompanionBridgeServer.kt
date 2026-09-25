package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.bridge.auth.BridgeCredentials
import com.phonecontrol.assistant.bridge.handlers.AppCatalogHandlers
import com.phonecontrol.assistant.bridge.handlers.AttentionHandler
import com.phonecontrol.assistant.bridge.handlers.DemoHandler
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
import com.phonecontrol.assistant.bridge.protocol.BridgeJson
import com.phonecontrol.assistant.bridge.routing.BridgeHandler
import com.phonecontrol.assistant.bridge.routing.BridgeRouter
import com.phonecontrol.assistant.bridge.routing.PhoneActionLock
import com.phonecontrol.assistant.bridge.routing.ToolCallScope
import com.phonecontrol.assistant.bridge.transport.BridgeTcpServer
import com.phonecontrol.assistant.bridge.transport.NdjsonWriter
import com.phonecontrol.assistant.core.AndroidBase64Codec
import com.phonecontrol.assistant.core.Base64Codec
import com.phonecontrol.assistant.core.BuildDeviceInfo
import com.phonecontrol.assistant.core.Clock
import com.phonecontrol.assistant.core.DeviceInfo
import com.phonecontrol.assistant.core.SystemClockClock
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.observation.PhoneObservationSource
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import java.io.BufferedWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.StateFlow

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
class CompanionBridgeServer internal constructor(
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
    private val bindHost: String = LAN_BIND_HOST,
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
        bindHost = bindHost,
        port = PAIRING_DISCOVERY_PORT,
    )
    private val phoneActionLock = PhoneActionLock()
    private val toolCalls = ToolCallScope(coordinator, platform)
    private val bridgeJson = BridgeJson(base64)
    private val captures = CaptureService(coordinator, observationProvider, taskDisplayRequiredProvider)
    private val displayTargets = DisplayTargetResolver(
        taskDisplayBackend,
        coordinator,
        taskDisplayRequiredProvider,
        clock,
        platform,
    )
    private val sessions = SessionHandlers(coordinator, platform, presence)
    private val steers = SteerHandlers(coordinator, presence)
    private val appCatalog = AppCatalogHandlers(coordinator, platform, allowedPackagesProvider, fullAccessProvider)
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
    private val attention = AttentionHandler(
        coordinator,
        platform,
        taskDisplayRequiredProvider,
        displayTargets,
        captures,
        bridgeJson,
        base64,
    )
    private val demo = DemoHandler(coordinator, captures, bridgeJson, newUuid)
    private val router = BridgeRouter(credentials, presence, platform, newUuid, requestHandlers())
    private val tcpServer = BridgeTcpServer(port, bindHost, scope, platform, router::handleRequestLine)

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
        tcpServer.start()
        scope.launch { pairingServer.run() }
        scope.launch { presence.monitor() }
    }

    fun stop() {
        started = false
        tcpServer.stop()
        pairingServer.stop()
        presence.release()
        scope.coroutineContext.cancelChildren()
    }

    /**
     * Record that the DHD UI became visible. The desktop companion consumes
     * this one-shot bit on its next pending-request poll and prewarms Codex in
     * the background, without making the Android app wait for the desktop.
     */
    fun requestCodexWarmup() {
        presence.requestCodexWarmup()
    }

    internal suspend fun handleRequestLine(
        line: String?,
        writer: BufferedWriter,
    ) {
        router.handleRequestLine(line, NdjsonWriter(writer))
    }

    private fun requestHandlers(): Map<String, BridgeHandler> = mapOf(
        "demo_run" to BridgeHandler { _, json, reply -> phoneActionLock.withLock { demo.run(json, reply) } },
        "start_session" to BridgeHandler { requestId, json, reply -> sessions.startSession(requestId, json, reply) },
        "status" to BridgeHandler { requestId, _, reply -> sessions.status(requestId, reply) },
        "heartbeat" to BridgeHandler { requestId, _, reply -> sessions.heartbeat(requestId, reply) },
        "companion_disconnected" to BridgeHandler { requestId, _, reply -> sessions.companionDisconnected(requestId, reply) },
        "pending_request" to BridgeHandler { requestId, _, reply -> sessions.pendingRequest(requestId, reply) },
        "claim_request" to BridgeHandler { requestId, json, reply -> sessions.claimRequest(requestId, json, reply) },
        "pending_steer" to BridgeHandler { requestId, json, reply -> steers.pendingSteer(requestId, json, reply) },
        "claim_steer" to BridgeHandler { requestId, json, reply -> steers.claimSteer(requestId, json, reply) },
        "release_steer" to BridgeHandler { requestId, json, reply -> steers.releaseSteer(requestId, json, reply) },
        "complete_steer" to BridgeHandler { requestId, json, reply -> steers.completeSteer(requestId, json, reply) },
        "bind_codex_thread" to BridgeHandler { requestId, json, reply -> sessions.bindCodexThread(requestId, json, reply) },
        "release_request" to BridgeHandler { requestId, json, reply -> sessions.releaseRequest(requestId, json, reply) },
        "stream_agent_message" to BridgeHandler { requestId, json, reply -> sessions.streamAgentMessage(requestId, json, reply) },
        "complete_session" to BridgeHandler { requestId, json, reply -> sessions.completeSession(requestId, json, reply) },
        "fail_session" to BridgeHandler { requestId, json, reply -> sessions.failSession(requestId, json, reply) },
        "allowed_apps" to BridgeHandler { requestId, json, reply ->
            toolCalls.withDhdTool(json, ToolNames.LIST_ALLOWED_APPS) {
                appCatalog.allowedApps(requestId, json, reply)
            }
        },
        "browse_apps" to BridgeHandler { requestId, json, reply ->
            toolCalls.withDhdTool(json, ToolNames.BROWSE_APP) {
                appCatalog.browseApps(requestId, json, reply)
            }
        },
        "set_app_display_layout" to BridgeHandler { requestId, json, reply ->
            toolCalls.withDhdTool(json, ToolNames.SET_APP_DISPLAY_LAYOUT) {
                appCatalog.setAppDisplayLayout(requestId, json, reply)
            }
        },
        "list_displays" to BridgeHandler { requestId, _, reply -> displays.listDisplays(requestId, reply) },
        "close_display" to BridgeHandler { requestId, json, reply -> displays.closeDisplay(requestId, json, reply) },
        "foreground_app" to BridgeHandler { requestId, json, reply ->
            toolCalls.withDhdTool(json, ToolNames.FOREGROUND_APP) {
                observations.foregroundApp(requestId, json, reply)
            }
        },
        "observe" to BridgeHandler { requestId, json, reply ->
            toolCalls.withDhdTool(
                json = json,
                fallbackToolName = ToolNames.OBSERVE,
            ) {
                observations.observe(requestId, json, reply)
            }
        },
        "execute_action" to BridgeHandler { requestId, json, reply ->
            toolCalls.withDhdTool(
                json = json,
                fallbackToolName = toolCalls.fallbackActionToolName(json),
            ) {
                phoneActionLock.withLock { executeActions.executeAction(requestId, json, reply) }
            }
        },
        "execute_sequence" to BridgeHandler { requestId, json, reply ->
            toolCalls.withDhdTool(
                json = json,
                fallbackToolName = ToolNames.EXECUTE_SEQUENCE,
            ) {
                phoneActionLock.withLock { executeSequences.executeSequence(requestId, json, reply) }
            }
        },
        "request_attention" to BridgeHandler { requestId, json, reply ->
            toolCalls.withDhdTool(
                json = json,
                fallbackToolName = ToolNames.REQUEST_ATTENTION,
                terminalStatus = DhdToolCallStatus.ATTENTION,
            ) {
                attention.requestAttention(requestId, json, reply)
            }
        },
        "stop_session" to BridgeHandler { requestId, json, reply -> sessions.stopSession(requestId, json, reply) },
    )

    /** Return currently usable IPv4 addresses that the desktop can dial. */
    fun lanIpv4Addresses(): List<String> = lanAddressProvider()

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
