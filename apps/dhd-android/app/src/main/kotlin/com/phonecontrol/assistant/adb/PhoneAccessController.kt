package com.phonecontrol.assistant.adb

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.phonecontrol.assistant.maintenance.DhdMaintenanceBootstrap
import com.phonecontrol.assistant.maintenance.DhdMaintenanceRecoveryPolicy
import com.phonecontrol.assistant.execution.PhoneProcessResult
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DeveloperConnectionState {
    CHECKING,
    UNSUPPORTED,
    WIRELESS_DEBUGGING_OFF,
    PAIRING_REQUIRED,
    PAIRING_SEARCHING,
    PAIRING_SERVICE_FOUND,
    CONNECTING,
    READY,
    ERROR,
}

private const val PHONE_ACCESS_INTERRUPTED_MESSAGE =
    "DHD is currently unable to use apps on your phone. This can happen after a restart or something totally out of your control. We'll show you the steps to fix it."
private const val INITIAL_PHONE_CONNECTION_MESSAGE =
    "DHD needs a one-time connection before it can use apps on your phone. We'll show you the steps."

data class DeveloperModeStatus(
    val state: DeveloperConnectionState = DeveloperConnectionState.CHECKING,
    val paired: Boolean = false,
    val message: String = "Checking Wireless Debugging…",
) {
    val privilegedApiReady: Boolean
        get() = state == DeveloperConnectionState.READY

    /** True when the user still needs to complete the first phone connection. */
    val needsInitialConnection: Boolean
        get() = !paired && state in setOf(
            DeveloperConnectionState.PAIRING_REQUIRED,
            DeveloperConnectionState.ERROR,
        )

    /** True when a saved phone connection was interrupted and needs recovery. */
    val phoneAccessInterrupted: Boolean
        get() = paired && state in setOf(
            DeveloperConnectionState.PAIRING_REQUIRED,
            DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
            DeveloperConnectionState.ERROR,
        )

    /** True when the current state needs a visible recovery action, not a spinner. */
    val requiresUserAction: Boolean
        get() = state in setOf(
            DeveloperConnectionState.PAIRING_REQUIRED,
            DeveloperConnectionState.PAIRING_SEARCHING,
            DeveloperConnectionState.PAIRING_SERVICE_FOUND,
            DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
            DeveloperConnectionState.UNSUPPORTED,
            DeveloperConnectionState.ERROR,
        )

    /** True when a saved pairing exists but DHD's local service must be restarted. */
    val requiresMaintenanceRestart: Boolean
        get() = phoneAccessInterrupted

    /** Copy shown before sending a user to DHD's phone-access instructions. */
    val recoveryTitle: String
        get() = when {
            needsInitialConnection -> "Phone access needed"
            phoneAccessInterrupted -> "Phone access needed"
            state == DeveloperConnectionState.PAIRING_SEARCHING -> "Connecting your phone"
            state == DeveloperConnectionState.PAIRING_SERVICE_FOUND -> "Pairing code ready"
            state == DeveloperConnectionState.CONNECTING -> "Connecting your phone"
            state == DeveloperConnectionState.CHECKING -> "Checking phone access"
            state == DeveloperConnectionState.UNSUPPORTED -> "DHD cannot use phone access"
            else -> "Phone access needs attention"
        }

    /** Non-technical recovery context for banners, overlays, and tool errors. */
    val recoveryDetail: String
        get() = when {
            needsInitialConnection -> INITIAL_PHONE_CONNECTION_MESSAGE
            phoneAccessInterrupted -> PHONE_ACCESS_INTERRUPTED_MESSAGE
            state == DeveloperConnectionState.PAIRING_SEARCHING ->
                "Follow these steps to enable DHD to use apps."
            state == DeveloperConnectionState.PAIRING_SERVICE_FOUND ->
                "Enter the six-digit code Android shows in the DHD notification."
            state == DeveloperConnectionState.CONNECTING ->
                "DHD is still connecting to your phone. Try again in a moment."
            state == DeveloperConnectionState.CHECKING ->
                "DHD is checking whether phone access is available."
            state == DeveloperConnectionState.UNSUPPORTED ->
                "DHD needs Android 11 or newer for phone access."
            else -> message
        }

    /** Safe, actionable copy for a phone action rejected while access is unavailable. */
    val executionUnavailableMessage: String
        get() = when {
            phoneAccessInterrupted -> PHONE_ACCESS_INTERRUPTED_MESSAGE
            needsInitialConnection -> INITIAL_PHONE_CONNECTION_MESSAGE
            !privilegedApiReady -> recoveryDetail
            else -> message
        }
}

