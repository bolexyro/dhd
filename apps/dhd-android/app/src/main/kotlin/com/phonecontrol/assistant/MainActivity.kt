package com.phonecontrol.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.lifecycle.lifecycleScope
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.display.TaskPreviewState
import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.taskDisplayReference
import com.phonecontrol.assistant.overlay.OverlayPreferences
import com.phonecontrol.assistant.overlay.OverlayVisibilityGate
import com.phonecontrol.assistant.session.AssistantForegroundService
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.PhoneControlApp
import com.phonecontrol.assistant.ui.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.LiveDisplayPreviewStatus
import com.phonecontrol.assistant.ui.TaskDisplayLifecycle
import com.phonecontrol.assistant.ui.TaskDisplayUiRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CONVERSATION_EXPIRY_CHECK_INTERVAL_MS = 1_000L
internal const val PERMISSION_SETUP_PREFERENCES = "dhd_permission_setup"
internal const val KEY_FIRST_RUN_PERMISSION_ONBOARDING_COMPLETED = "first_run_permission_onboarding_completed"
internal const val KEY_NOTIFICATION_SETUP_STEP_HANDLED = "notification_setup_step_handled"
internal const val STATE_PERMISSION_SETUP_STEP = "permission_setup_step"
internal const val STATE_NOTIFICATION_SETUP_HANDLED = "notification_setup_step_handled"
internal const val STATE_PENDING_OVERLAY_ENABLE = "pending_overlay_enable"

