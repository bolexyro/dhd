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
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import com.phonecontrol.assistant.data.PermissionSetupRepository
import com.phonecontrol.assistant.overlay.OverlayPreferences
import com.phonecontrol.assistant.overlay.OverlayVisibilityGate
import com.phonecontrol.assistant.session.SessionCommands
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.AppViewModel
import com.phonecontrol.assistant.ui.PhoneControlApp
import com.phonecontrol.assistant.ui.displays.applicationLabel
import com.phonecontrol.assistant.ui.displays.mapDisplayUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CONVERSATION_EXPIRY_CHECK_INTERVAL_MS = 1_000L
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
    private val sessionCommands = SessionCommands(this)
    private val appViewModel: AppViewModel by viewModels {
        AppViewModel.factory((application as PhoneControlApplication).container)
    }
    private val permissionSetup: PermissionSetupRepository
        get() = (application as PhoneControlApplication).container.permissionSetupRepository
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
        notificationSetupStepHandled = savedInstanceState
            ?.getBoolean(STATE_NOTIFICATION_SETUP_HANDLED)
            ?: permissionSetup.isNotificationStepHandled()
        pendingOverlayEnable = savedInstanceState
            ?.getBoolean(STATE_PENDING_OVERLAY_ENABLE)
            ?: false
        enableEdgeToEdge()
        refreshOverlayState()
        maybeStartFirstRunPermissionSetup()
        val app = (application as PhoneControlApplication).container
        val appPackageManager = packageManager
        setContent {
            val uiState by appViewModel.uiState.collectAsState()
            val displayUi = mapDisplayUi(
                sources = uiState.displaySources,
                appLabelFor = { packageName -> packageName.applicationLabel(appPackageManager) },
            )
            val displayForRun = displayUi.displayForRun
            PhoneControlApp(
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
                previewState = displayUi.previewState,
                displayRecords = displayUi.displayRecords,
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
                onPermissionSetupPrimaryAction = ::handlePermissionSetupPrimaryAction,
                onShowOverlayPermissionSetup = ::showOverlayPermissionSetup,
                onPermissionSetupBack = ::navigateBackInPermissionSetup,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val app = (application as? PhoneControlApplication)?.container
        app?.let {
            it.notificationVisibility.setActivityVisible(true)
            // A conversation that aged out while the app was not visible is
            // expired immediately. The foreground monitor below handles the
            // separate case where the app stays open across the boundary.
            conversationExpiryMonitor?.cancel()
            conversationExpiryMonitor = lifecycleScope.launch {
                it.conversationRepository.expireInactiveConversation()
                while (isActive) {
                    delay(CONVERSATION_EXPIRY_CHECK_INTERVAL_MS)
                    if (!it.conversationRepository.conversationExpiryPrompt.value) {
                        it.conversationRepository.promptForInactiveConversation()
                    }
                }
            }
        }
        if (overlayActivityToken == null) {
            overlayActivityToken = app?.overlayVisibilityGate?.acquire(
                com.phonecontrol.assistant.overlay.OverlayHideReason.DHD_ACTIVITY,
            )
        }
        (application as? PhoneControlApplication)?.container?.let { app ->
            app.phoneAccessController.refresh()
            app.companionBridgeServer.requestCodexWarmup()
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
        (application as? PhoneControlApplication)?.container?.notificationVisibility?.setActivityVisible(false)
        (application as? PhoneControlApplication)?.container?.conversationRepository?.dismissInactiveConversationPrompt()
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
        if (permissionSetupStep != null || permissionSetup.isOnboardingCompleted()) {
            return
        }

        notificationSetupStepHandled = permissionSetup.isNotificationStepHandled(notificationSetupStepHandled)
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
        permissionSetup.setNotificationStepHandled(notificationSetupStepHandled)
    }

    private fun markFirstRunPermissionSetupCompleted() {
        permissionSetup.markOnboardingCompleted()
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
        sessionCommands.start(request, conversationId, reasoningEffort, fastMode)
    }

    private fun stopSession() {
        sessionCommands.stop()
    }

    private fun startFresh() {
        val app = (application as PhoneControlApplication).container
        app.startFresh()
        sessionCommands.startFresh()
    }

    private fun continueSession() {
        val app = (application as PhoneControlApplication).container
        if (app.sessionCoordinator.state.value !is SessionState.Stopped) return
        sessionCommands.continueStopped()
    }

    private fun steerSession(text: String): Boolean =
        (application as PhoneControlApplication).container.sessionCoordinator.enqueueSteer(text) != null

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
            sessionCommands.enableOverlay()
        }
    }

    private fun handleOverlayToggle(enabled: Boolean) {
        if (!enabled) {
            pendingOverlayEnable = false
            OverlayPreferences.setEnabled(this, false)
            overlayEnabled = false
            sessionCommands.disableOverlay()
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
        sessionCommands.enableOverlay()
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "com.phonecontrol.assistant.extra.CONVERSATION_ID"
        const val EXTRA_OPEN_ROUTE = "com.phonecontrol.assistant.extra.OPEN_ROUTE"
    }
}
