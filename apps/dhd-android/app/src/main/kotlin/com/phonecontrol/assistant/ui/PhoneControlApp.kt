package com.phonecontrol.assistant.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.Surface as AndroidSurface
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.phonecontrol.assistant.PermissionSetupStep
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.chat.ChatScreen
import com.phonecontrol.assistant.ui.chat.ChatViewModel
import com.phonecontrol.assistant.ui.chat.ConversationExpiryDialog
import com.phonecontrol.assistant.ui.components.reasoning.normalizeReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.selectReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.selectedReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.setReasoningEffortVisible
import com.phonecontrol.assistant.ui.components.reasoning.visibleReasoningEffortList
import com.phonecontrol.assistant.ui.displays.FullScreenLiveDisplayViewer
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.displays.TaskDisplayUiRecord
import com.phonecontrol.assistant.ui.displays.TaskDisplaysSheet
import com.phonecontrol.assistant.ui.displays.displayRecordsWithPreviewFallback
import com.phonecontrol.assistant.ui.displays.surface.PreviewSurfaceDestroyed
import com.phonecontrol.assistant.ui.displays.viewerPreviewState
import com.phonecontrol.assistant.ui.navigation.AppRoutes
import com.phonecontrol.assistant.ui.navigation.supportedInitialRoute
import com.phonecontrol.assistant.ui.onboarding.PermissionOnboardingScreen
import com.phonecontrol.assistant.ui.pairing.CompanionInstructionsScreen
import com.phonecontrol.assistant.ui.pairing.CompanionPairingApprovalDialog
import com.phonecontrol.assistant.ui.pairing.PairingScreen
import com.phonecontrol.assistant.ui.settings.ApprovedAppsScreen
import com.phonecontrol.assistant.ui.settings.SettingsScreen
import com.phonecontrol.assistant.ui.theme.DhdTheme
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun PhoneControlApp(
    initialRoute: String? = null,
    onRunRequest: (String, String?, String?, Boolean) -> Unit,
    restoredRequest: String? = null,
    onRestoredRequestConsumed: () -> Unit = {},
    apps: List<InstalledUserApp> = emptyList(),
    onStopSession: () -> Unit,
    onContinueSession: () -> Unit = {},
    onStartFresh: (() -> Unit)? = null,
    onAcknowledgeAttention: () -> Boolean,
    onSteerRequest: (String) -> Boolean,
    previewState: LiveDisplayPreviewState? = null,
    onPreviewSurfaceAvailable: (AndroidSurface) -> Unit = {},
    onPreviewSurfaceDestroyed: PreviewSurfaceDestroyed = { _, release -> release() },
    /** Display records supplied by the lifecycle/backend layer. */
    displayRecords: List<TaskDisplayUiRecord> = emptyList(),
    onTaskDisplaySurfaceAvailable: (TaskDisplayUiRecord, AndroidSurface) -> Unit = { _, surface ->
        onPreviewSurfaceAvailable(surface)
    },
    onTaskDisplaySurfaceDestroyed: (TaskDisplayUiRecord, AndroidSurface, () -> Unit) -> Unit = { _, surface, release ->
        onPreviewSurfaceDestroyed(surface, release)
    },
    onEndTaskDisplay: (TaskDisplayUiRecord) -> Unit = {},
    onRetryTaskDisplayPreview: (TaskDisplayUiRecord) -> Unit = {},
    overlayEnabled: Boolean = false,
    overlayPermissionGranted: Boolean = false,
    onSetOverlayEnabled: (Boolean) -> Unit = {},
    permissionSetupStep: PermissionSetupStep? = null,
    onPermissionSetupPrimaryAction: () -> Unit = {},
    onShowOverlayPermissionSetup: () -> Unit = {},
    onPermissionSetupBack: () -> Unit = {},
    onNotificationVisibilityChanged: (mainConversationVisible: Boolean, attentionVisible: Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PhoneControlApplication).container
    val uiPreferencesRepository = container.uiPreferencesRepository
    val uiPreferences by uiPreferencesRepository.state.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val themeMode = ThemeMode.fromStorage(uiPreferences.themeMode)
    val isDarkMode = themeMode.isDark(isSystemDark)
    val visibleReasoningEfforts = uiPreferences.visibleReasoningEffortList
    val reasoningEffort = uiPreferences.selectedReasoningEffort
    val fastMode = uiPreferences.fastMode
    LaunchedEffect(uiPreferences) {
        uiPreferencesRepository.normalizeReasoningEffort()
    }
    val setThemeMode: (ThemeMode) -> Unit = { mode -> uiPreferencesRepository.setThemeMode(mode.storageValue) }
    val setReasoningEffort: (ReasoningEffort) -> Unit = uiPreferencesRepository::selectReasoningEffort
    val setFastMode: (Boolean) -> Unit = uiPreferencesRepository::setFastMode
    val setReasoningEffortVisibility: (ReasoningEffort, Boolean) -> Unit =
        uiPreferencesRepository::setReasoningEffortVisible

    val coordinator = container.sessionCoordinator
    val coordinatorState by coordinator.state.collectAsState()
    val conversationRepository = container.conversationRepository
    val conversationScope = rememberCoroutineScope()
    val showConversationExpiryPrompt by conversationRepository.conversationExpiryPrompt.collectAsState()
    val permissions = container.appPermissionRepository
    val developerModeController = container.phoneAccessController
    val developerStatus by developerModeController.status.collectAsState()
    val companionConnected by container.companionBridgeServer.companionConnected.collectAsState()
    val pendingCompanionPairing by container.companionBridgeServer.pendingCompanionPairing.collectAsState()
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val mainConversationVisible = permissionSetupStep == null && currentRoute == AppRoutes.MAIN
    val attentionVisible = mainConversationVisible && when (val state = coordinatorState) {
        is SessionState.Running -> !state.attentionReason.isNullOrBlank()
        is SessionState.Paused -> !state.attentionReason.isNullOrBlank()
        else -> false
    }
    LaunchedEffect(mainConversationVisible, attentionVisible) {
        onNotificationVisibilityChanged(mainConversationVisible, attentionVisible)
    }
    DisposableEffect(Unit) {
        onDispose { onNotificationVisibilityChanged(false, false) }
    }
    val initialNavigationRoute = supportedInitialRoute(initialRoute)
    var viewerSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var taskDisplaysSheetVisible by rememberSaveable { mutableStateOf(false) }
    val openTaskDisplays: () -> Unit = { taskDisplaysSheetVisible = true }
    val handleStartFresh: () -> Unit = {
        viewerSessionKey = null
        taskDisplaysSheetVisible = false
        if (onStartFresh != null) {
            onStartFresh()
        } else {
            container.startFresh()
        }
    }

    val visibleDisplayRecords = displayRecordsWithPreviewFallback(displayRecords, previewState)
    val viewerRecord = viewerSessionKey?.let { key ->
        visibleDisplayRecords.firstOrNull { it.sessionKey == key }
    }
    val viewerState = viewerPreviewState(viewerRecord, previewState)

    DhdTheme(isDarkMode = isDarkMode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = LocalAssistantColors.current.background,
            ) {
                BackHandler(enabled = permissionSetupStep != null) {
                    onPermissionSetupBack()
                }
                if (permissionSetupStep != null) {
                    PermissionOnboardingScreen(
                        step = permissionSetupStep,
                        apps = apps,
                        permissions = permissions,
                        onPrimaryAction = onPermissionSetupPrimaryAction,
                        onShowOverlayStep = onShowOverlayPermissionSetup,
                        onBack = onPermissionSetupBack,
                    )
                } else {
                NavHost(
                    navController = navController,
                    startDestination = AppRoutes.MAIN,
                    enterTransition = {
                        slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280)) + fadeIn(animationSpec = tween(280))
                    },
                    exitTransition = {
                        slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(280)) + fadeOut(animationSpec = tween(280))
                    },
                    popEnterTransition = {
                        slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(280)) + fadeIn(animationSpec = tween(280))
                    },
                    popExitTransition = {
                        slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(280)) + fadeOut(animationSpec = tween(280))
                    },
                ) {
                    composable(AppRoutes.MAIN) {
                        ChatScreen(
                            viewModel = viewModel(factory = ChatViewModel.factory(container)),
                            onRunRequest = onRunRequest,
                            restoredRequest = restoredRequest,
                            onRestoredRequestConsumed = onRestoredRequestConsumed,
                            reasoningEffort = reasoningEffort,
                            visibleReasoningEfforts = visibleReasoningEfforts,
                            onSelectReasoningEffort = setReasoningEffort,
                            fastMode = fastMode,
                            onSetFastMode = setFastMode,
                            onStopSession = onStopSession,
                            onContinueSession = onContinueSession,
                            onAcknowledgeAttention = onAcknowledgeAttention,
                            onSteerRequest = onSteerRequest,
                            onOpenSettings = { navController.navigate(AppRoutes.SETTINGS) },
                            onOpenPhoneAccess = { navController.navigate(AppRoutes.PAIRING) },
                            onOpenTaskDisplays = openTaskDisplays,
                            onStartFresh = handleStartFresh,
                            developerStatus = developerStatus,
                            companionConnected = companionConnected,
                            onOpenCompanion = { navController.navigate(AppRoutes.COMPANION) },
                            previewState = previewState,
                            onPreviewSurfaceAvailable = onPreviewSurfaceAvailable,
                            onPreviewSurfaceDestroyed = onPreviewSurfaceDestroyed,
                            onOpenPreview = { sessionKey -> viewerSessionKey = sessionKey },
                            expandedPreviewSessionKey = viewerSessionKey,
                        )
                    }

                    composable(AppRoutes.SETTINGS) {
                        SettingsScreen(
                            apps = apps,
                            permissions = permissions,
                            developerStatus = developerStatus,
                            companionConnected = companionConnected,
                            themeMode = themeMode,
                            onSelectThemeMode = setThemeMode,
                            visibleReasoningEfforts = visibleReasoningEfforts,
                            onSetReasoningEffortVisibility = setReasoningEffortVisibility,
                            onOpenPairing = { navController.navigate(AppRoutes.PAIRING) },
                            onOpenApprovedApps = { navController.navigate(AppRoutes.APPROVED_APPS) },
                            onOpenCompanion = { navController.navigate(AppRoutes.COMPANION) },
                            overlayEnabled = overlayEnabled,
                            overlayPermissionGranted = overlayPermissionGranted,
                            onSetOverlayEnabled = onSetOverlayEnabled,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(AppRoutes.PAIRING) {
                        PairingScreen(
                            status = developerStatus,
                            onStartPairingNotification = { developerModeController.startPairingNotification() },
                            onOpenDeveloperOptions = { openDeveloperOptions(context) },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(AppRoutes.COMPANION) {
                        if (companionConnected) {
                            LaunchedEffect(companionConnected) {
                                navController.popBackStack()
                            }
                        } else {
                            CompanionInstructionsScreen(
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }

                    composable(AppRoutes.APPROVED_APPS) {
                        ApprovedAppsScreen(
                            apps = apps,
                            permissions = permissions,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }

                CompanionPairingApprovalDialog(
                    pending = pendingCompanionPairing,
                    bridgeServer = container.companionBridgeServer,
                )

                LaunchedEffect(initialNavigationRoute) {
                    initialNavigationRoute?.let { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                    }
                }

                if (taskDisplaysSheetVisible) {
                    TaskDisplaysSheet(
                        records = visibleDisplayRecords,
                        onView = { record ->
                            taskDisplaysSheetVisible = false
                            viewerSessionKey = record.sessionKey
                        },
                        onEnd = onEndTaskDisplay,
                        onBack = { taskDisplaysSheetVisible = false },
                    )
                }

                if (viewerRecord != null && viewerState != null) {
                    FullScreenLiveDisplayViewer(
                        record = viewerRecord,
                        state = viewerState,
                        onDismiss = { viewerSessionKey = null },
                        onSurfaceAvailable = { surface ->
                            onTaskDisplaySurfaceAvailable(viewerRecord, surface)
                        },
                        onSurfaceDestroyed = { surface, release ->
                            onTaskDisplaySurfaceDestroyed(viewerRecord, surface, release)
                        },
                        onRetry = { onRetryTaskDisplayPreview(viewerRecord) },
                        onAcknowledgeAttention = onAcknowledgeAttention,
                        onStopSession = onStopSession,
                        onEndTaskDisplay = { record ->
                            viewerSessionKey = null
                            onEndTaskDisplay(record)
                        },
                    )
                }

                if (showConversationExpiryPrompt) {
                    ConversationExpiryDialog(
                        onKeep = { conversationScope.launch { conversationRepository.keepInactiveConversation() } },
                        onClear = { conversationScope.launch { conversationRepository.expireInactiveConversation() } },
                    )
                }
                }
            }
    }
}

private fun openDeveloperOptions(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            },
        )
    }
}