class MainActivity : ComponentActivity() {
    private var pendingRequest: String? = null
    private var pendingConversationId: String? = null
    private var pendingReasoningEffort: String? = null
    private var pendingFastMode: Boolean = false
    private var overlayEnabled by mutableStateOf(false)
    private var overlayPermissionGranted by mutableStateOf(false)
    private var permissionSetupStep by mutableStateOf<PermissionSetupStep?>(null)
    private var pendingOverlayEnable = false
    private var notificationSetupStepHandled = false
    private var overlayActivityToken: OverlayVisibilityGate.Token? = null
    private var conversationExpiryMonitor: Job? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (permissionSetupStep == PermissionSetupStep.NOTIFICATIONS) {
            notificationSetupStepHandled = true
            persistNotificationSetupStepHandled()
            updatePermissionSetupStep()
            return@registerForActivityResult
        }
        if (granted) {
            pendingRequest?.let {
                launchSession(it, pendingConversationId, pendingReasoningEffort, pendingFastMode)
            }
        }
        pendingRequest = null
        pendingConversationId = null
        pendingReasoningEffort = null
        pendingFastMode = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionSetupStep = savedInstanceState
            ?.getString(STATE_PERMISSION_SETUP_STEP)
            ?.let { savedStep -> PermissionSetupStep.entries.firstOrNull { it.name == savedStep } }
            ?.takeUnless { it == PermissionSetupStep.COMPLETE }
        val permissionPreferences = getSharedPreferences(PERMISSION_SETUP_PREFERENCES, MODE_PRIVATE)
        notificationSetupStepHandled = savedInstanceState
            ?.getBoolean(STATE_NOTIFICATION_SETUP_HANDLED)
            ?: permissionPreferences.getBoolean(KEY_NOTIFICATION_SETUP_STEP_HANDLED, false)
        pendingOverlayEnable = savedInstanceState
            ?.getBoolean(STATE_PENDING_OVERLAY_ENABLE)
            ?: false
        enableEdgeToEdge()
        refreshOverlayState()
        maybeStartFirstRunPermissionSetup()
        val initialConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        val app = application as PhoneControlApplication
        val appPackageManager = packageManager
        setContent {
            val display by app.taskDisplayBackend.activeSession.collectAsState()
            val playback by app.taskDisplayBackend.previewState.collectAsState()
            val previewStates by app.taskDisplayBackend.previewStates.collectAsState()
            val backendDisplayRecords by app.taskDisplayBackend.displayRecords.collectAsState()
            val sessionState by app.sessionCoordinator.state.collectAsState()
            val events by app.sessionCoordinator.events.collectAsState()
            val pointerEvent by app.sessionCoordinator.pointerEvent.collectAsState()
            val purpose = sessionState.displayPurpose()
            val coordinatorSessionKey = sessionState.sessionIdOrNull
            // A new coordinator run can claim a retained display whose native
            // owner key belongs to the previous run. Resolve that binding for
            // the inline viewer so the UI follows the selected display rather
            // than assuming the two keys are identical.
            val resolvedDisplayForRun by produceState<TaskDisplaySession?>(
                initialValue = null,
                key1 = coordinatorSessionKey,
                key2 = display?.sessionKey,
                // Selecting a retained display with displayRef publishes its
                // updated registry record after the initial lookup. Include
                // the registry in the keys so the suspended lookup retries
                // once that binding becomes visible to the UI.
                key3 = backendDisplayRecords,
            ) {
                value = coordinatorSessionKey?.let { app.taskDisplayBackend.current(it) }
            }
            val displayForRun = selectDisplayForRun(
                resolvedDisplayForRun = resolvedDisplayForRun,
                activeDisplay = display,
                coordinatorSessionKey = coordinatorSessionKey,
                sessionState = sessionState,
            )
            val activeDisplayOwnerKey = displayForRun?.sessionKey
            val currentToolName = latestToolNameForRun(events, coordinatorSessionKey)
            val preview = displayForRun?.let { session ->
                livePreviewForRun(
                    session = session,
                    previewStates = previewStates,
                    playback = playback,
                    records = backendDisplayRecords,
                    coordinatorSessionKey = coordinatorSessionKey,
                    pointerEvent = pointerEvent,
                    purpose = purpose,
                    currentToolName = currentToolName,
                    appLabelFor = { packageName -> packageName.applicationLabel(appPackageManager) },
                )
            }
            val mappedRecords = backendDisplayRecords.map { record ->
                record.toUiRecord(
                    preview = previewStates[record.sessionKey],
                    packageManager = appPackageManager,
                    currentToolName = currentToolName.takeIf { record.sessionKey == activeDisplayOwnerKey },
                )
            }
            // A newly created session may be visible through activeSession a
            // frame before its durable registry record is published. Keep the
            // manager populated during that small handoff window.
            val displayRecordsForUi = displayForRun?.let { session ->
                val currentPackage = currentPackageForSession(session, backendDisplayRecords)
                mergeActiveDisplayRecord(
                    records = mappedRecords,
                    activeRecord = activeDisplayUiRecord(
                        session = session,
                        currentPackage = currentPackage,
                        appLabel = currentPackage.applicationLabel(appPackageManager),
                        sessionState = sessionState,
                        purpose = purpose,
                        currentToolName = currentToolName,
                        preview = preview,
                    ),
                    runIsActive = sessionState is SessionState.Running || sessionState is SessionState.Paused,
                    purpose = purpose,
                    currentToolName = currentToolName,
                    preview = preview,
                )
            } ?: mappedRecords
            PhoneControlApp(
                initialConversationId = initialConversationId,
                initialRoute = intent.getStringExtra(EXTRA_OPEN_ROUTE),
                onRunRequest = ::startSession,
                onStopSession = ::stopSession,
                onContinueSession = ::continueSession,
                onStartFresh = ::startFresh,
                onAcknowledgeAttention = { app.sessionCoordinator.acknowledgeAttention() },
                onSteerRequest = ::steerSession,
                onNotificationVisibilityChanged = { mainConversationVisible, attentionVisible ->
                    app.notificationVisibility.updateUi(mainConversationVisible, attentionVisible)
                },
                previewState = preview,
                displayRecords = displayRecordsForUi,
                onPreviewSurfaceAvailable = { surface ->
                    displayForRun?.let { app.attachTaskPreview(it, surface) }
                },
                onPreviewSurfaceDestroyed = { surface, release ->
                    app.detachTaskPreview(surface, release)
                },
                onTaskDisplaySurfaceAvailable = { record, surface ->
                    app.attachTaskPreview(record.sessionKey, surface)
                },
                onTaskDisplaySurfaceDestroyed = { _, surface, release ->
                    app.detachTaskPreview(surface, release)
                },
                onEndTaskDisplay = { record ->
                    app.endTaskDisplay(record.displayId, record.displayRef, record.sessionKey)
                },
                onRetryTaskDisplayPreview = { record ->
                    app.retryTaskPreview(record.sessionKey)
                },
                overlayEnabled = overlayEnabled,
                overlayPermissionGranted = overlayPermissionGranted,
                onSetOverlayEnabled = ::handleOverlayToggle,
                permissionSetupStep = permissionSetupStep,
                notificationsAllowed = hasNotificationPermission(),
                onPermissionSetupPrimaryAction = ::handlePermissionSetupPrimaryAction,
                onShowOverlayPermissionSetup = ::showOverlayPermissionSetup,
                onPermissionSetupBack = ::navigateBackInPermissionSetup,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as? PhoneControlApplication
        app?.let {
            it.notificationVisibility.setActivityVisible(true)
            // A conversation that aged out while the app was not visible is
            // expired immediately. The foreground monitor below handles the
            // separate case where the app stays open across the boundary.
            it.conversationStore.expireInactiveConversation()
            conversationExpiryMonitor?.cancel()
            conversationExpiryMonitor = lifecycleScope.launch {
                while (isActive) {
                    delay(CONVERSATION_EXPIRY_CHECK_INTERVAL_MS)
                    if (!it.conversationStore.conversationExpiryPrompt.value) {
                        it.conversationStore.promptForInactiveConversation()
                    }
                }
            }
        }
        if (overlayActivityToken == null) {
            overlayActivityToken = app?.overlayVisibilityGate?.acquire(
                com.phonecontrol.assistant.overlay.OverlayHideReason.DHD_ACTIVITY,
            )
        }
        (application as? PhoneControlApplication)?.let { app ->
            app.developerModeController.refresh()
            app.devBridgeServer.requestCodexWarmup()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayState()
        maybeStartFirstRunPermissionSetup()
    }

    override fun onStop() {
        conversationExpiryMonitor?.cancel()
        conversationExpiryMonitor = null
        (application as? PhoneControlApplication)?.notificationVisibility?.setActivityVisible(false)
        (application as? PhoneControlApplication)?.conversationStore?.dismissInactiveConversationPrompt()
        overlayActivityToken?.close()
        overlayActivityToken = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_OPEN_ROUTE)) {
            recreate()
            return
        }
        refreshOverlayState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PERMISSION_SETUP_STEP, permissionSetupStep?.name)
        outState.putBoolean(STATE_NOTIFICATION_SETUP_HANDLED, notificationSetupStepHandled)
        outState.putBoolean(STATE_PENDING_OVERLAY_ENABLE, pendingOverlayEnable)
        super.onSaveInstanceState(outState)
    }

    private fun startSession(
        request: String,
        conversationId: String?,
        reasoningEffort: String?,
        fastMode: Boolean,
    ) {
        if (request.isBlank()) return
        if (!hasNotificationPermission()) {
            pendingRequest = request
            pendingConversationId = conversationId
            pendingReasoningEffort = reasoningEffort
            pendingFastMode = fastMode
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchSession(request, conversationId, reasoningEffort, fastMode)
        }
    }

    /** Show the in-app explanation before asking Android for either permission. */
    private fun maybeStartFirstRunPermissionSetup() {
        val preferences = getSharedPreferences(PERMISSION_SETUP_PREFERENCES, MODE_PRIVATE)
        if (permissionSetupStep != null || preferences.getBoolean(KEY_FIRST_RUN_PERMISSION_ONBOARDING_COMPLETED, false)) {
            return
        }

        notificationSetupStepHandled = preferences.getBoolean(
            KEY_NOTIFICATION_SETUP_STEP_HANDLED,
            notificationSetupStepHandled,
        )
        val nextStep = firstRunPermissionSetupStep(
            onboardingCompleted = false,
            sdkInt = Build.VERSION.SDK_INT,
            notificationGranted = hasNotificationPermission(),
            overlayGranted = Settings.canDrawOverlays(this),
            notificationStepHandled = notificationSetupStepHandled,
        )
        if (nextStep == null) {
            markFirstRunPermissionSetupCompleted()
        } else {
            permissionSetupStep = nextStep
        }
    }

    private fun handlePermissionSetupPrimaryAction() {
        when (permissionSetupStep) {
            PermissionSetupStep.NOTIFICATIONS -> {
                if (hasNotificationPermission()) {
                    notificationSetupStepHandled = true
                    persistNotificationSetupStepHandled()
                    updatePermissionSetupStep()
                } else {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            PermissionSetupStep.OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    updatePermissionSetupStep()
                } else {
                    pendingOverlayEnable = true
                    openOverlayPermissionSettings()
                }
            }
            PermissionSetupStep.APP_ACCESS -> dismissPermissionSetup()
            PermissionSetupStep.COMPLETE -> dismissPermissionSetup()
            null -> Unit
        }
    }

    private fun showOverlayPermissionSetup() {
        if (permissionSetupStep != PermissionSetupStep.NOTIFICATIONS) return
        permissionSetupStep = PermissionSetupStep.OVERLAY
    }

    private fun navigateBackInPermissionSetup() {
        when (permissionSetupStep) {
            PermissionSetupStep.OVERLAY -> permissionSetupStep = PermissionSetupStep.NOTIFICATIONS
            PermissionSetupStep.APP_ACCESS,
            PermissionSetupStep.NOTIFICATIONS,
            PermissionSetupStep.COMPLETE,
            -> finish()
            null -> Unit
        }
    }

    private fun dismissPermissionSetup() {
        pendingOverlayEnable = false
        if (permissionSetupStep == PermissionSetupStep.APP_ACCESS ||
            currentPermissionSetupStep() == PermissionSetupStep.COMPLETE
        ) {
            markFirstRunPermissionSetupCompleted()
        }
        permissionSetupStep = null
    }

    private fun currentPermissionSetupStep(): PermissionSetupStep = nextPermissionSetupStep(
        sdkInt = Build.VERSION.SDK_INT,
        notificationGranted = hasNotificationPermission(),
        overlayGranted = Settings.canDrawOverlays(this),
        notificationStepHandled = notificationSetupStepHandled,
    )

    private fun updatePermissionSetupStep() {
        val nextStep = currentPermissionSetupStep()
        if (nextStep == PermissionSetupStep.COMPLETE) {
            permissionSetupStep = PermissionSetupStep.APP_ACCESS
        } else {
            permissionSetupStep = nextStep
        }
    }

    private fun persistNotificationSetupStepHandled() {
        getSharedPreferences(PERMISSION_SETUP_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_SETUP_STEP_HANDLED, notificationSetupStepHandled)
            .apply()
    }

    private fun markFirstRunPermissionSetupCompleted() {
        getSharedPreferences(PERMISSION_SETUP_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FIRST_RUN_PERMISSION_ONBOARDING_COMPLETED, true)
            .remove(KEY_NOTIFICATION_SETUP_STEP_HANDLED)
            .apply()
        notificationSetupStepHandled = false
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchSession(
        request: String,
        conversationId: String? = null,
        reasoningEffort: String? = null,
        fastMode: Boolean = false,
    ) {
        val intent = Intent(this, AssistantForegroundService::class.java)
            .setAction(AssistantForegroundService.ACTION_START)
            .putExtra(AssistantForegroundService.EXTRA_REQUEST, request)
        if (!conversationId.isNullOrBlank()) {
            intent.putExtra(AssistantForegroundService.EXTRA_CONVERSATION_ID, conversationId)
        }
        if (!reasoningEffort.isNullOrBlank()) {
            intent.putExtra(AssistantForegroundService.EXTRA_REASONING_EFFORT, reasoningEffort)
        }
        intent.putExtra(AssistantForegroundService.EXTRA_FAST_MODE, fastMode)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSession() {
        startService(
            Intent(this, AssistantForegroundService::class.java)
                .setAction(AssistantForegroundService.ACTION_STOP_USER),
        )
    }

    private fun startFresh() {
        val app = application as PhoneControlApplication
        app.startFresh()
        startService(
            Intent(this, AssistantForegroundService::class.java)
                .setAction(AssistantForegroundService.ACTION_START_FRESH),
        )
    }

    private fun continueSession() {
        val app = application as PhoneControlApplication
        if (app.sessionCoordinator.state.value !is SessionState.Stopped) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, AssistantForegroundService::class.java)
                .setAction(AssistantForegroundService.ACTION_CONTINUE),
        )
    }

    private fun steerSession(text: String): Boolean =
        (application as PhoneControlApplication).sessionCoordinator.enqueueSteer(text) != null

    private fun refreshOverlayState() {
        val granted = Settings.canDrawOverlays(this)
        overlayPermissionGranted = granted
        overlayEnabled = granted && OverlayPreferences.isEnabled(this)
        if (pendingOverlayEnable) {
            pendingOverlayEnable = false
            if (granted) {
                enableOverlay()
                updatePermissionSetupStep()
            }
        } else if (overlayEnabled) {
            startService(
                Intent(this, AssistantForegroundService::class.java)
                    .setAction(AssistantForegroundService.ACTION_ENABLE_OVERLAY),
            )
        }
    }

    private fun handleOverlayToggle(enabled: Boolean) {
        if (!enabled) {
            pendingOverlayEnable = false
            OverlayPreferences.setEnabled(this, false)
            overlayEnabled = false
            startService(
                Intent(this, AssistantForegroundService::class.java)
                    .setAction(AssistantForegroundService.ACTION_DISABLE_OVERLAY),
            )
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            notificationSetupStepHandled = true
            persistNotificationSetupStepHandled()
            permissionSetupStep = PermissionSetupStep.OVERLAY
            return
        }
        enableOverlay()
    }

    private fun openOverlayPermissionSettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun enableOverlay() {
        OverlayPreferences.setEnabled(this, true)
        overlayEnabled = true
        overlayPermissionGranted = true
        startService(
            Intent(this, AssistantForegroundService::class.java)
                .setAction(AssistantForegroundService.ACTION_ENABLE_OVERLAY),
        )
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "com.phonecontrol.assistant.extra.CONVERSATION_ID"
        const val EXTRA_OPEN_ROUTE = "com.phonecontrol.assistant.extra.OPEN_ROUTE"
    }
}