/**
 * Owns DHD's one-time Wireless Debugging bootstrap and its long-lived local
 * shell-UID maintenance connection. It never enables Wireless Debugging
 * itself; the user explicitly turns that maintenance switch on when Android
 * has restarted the maintenance process or pairing is needed.
 */
class PhoneAccessController internal constructor(
    context: Context,
    private val preferences: SharedPreferences,
    private val mdns: DhdAdbMdns,
    private val maintenanceBootstrap: DhdMaintenanceBootstrap,
    keyFactory: () -> DhdAdbKey,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val maintenanceRecoveryPolicy = DhdMaintenanceRecoveryPolicy()
    private val key by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { keyFactory() }
    private val _status = MutableStateFlow(DeveloperModeStatus())
    private val started = AtomicBoolean(false)
    private val maintenanceProbeGate = AtomicBoolean(false)
    private val maintenanceRecoveryGate = AtomicBoolean(false)
    private val maintenanceRetryGate = AtomicBoolean(false)
    private val connectDiscoveryGate = AtomicBoolean(false)
    private var discoveryTimeoutJob: Job? = null
    private var pairingJob: Job? = null
    private var maintenanceProbeJob: Job? = null
    private var maintenanceMonitorJob: Job? = null
    private var maintenanceRecoveryJob: Job? = null
    private var maintenanceRetryJob: Job? = null
    private var maintenanceRetryAttempt = 0
    private var endpoint: DhdAdbEndpoint? = null
    private var pairingEndpoint: DhdAdbEndpoint? = null

    val status: StateFlow<DeveloperModeStatus> = _status.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            publish(
                DeveloperConnectionState.UNSUPPORTED,
                paired = false,
                message = "Direct Wireless Debugging requires Android 11 or newer.",
            )
            return
        }
        if (isPaired()) {
            beginMaintenanceProbe()
        } else {
            publish(
                DeveloperConnectionState.PAIRING_REQUIRED,
                paired = false,
                message = PAIRING_REQUIRED_MESSAGE,
            )
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        discoveryTimeoutJob?.cancel()
        pairingJob?.cancel()
        maintenanceProbeJob?.cancel()
        maintenanceMonitorJob?.cancel()
        maintenanceRecoveryJob?.cancel()
        maintenanceRetryJob?.cancel()
        maintenanceRetryJob = null
        maintenanceRetryAttempt = 0
        maintenanceProbeGate.set(false)
        maintenanceRecoveryGate.set(false)
        maintenanceRetryGate.set(false)
        connectDiscoveryGate.set(false)
        maintenanceRecoveryPolicy.reset()
        mdns.stop()
        endpoint = null
        pairingEndpoint = null
        DhdAdbPairingNotification.cancel(appContext)
        appContext.stopService(Intent(appContext, DhdAdbPairingService::class.java))
    }

    /** Re-check the local ADB advertisement after the user changes settings. */
    fun refresh() {
        if (!started.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        when (_status.value.state) {
            DeveloperConnectionState.CONNECTING,
            DeveloperConnectionState.CHECKING,
            DeveloperConnectionState.PAIRING_SEARCHING,
            DeveloperConnectionState.PAIRING_SERVICE_FOUND,
            DeveloperConnectionState.UNSUPPORTED,
            -> Unit
            DeveloperConnectionState.READY,
            DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
            DeveloperConnectionState.ERROR,
            -> if (isPaired()) {
                maintenanceRetryJob?.cancel()
                maintenanceRetryJob = null
                maintenanceRetryAttempt = 0
                beginMaintenanceProbe()
            } else {
                publish(
                    DeveloperConnectionState.PAIRING_REQUIRED,
                    paired = false,
                    message = PAIRING_REQUIRED_MESSAGE,
                )
            }
            DeveloperConnectionState.PAIRING_REQUIRED -> publish(
                DeveloperConnectionState.PAIRING_REQUIRED,
                paired = isPaired(),
                message = if (isPaired()) MAINTENANCE_RESTART_MESSAGE else PAIRING_REQUIRED_MESSAGE,
            )
        }
    }

    /** Start the Shizuku-style pairing notification while Settings is open. */
    fun startPairingNotification(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            publish(
                DeveloperConnectionState.UNSUPPORTED,
                paired = false,
                message = "Direct Wireless Debugging requires Android 11 or newer.",
            )
            return false
        }
        if (!DhdAdbPairingNotification.areNotificationsEnabled(appContext)) {
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = "Allow DHD notifications to enter the pairing code from the notification.",
            )
            return false
        }
        if (!started.get()) start()
        return runCatching {
            ContextCompat.startForegroundService(
                appContext,
                DhdAdbPairingService.startIntent(appContext),
            )
            true
        }.getOrElse { error ->
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = "DHD could not start the pairing notification: ${rootMessage(error)}",
            )
            false
        }
    }

    /** Reset the controller for a fresh pairing attempt without changing the saved key. */
    internal fun preparePairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!started.get()) start()
        discoveryTimeoutJob?.cancel()
        pairingJob?.cancel()
        maintenanceProbeJob?.cancel()
        maintenanceMonitorJob?.cancel()
        maintenanceRecoveryJob?.cancel()
        maintenanceRetryJob?.cancel()
        maintenanceRetryJob = null
        maintenanceRetryAttempt = 0
        maintenanceProbeGate.set(false)
        maintenanceRecoveryGate.set(false)
        maintenanceRetryGate.set(false)
        connectDiscoveryGate.set(false)
        maintenanceRecoveryPolicy.reset()
        mdns.stop()
        endpoint = null
        pairingEndpoint = null
        publish(
            DeveloperConnectionState.PAIRING_SEARCHING,
            paired = isPaired(),
            message = PAIRING_SEARCHING_MESSAGE,
        )
        DhdAdbPairingNotification.showSearching(appContext)
        beginPairingDiscovery(pairingCode = null)
    }

    /** Stop a notification-owned pairing attempt and resume normal reconnecting. */
    internal fun cancelPairing() {
        discoveryTimeoutJob?.cancel()
        pairingJob?.cancel()
        connectDiscoveryGate.set(false)
        mdns.stop()
        endpoint = null
        pairingEndpoint = null
        if (isPaired()) {
            beginMaintenanceProbe()
        } else {
            publish(
                DeveloperConnectionState.PAIRING_REQUIRED,
                paired = false,
                message = PAIRING_REQUIRED_MESSAGE,
            )
        }
    }

    /**
     * Start one pairing attempt. The user should first open Developer options
     * and choose Wireless debugging → Pair device with pairing code.
     */
    fun pair(pairingCode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            publish(
                DeveloperConnectionState.UNSUPPORTED,
                paired = false,
                message = "Direct Wireless Debugging requires Android 11 or newer.",
            )
            return
        }
        if (!pairingCode.matches(PAIRING_CODE_REGEX)) {
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = "Enter the six-digit Wireless Debugging pairing code.",
            )
            return
        }
        if (!started.get()) start()
        val discoveredPairingEndpoint = pairingEndpoint
        pairingJob?.cancel()
        discoveryTimeoutJob?.cancel()
        mdns.stop()
        publish(
            DeveloperConnectionState.CONNECTING,
            paired = isPaired(),
            message = if (discoveredPairingEndpoint == null) {
                "Looking for the Wireless Debugging pairing service…"
            } else {
                "Pairing with Wireless Debugging…"
            },
        )

        if (discoveredPairingEndpoint != null) {
            pairingJob = scope.launch { performPairing(discoveredPairingEndpoint, pairingCode) }
        } else {
            beginPairingDiscovery(pairingCode)
        }
    }

    internal suspend fun execute(
        command: List<String>,
        binaryOutput: Boolean = false,
        onStarted: (() -> Unit)? = null,
    ): PhoneProcessResult =
        commandMutex.withLock {
            if (!started.get() || !isPaired()) return@withLock unavailableResult()
            try {
                val result = maintenanceBootstrap.client().execute(
                    command,
                    binaryOutput,
                    onStarted,
                )
                if (result.exitCode == null && !result.timedOut) {
                    handleMaintenanceUnavailable(result.stderr)
                }
                result
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleMaintenanceUnavailable(rootMessage(error))
                PhoneProcessResult(
                    exitCode = null,
                    stdout = ByteArray(0),
                    stderr = if (error is SocketTimeoutException) {
                        "The DHD maintenance command timed out."
                    } else {
                        "DHD maintenance service is unavailable: ${rootMessage(error)}"
                    },
                    timedOut = error is SocketTimeoutException,
                )
            }
        }

    private fun beginMaintenanceProbe() {
        if (!started.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isPaired()) return
        if (!maintenanceProbeGate.compareAndSet(false, true)) return
        maintenanceProbeJob = scope.launch {
            try {
                val check = commandMutex.withLock {
                    maintenanceBootstrap.client().checkCompatibility()
                }
                if (!currentCoroutineContext().isActive) return@launch
                Log.i(TAG, "Maintenance probe compatible=${check.compatible} endpointKnown=${endpoint != null}: ${check.detail}")
                if (check.compatible) {
                    maintenanceRecoveryPolicy.recordHealthy()
                    publishReady()
                } else {
                    handleMaintenanceUnavailable("DHD's maintenance service is unavailable (${check.detail}).")
                }
            } finally {
                maintenanceProbeGate.set(false)
                maintenanceProbeJob = null
            }
        }
    }

    private fun startMaintenanceMonitor() {
        maintenanceMonitorJob?.cancel()
        maintenanceMonitorJob = scope.launch {
            while (isActive && started.get()) {
                delay(MAINTENANCE_HEALTH_INTERVAL_MS)
                val check = commandMutex.withLock {
                    maintenanceBootstrap.client().checkCompatibility()
                }
                if (!currentCoroutineContext().isActive) return@launch
                if (!check.compatible) {
                    handleMaintenanceUnavailable("DHD's maintenance service stopped (${check.detail}).")
                    return@launch
                }
            }
        }
    }

    private fun publishReady() {
        if (!started.get()) return
        maintenanceRetryJob?.cancel()
        maintenanceRetryJob = null
        maintenanceRetryAttempt = 0
        maintenanceRetryGate.set(false)
        maintenanceRecoveryPolicy.recordHealthy()
        publish(
            DeveloperConnectionState.READY,
            paired = true,
            message = "DHD maintenance service is running. Wireless Debugging can be turned off until DHD needs a restart.",
        )
        startMaintenanceMonitor()
    }

    private fun handleMaintenanceUnavailable(detail: String) {
        if (!started.get() || !isPaired()) return
        maintenanceMonitorJob?.cancel()
        val keepActionableStatus = _status.value.state == DeveloperConnectionState.WIRELESS_DEBUGGING_OFF
        if (!maintenanceRecoveryPolicy.recordUnavailable()) {
            Log.w(TAG, "Maintenance unavailable once; deferring ADB bootstrap and scheduling a local re-check: $detail")
            publish(
                if (keepActionableStatus) {
                    DeveloperConnectionState.WIRELESS_DEBUGGING_OFF
                } else {
                    DeveloperConnectionState.CONNECTING
                },
                paired = true,
                message = if (keepActionableStatus) {
                    WIRELESS_DEBUGGING_REQUIRED_MESSAGE
                } else {
                    "DHD maintenance check failed once. Rechecking locally before restarting it; pairing is saved."
                },
            )
            scheduleMaintenanceRetry()
            return
        }
        Log.w(TAG, "Maintenance unavailable endpointKnown=${endpoint != null}: $detail")
        publish(
            if (keepActionableStatus) {
                DeveloperConnectionState.WIRELESS_DEBUGGING_OFF
            } else {
                DeveloperConnectionState.CONNECTING
            },
            paired = true,
            message = if (keepActionableStatus) {
                WIRELESS_DEBUGGING_REQUIRED_MESSAGE
            } else {
                "$detail Reconnecting DHD's maintenance service. Pairing is already saved."
            },
        )
        scheduleMaintenanceRecovery()
    }

    private fun scheduleMaintenanceRecovery() {
        if (!started.get() || !isPaired()) return
        if (discoveryTimeoutJob?.isActive == true || !maintenanceRecoveryGate.compareAndSet(false, true)) return
        val keepActionableStatus = _status.value.state == DeveloperConnectionState.WIRELESS_DEBUGGING_OFF && endpoint == null
        Log.i(TAG, "Starting maintenance recovery endpointKnown=${endpoint != null}")
        publish(
            if (keepActionableStatus) {
                DeveloperConnectionState.WIRELESS_DEBUGGING_OFF
            } else {
                DeveloperConnectionState.CONNECTING
            },
            paired = true,
            message = if (keepActionableStatus) {
                WIRELESS_DEBUGGING_REQUIRED_MESSAGE
            } else if (endpoint != null) {
                "Checking DHD's local maintenance service before restarting it…"
            } else {
                "Waiting for Wi-Fi/ADB to reconnect before checking DHD's maintenance service…"
            },
        )
        maintenanceRecoveryPolicy.reset()
        maintenanceRecoveryJob = scope.launch {
            try {
                val recovered = commandMutex.withLock {
                    val currentEndpoint = endpoint ?: return@withLock false
                    try {
                        bootstrapMaintenance(currentEndpoint)
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.w(TAG, "Maintenance service recovery failed", error)
                        false
                    }
                }
                if (!currentCoroutineContext().isActive || !started.get()) return@launch
                if (recovered) {
                    Log.i(TAG, "Maintenance recovery succeeded using the known ADB endpoint.")
                    publishReady()
                } else {
                    Log.w(TAG, "Known-endpoint maintenance recovery failed; starting ADB service discovery.")
                    endpoint = null
                    beginConnectDiscovery()
                }
            } finally {
                maintenanceRecoveryGate.set(false)
                maintenanceRecoveryJob = null
            }
        }
    }

    private suspend fun bootstrapMaintenance(discovered: DhdAdbEndpoint) {
        DhdAdbClient(
            host = discovered.host,
            port = discovered.port,
            key = key,
        ).use { adb ->
            adb.connect()
            maintenanceBootstrap.ensureStarted(adb)
        }
    }

    private fun beginConnectDiscovery() {
        if (!started.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!connectDiscoveryGate.compareAndSet(false, true)) return
        discoveryTimeoutJob?.cancel()
        pairingJob?.cancel()
        pairingEndpoint = null
        endpoint = null
        mdns.stop()
        Log.i(TAG, "Starting ADB connect-service discovery attempt ${maintenanceRetryAttempt + 1}.")
        val keepActionableStatus = _status.value.state == DeveloperConnectionState.WIRELESS_DEBUGGING_OFF && isPaired()
        publish(
            if (keepActionableStatus) {
                DeveloperConnectionState.WIRELESS_DEBUGGING_OFF
            } else {
                DeveloperConnectionState.CONNECTING
            },
            paired = isPaired(),
            message = if (keepActionableStatus) {
                WIRELESS_DEBUGGING_REQUIRED_MESSAGE
            } else {
                "Waiting for Wi-Fi and Wireless Debugging's ADB service…"
            },
        )

        val completed = AtomicBoolean(false)
        discoveryTimeoutJob = scope.launch {
            delay(CONNECT_DISCOVERY_TIMEOUT_MS)
            if (completed.compareAndSet(false, true)) {
                mdns.stop()
                connectDiscoveryGate.set(false)
                Log.w(TAG, "ADB connect-service discovery timed out; Wi-Fi or Wireless Debugging may be unavailable temporarily.")
                publish(
                    DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
                    paired = isPaired(),
                    message = WIRELESS_DEBUGGING_REQUIRED_MESSAGE,
                )
                scheduleMaintenanceRetry()
            }
        }
        mdns.start(
            serviceType = DhdAdbMdns.TLS_CONNECT,
            onResolved = { discovered ->
                if (!completed.compareAndSet(false, true)) return@start
                discoveryTimeoutJob?.cancel()
                mdns.stop()
                endpoint = discovered
                Log.i(TAG, "ADB connect service discovered on local port ${discovered.port}; verifying authorization and daemon bootstrap.")
                scope.launch { verifyConnection(discovered) }
            },
            onLost = {
                if (started.get() && endpoint != null) beginConnectDiscovery()
            },
            onError = { error ->
                if (completed.compareAndSet(false, true)) {
                    discoveryTimeoutJob?.cancel()
                    mdns.stop()
                    connectDiscoveryGate.set(false)
                    Log.w(TAG, "ADB connect-service discovery failed: ${rootMessage(error)}")
                    publish(
                        DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
                        paired = isPaired(),
                        message = WIRELESS_DEBUGGING_REQUIRED_MESSAGE,
                    )
                    scheduleMaintenanceRetry()
                }
            },
        )
    }

    private suspend fun verifyConnection(discovered: DhdAdbEndpoint) = commandMutex.withLock {
        try {
            if (endpoint != discovered) return@withLock
            publish(
                DeveloperConnectionState.CONNECTING,
                paired = isPaired(),
                message = "Connecting to DHD's local ADB service…",
            )
            var adbConnected = false
            try {
                DhdAdbClient(discovered.host, discovered.port, key).use { adb ->
                    adb.connect()
                    adbConnected = true
                    maintenanceBootstrap.ensureStarted(adb)
                }
                Log.i(TAG, "ADB connection and maintenance bootstrap succeeded on local port ${discovered.port}.")
                publishReady()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "ADB/maintenance verification failed adbConnected=$adbConnected: ${rootMessage(error)}", error)
                if (adbConnected) {
                    publish(
                        DeveloperConnectionState.CONNECTING,
                        paired = true,
                        message = "ADB is reachable, but DHD's maintenance service could not start. Retrying; pairing is still saved.",
                    )
                    scheduleMaintenanceRetry()
                } else {
                    mdns.stop()
                    endpoint = null
                    preferences.edit().putBoolean(KEY_PAIRED, false).apply()
                    publish(
                        DeveloperConnectionState.PAIRING_REQUIRED,
                        paired = false,
                        message = "DHD is not authorized by Wireless Debugging. Pair DHD once, then it will reconnect automatically.",
                    )
                }
            }
        } finally {
            connectDiscoveryGate.set(false)
        }
    }

    private suspend fun performPairing(endpoint: DhdAdbEndpoint, pairingCode: String) {
        Log.i(TAG, "Starting ADB pairing against local port ${endpoint.port}")
        try {
            val success = DhdAdbPairingClient(
                host = endpoint.host,
                port = endpoint.port,
                pairingCode = pairingCode,
                key = key,
            ).use { it.start() }
            if (!success) {
                pairingEndpoint = null
                publish(
                    DeveloperConnectionState.ERROR,
                    paired = isPaired(),
                    message = "DHD could not complete pairing. Check that the code is current and try again.",
                )
                return
            }
            pairingEndpoint = null
            preferences.edit().putBoolean(KEY_PAIRED, true).apply()
            beginConnectDiscovery()
        } catch (error: DhdAdbInvalidPairingCodeException) {
            pairingEndpoint = null
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = "That Wireless Debugging pairing code was not accepted.",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            pairingEndpoint = null
            Log.e(TAG, "Pairing failed: ${error::class.java.name}", error)
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = pairingFailureMessage(error),
            )
        }
    }

    private fun beginPairingDiscovery(pairingCode: String?) {
        if (!started.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        discoveryTimeoutJob?.cancel()
        mdns.stop()
        pairingEndpoint = null

        val completed = AtomicBoolean(false)
        // Keep the first notification alive while the user navigates through
        // Developer options. Shizuku also keeps mDNS pairing discovery alive
        // until the service is found or the user stops the flow. A timeout is
        // still useful after a code has been submitted because that is a
        // bounded network operation rather than a UI-guided search.
        if (pairingCode != null) {
            discoveryTimeoutJob = scope.launch {
                delay(PAIRING_DISCOVERY_TIMEOUT_MS)
                if (!completed.compareAndSet(false, true)) return@launch
                mdns.stop()
                publish(
                    DeveloperConnectionState.ERROR,
                    paired = isPaired(),
                    message = "The Wireless Debugging pairing service was not found. Open Pair device with pairing code and try again.",
                )
            }
        } else {
            discoveryTimeoutJob = null
        }
        mdns.start(
            serviceType = DhdAdbMdns.TLS_PAIRING,
            onResolved = { discovered ->
                if (!completed.compareAndSet(false, true)) return@start
                discoveryTimeoutJob?.cancel()
                mdns.stop()
                pairingEndpoint = discovered
                if (pairingCode == null) {
                    publish(
                        DeveloperConnectionState.PAIRING_SERVICE_FOUND,
                        paired = isPaired(),
                        message = PAIRING_SERVICE_FOUND_MESSAGE,
                    )
                } else {
                    pairingJob = scope.launch { performPairing(discovered, pairingCode) }
                }
            },
            onError = { error ->
                if (!completed.compareAndSet(false, true)) return@start
                discoveryTimeoutJob?.cancel()
                mdns.stop()
                val message = "Could not search for Wireless Debugging: ${rootMessage(error)}"
                if (pairingCode == null) {
                    DhdAdbPairingNotification.showSearching(appContext)
                }
                publish(
                    DeveloperConnectionState.ERROR,
                    paired = isPaired(),
                    message = message,
                )
            },
        )
    }

    private fun unavailableResult(): PhoneProcessResult = PhoneProcessResult(
        exitCode = null,
        stdout = ByteArray(0),
        stderr = _status.value.message,
    )

    private fun isPaired(): Boolean = preferences.getBoolean(KEY_PAIRED, false)

    private fun publish(
        state: DeveloperConnectionState,
        paired: Boolean,
        message: String,
    ) {
        val previous = _status.value
        if (previous.state != state || previous.paired != paired || previous.message != message) {
            Log.i(TAG, "Status ${previous.state} -> $state paired=$paired endpointKnown=${endpoint != null}: $message")
        }
        if (state == DeveloperConnectionState.READY) {
            DhdAdbPairingNotification.cancel(appContext)
        }
        _status.value = DeveloperModeStatus(state = state, paired = paired, message = message)
    }

    private fun rootMessage(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName

    /**
     * Keep trying after a discovery race or a temporarily unavailable ADB
     * advertisement. The old implementation stopped after one seven-second
     * attempt and required Activity.onStart() to call refresh() again.
     */
    private fun scheduleMaintenanceRetry() {
        if (!started.get() || !isPaired() || !maintenanceRetryGate.compareAndSet(false, true)) return
        val delayMs = MAINTENANCE_RETRY_DELAYS_MS[
            maintenanceRetryAttempt.coerceAtMost(MAINTENANCE_RETRY_DELAYS_MS.lastIndex)
        ]
        maintenanceRetryAttempt = (maintenanceRetryAttempt + 1)
            .coerceAtMost(MAINTENANCE_RETRY_DELAYS_MS.lastIndex)
        Log.i(TAG, "Scheduling maintenance retry in ${delayMs}ms (attempt=$maintenanceRetryAttempt).")
        maintenanceRetryJob = scope.launch {
            try {
                delay(delayMs)
            } finally {
                // The probe can fail immediately and schedule another retry.
                // Release this timer's gate before starting that probe.
                maintenanceRetryGate.set(false)
                maintenanceRetryJob = null
            }
            if (!currentCoroutineContext().isActive || !started.get() || !isPaired()) return@launch
            if (_status.value.state != DeveloperConnectionState.READY) {
                beginMaintenanceProbe()
            }
        }
    }

    private fun pairingFailureMessage(error: Throwable): String = when {
        containsCause(error) {
            it is UnsatisfiedLinkError ||
                it is ExceptionInInitializerError ||
                it is NoClassDefFoundError
        } -> "DHD's native pairing engine could not start. Reinstall the latest DHD build and try again."
        else -> "DHD pairing failed: ${rootMessage(error)}"
    }

    private fun containsCause(error: Throwable, predicate: (Throwable) -> Boolean): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (predicate(current)) return true
            current = current.cause
        }
        return false
    }

    internal companion object {
        const val TAG = "DhdAdbController"
        const val PREFERENCES_NAME = "dhd_adb_connection"
        const val KEY_PAIRED = "paired"
        const val CONNECT_DISCOVERY_TIMEOUT_MS = 7_000L
        const val PAIRING_DISCOVERY_TIMEOUT_MS = 30_000L
        const val MAINTENANCE_HEALTH_INTERVAL_MS = 15_000L
        val MAINTENANCE_RETRY_DELAYS_MS = longArrayOf(2_000L, 3_000L, 5_000L)
        const val PAIRING_SEARCHING_MESSAGE =
            "Open Wireless debugging → Pair device with pairing code. DHD is listening for the pairing service."
        const val PAIRING_SERVICE_FOUND_MESSAGE =
            "The Wireless Debugging pairing service was found. Enter the six-digit code shown by Android."
        const val PAIRING_REQUIRED_MESSAGE =
            INITIAL_PHONE_CONNECTION_MESSAGE
        const val MAINTENANCE_RESTART_MESSAGE =
            PHONE_ACCESS_INTERRUPTED_MESSAGE
        const val WIRELESS_DEBUGGING_REQUIRED_MESSAGE =
            PHONE_ACCESS_INTERRUPTED_MESSAGE
        val PAIRING_CODE_REGEX = Regex("\\d{6}")
    }
}