internal fun SessionState.displayPurpose(): String? = when (this) {
    is SessionState.Running -> currentPurpose
    is SessionState.Paused -> currentPurpose
    is SessionState.Stopped -> reason
    is SessionState.Completed -> "Task complete"
    SessionState.Idle -> null
}

internal fun selectDisplayForRun(
    resolvedDisplayForRun: TaskDisplaySession?,
    activeDisplay: TaskDisplaySession?,
    coordinatorSessionKey: String?,
    sessionState: SessionState,
): TaskDisplaySession? = resolvedDisplayForRun
    ?: activeDisplay?.takeIf { it.sessionKey == coordinatorSessionKey }
    // Continue creates a fresh coordinator run before the first
    // tool has a chance to claim the retained display. Keep the
    // previous backend session rendered during that handoff;
    // this callback only attaches the read-only preview surface.
    ?: activeDisplay?.takeIf {
        (sessionState as? SessionState.Running)?.isContinuation == true
    }

internal fun latestToolNameForRun(events: List<ActivityEvent>, sessionKey: String?): String? =
    sessionKey?.let {
        events.asReversed()
            .firstOrNull { event ->
                event.sessionId == sessionKey && !event.toolName.isNullOrBlank() && !ToolNames.isCloseDisplay(event.toolName)
            }
            ?.toolName
    }

internal fun currentPackageForSession(
    session: TaskDisplaySession,
    records: List<TaskDisplayRecord>,
): String = records
    .firstOrNull { it.sessionKey == session.sessionKey }
    ?.packageName ?: session.packageName

internal fun livePreviewForRun(
    session: TaskDisplaySession,
    previewStates: Map<String, TaskPreviewState>,
    playback: TaskPreviewState,
    records: List<TaskDisplayRecord>,
    coordinatorSessionKey: String?,
    pointerEvent: TaskPointerEvent?,
    purpose: String?,
    currentToolName: String?,
    appLabelFor: (String) -> String?,
): LiveDisplayPreviewState {
    // The global preview state is retained for the legacy single
    // viewer. Once a displayRef selects a retained display, use
    // its per-session state so another display's decoder cannot
    // make this preview appear stuck in Connecting.
    val playbackForSession = previewStates[session.sessionKey]
        ?: playback.forSession(session.sessionKey)
    val error = playbackForSession as? TaskPreviewState.Error
    val ended = playbackForSession as? TaskPreviewState.Ended
    val currentPackage = currentPackageForSession(session, records)
    val appLabel = appLabelFor(currentPackage)
    return LiveDisplayPreviewState(
        status = when {
            error?.sessionKey == session.sessionKey -> LiveDisplayPreviewStatus.ERROR
            ended?.session?.sessionKey == session.sessionKey -> LiveDisplayPreviewStatus.UNAVAILABLE
            (playbackForSession as? TaskPreviewState.Attached)?.session == session ->
                LiveDisplayPreviewStatus.LIVE
            else -> LiveDisplayPreviewStatus.CONNECTING
        },
        message = error?.takeIf { it.sessionKey == session.sessionKey }?.message
            ?: ended?.takeIf { it.session.sessionKey == session.sessionKey }?.message,
        aspectRatio = session.geometry.width.toFloat() / session.geometry.height,
        appLabel = appLabel,
        sessionKey = session.sessionKey,
        runSessionKey = coordinatorSessionKey,
        pointerEvent = pointerEvent?.takeIf {
            it.sessionId == coordinatorSessionKey || it.sessionId == session.sessionKey
        },
        purpose = purpose,
        currentToolName = currentToolName,
    )
}

internal fun activeDisplayUiRecord(
    session: TaskDisplaySession,
    currentPackage: String,
    appLabel: String?,
    sessionState: SessionState,
    purpose: String?,
    currentToolName: String?,
    preview: LiveDisplayPreviewState?,
): TaskDisplayUiRecord = TaskDisplayUiRecord(
    sessionKey = session.sessionKey,
    taskId = session.taskId,
    packageName = currentPackage,
    appLabel = appLabel,
    displayId = session.displayId,
    displayRef = taskDisplayReference(session.sessionKey, session.displayId),
    geometry = session.geometry,
    lifecycle = sessionState.toUiDisplayLifecycle(),
    currentPurpose = purpose,
    createdAtEpochMs = sessionState.startedAtEpochMsOrZero(),
    currentToolName = currentToolName,
    previewState = preview,
)

internal fun mergeActiveDisplayRecord(
    records: List<TaskDisplayUiRecord>,
    activeRecord: TaskDisplayUiRecord,
    runIsActive: Boolean,
    purpose: String?,
    currentToolName: String?,
    preview: LiveDisplayPreviewState?,
): List<TaskDisplayUiRecord> {
    val merged = records.toMutableList()
    val index = merged.indexOfFirst { it.sessionKey == activeRecord.sessionKey }
    if (index >= 0) {
        val persisted = merged[index]
        // The coordinator is authoritative while this run is
        // active. Once it reaches a terminal state, retain the
        // backend's precise completed/failed/stopped status and
        // purpose instead of replacing it with a generic state
        // from the UI process.
        merged[index] = if (runIsActive) {
            persisted.copy(
                lifecycle = activeRecord.lifecycle,
                currentPurpose = purpose ?: persisted.currentPurpose,
                currentToolName = currentToolName ?: persisted.currentToolName,
                previewState = preview ?: persisted.previewState,
            )
        } else {
            persisted.copy(previewState = preview ?: persisted.previewState)
        }
    } else {
        merged += activeRecord
    }
    return merged
}

private fun TaskDisplayRecord.toUiRecord(
    preview: TaskPreviewState?,
    packageManager: PackageManager,
    currentToolName: String? = null,
): TaskDisplayUiRecord = TaskDisplayUiRecord(
    sessionKey = sessionKey,
    taskId = taskId,
    packageName = packageName,
    appLabel = packageName.applicationLabel(packageManager),
    displayId = displayId,
    displayRef = this.displayRef,
    geometry = geometry,
    lifecycle = status.toUiLifecycle(),
    currentPurpose = lastPurpose,
    createdAtEpochMs = createdAtEpochMs,
    terminalAtEpochMs = terminalAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    error = error,
    currentToolName = currentToolName,
    previewState = preview.toUiPreview(
        geometry = geometry,
        sessionKey = sessionKey,
        appLabel = packageName.applicationLabel(packageManager),
        purpose = lastPurpose,
        currentToolName = currentToolName,
    ),
)

internal fun TaskPreviewState?.toUiPreview(
    geometry: com.phonecontrol.assistant.execution.TaskDisplayGeometry,
    sessionKey: String,
    appLabel: String?,
    purpose: String,
    currentToolName: String? = null,
): LiveDisplayPreviewState? {
    val ratio = geometry.width.toFloat() / geometry.height.toFloat()
    return when (this) {
        is TaskPreviewState.Connecting -> LiveDisplayPreviewState(
            status = LiveDisplayPreviewStatus.CONNECTING,
            appLabel = appLabel,
            aspectRatio = ratio,
            sessionKey = sessionKey,
            purpose = purpose,
            currentToolName = currentToolName,
        )
        is TaskPreviewState.Attached -> LiveDisplayPreviewState(
            status = LiveDisplayPreviewStatus.LIVE,
            appLabel = appLabel,
            aspectRatio = ratio,
            sessionKey = sessionKey,
            purpose = purpose,
            currentToolName = currentToolName,
        )
        is TaskPreviewState.Error -> LiveDisplayPreviewState.error(
            message = message,
            appLabel = appLabel,
            aspectRatio = ratio,
            sessionKey = sessionKey,
        ).copy(purpose = purpose, currentToolName = currentToolName)
        is TaskPreviewState.Ended -> LiveDisplayPreviewState.unavailable(
            message = message,
            aspectRatio = ratio,
            sessionKey = sessionKey,
            currentToolName = currentToolName,
        ).copy(appLabel = appLabel, purpose = purpose)
        else -> null
    }
}

private fun String.applicationLabel(packageManager: PackageManager): String? {
    if (isBlank()) return null
    return runCatching {
        val info = packageManager.getApplicationInfo(this, 0)
        packageManager.getApplicationLabel(info).toString().takeIf(String::isNotBlank)
    }.getOrNull()
}

internal fun TaskDisplayStatus.toUiLifecycle(): TaskDisplayLifecycle = when (this) {
    TaskDisplayStatus.RUNNING -> TaskDisplayLifecycle.RUNNING
    TaskDisplayStatus.PAUSED -> TaskDisplayLifecycle.PAUSED
    TaskDisplayStatus.COMPLETED -> TaskDisplayLifecycle.COMPLETED
    TaskDisplayStatus.FAILED -> TaskDisplayLifecycle.FAILED
    TaskDisplayStatus.STOPPED -> TaskDisplayLifecycle.STOPPED
    TaskDisplayStatus.UNAVAILABLE -> TaskDisplayLifecycle.UNAVAILABLE
    TaskDisplayStatus.ENDED -> TaskDisplayLifecycle.ENDED
    TaskDisplayStatus.EXPIRED -> TaskDisplayLifecycle.EXPIRED
}

internal fun SessionState.toUiDisplayLifecycle(): TaskDisplayLifecycle = when (this) {
    is SessionState.Running -> if (attentionReason != null) {
        TaskDisplayLifecycle.PAUSED
    } else {
        TaskDisplayLifecycle.RUNNING
    }
    is SessionState.Paused -> TaskDisplayLifecycle.PAUSED
    is SessionState.Stopped -> TaskDisplayLifecycle.STOPPED
    is SessionState.Completed -> TaskDisplayLifecycle.COMPLETED
    SessionState.Idle -> TaskDisplayLifecycle.UNAVAILABLE
}

internal fun SessionState.startedAtEpochMsOrZero(): Long = when (this) {
    is SessionState.Running -> startedAtEpochMs
    is SessionState.Paused -> startedAtEpochMs
    else -> 0L
}

internal fun TaskPreviewState.forSession(sessionKey: String): TaskPreviewState? = when (this) {
    TaskPreviewState.Detached -> null
    is TaskPreviewState.Connecting -> takeIf { session.sessionKey == sessionKey }
    is TaskPreviewState.Attached -> takeIf { session.sessionKey == sessionKey }
    is TaskPreviewState.Ended -> takeIf { session.sessionKey == sessionKey }
    is TaskPreviewState.Error -> takeIf { this.sessionKey == sessionKey }
}
