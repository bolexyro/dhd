package com.phonecontrol.assistant.ui

import android.app.Activity
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.phonecontrol.assistant.PermissionSetupStep
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.apps.InstalledAppsRepository
import com.phonecontrol.assistant.data.UiPreferencesRepository.Companion.KEY_FAST_MODE
import com.phonecontrol.assistant.data.UiPreferencesRepository.Companion.KEY_REASONING_EFFORT
import com.phonecontrol.assistant.data.UiPreferencesRepository.Companion.KEY_THEME_MODE
import com.phonecontrol.assistant.data.UiPreferencesRepository.Companion.KEY_VISIBLE_REASONING_EFFORTS
import com.phonecontrol.assistant.data.UiPreferencesRepository.Companion.PREFS_NAME
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.components.reasoning.effectiveReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.visibleReasoningEffortsFromStorage
import com.phonecontrol.assistant.ui.displays.FullScreenLiveDisplayViewer
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.displays.TaskDisplayUiRecord
import com.phonecontrol.assistant.ui.displays.TaskDisplaysScreen
import com.phonecontrol.assistant.ui.displays.displayRecordsWithPreviewFallback
import com.phonecontrol.assistant.ui.displays.surface.PreviewSurfaceDestroyed
import com.phonecontrol.assistant.ui.displays.viewerPreviewState
import com.phonecontrol.assistant.ui.navigation.AppRoutes
import com.phonecontrol.assistant.ui.navigation.supportedInitialRoute
import com.phonecontrol.assistant.ui.theme.DarkAssistantColors
import com.phonecontrol.assistant.ui.theme.LightAssistantColors
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.ThemeMode

@Composable
fun PhoneControlApp(
    initialConversationId: String? = null,
    initialRoute: String? = null,
    onRunRequest: (String, String?, String?, Boolean) -> Unit,
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
    notificationsAllowed: Boolean = true,
    onPermissionSetupPrimaryAction: () -> Unit = {},
    onShowOverlayPermissionSetup: () -> Unit = {},
    onPermissionSetupBack: () -> Unit = {},
    onNotificationVisibilityChanged: (mainConversationVisible: Boolean, attentionVisible: Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val isSystemDark = isSystemInDarkTheme()
    var themeMode by rememberSaveable {
        mutableStateOf(ThemeMode.fromStorage(prefs.getString(KEY_THEME_MODE, "dark")))
    }
    var reasoningEffortValue by rememberSaveable {
        mutableStateOf(
            ReasoningEffort.fromStorage(
                prefs.getString(KEY_REASONING_EFFORT, ReasoningEffort.default.storageValue),
            ).storageValue,
        )
    }
    var visibleReasoningEffortValues by rememberSaveable {
        mutableStateOf(
            visibleReasoningEffortsFromStorage(
                prefs.getString(KEY_VISIBLE_REASONING_EFFORTS, null),
            ).map(ReasoningEffort::storageValue),
        )
    }
    var fastMode by rememberSaveable {
        mutableStateOf(prefs.getBoolean(KEY_FAST_MODE, false))
    }

    val isDarkMode = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val setThemeMode: (ThemeMode) -> Unit = { mode ->
        themeMode = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.storageValue).apply()
    }
    val visibleReasoningEfforts = visibleReasoningEffortsFromStorage(
        visibleReasoningEffortValues.joinToString(","),
    )
    val reasoningEffort = effectiveReasoningEffort(reasoningEffortValue, visibleReasoningEfforts)
    LaunchedEffect(visibleReasoningEfforts, reasoningEffortValue) {
        if (reasoningEffortValue != reasoningEffort.storageValue) {
            reasoningEffortValue = reasoningEffort.storageValue
            prefs.edit().putString(KEY_REASONING_EFFORT, reasoningEffort.storageValue).apply()
        }
    }
    val setReasoningEffort: (ReasoningEffort) -> Unit = { effort ->
        if (effort in visibleReasoningEfforts) {
            reasoningEffortValue = effort.storageValue
            prefs.edit().putString(KEY_REASONING_EFFORT, effort.storageValue).apply()
        }
    }
    val setFastMode: (Boolean) -> Unit = { enabled ->
        fastMode = enabled
        prefs.edit().putBoolean(KEY_FAST_MODE, enabled).apply()
    }
    val setReasoningEffortVisibility: (ReasoningEffort, Boolean) -> Unit = { effort, visible ->
        val current = visibleReasoningEfforts.toSet()
        val next = if (visible) current + effort else current - effort
        if (next.isNotEmpty()) {
            val ordered = ReasoningEffort.entries.filter { it in next }
            visibleReasoningEffortValues = ordered.map(ReasoningEffort::storageValue)
            prefs.edit()
                .putString(KEY_VISIBLE_REASONING_EFFORTS, ordered.joinToString(",") { it.storageValue })
                .apply()
            if (reasoningEffort !in ordered) {
                val fallback = ordered.first()
                reasoningEffortValue = fallback.storageValue
                prefs.edit().putString(KEY_REASONING_EFFORT, fallback.storageValue).apply()
            }
        }
    }

    val container = (context.applicationContext as PhoneControlApplication).container
    val coordinator = container.sessionCoordinator
    val coordinatorState by coordinator.state.collectAsState()
    val conversationStore = container.conversationStore
    val showConversationExpiryPrompt by conversationStore.conversationExpiryPrompt.collectAsState()
    val permissions = container.appPermissionRepository
    val developerModeController = container.phoneAccessController
    val developerStatus by developerModeController.status.collectAsState()
    val companionConnected by container.companionBridgeServer.companionConnected.collectAsState()
    val pendingCompanionPairing by container.companionBridgeServer.pendingCompanionPairing.collectAsState()
    val apps = remember { InstalledAppsRepository(context).listLaunchableUserApps() }
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

    val assistantColors = if (isDarkMode) DarkAssistantColors else LightAssistantColors
    val materialColors = if (isDarkMode) {
        darkColorScheme(
            primary = assistantColors.accentBlue,
            onPrimary = Color.White,
            secondary = assistantColors.accentGreen,
            background = assistantColors.background,
            surface = assistantColors.surfaceCard,
            onBackground = assistantColors.textPrimary,
            onSurface = assistantColors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = assistantColors.accentBlue,
            onPrimary = Color.White,
            secondary = assistantColors.accentGreen,
            background = assistantColors.background,
            surface = assistantColors.surfaceCard,
            onBackground = assistantColors.textPrimary,
            onSurface = assistantColors.textPrimary,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDarkMode
            insetsController.isAppearanceLightNavigationBars = !isDarkMode
        }
    }

    CompositionLocalProvider(LocalAssistantColors provides assistantColors) {
        MaterialTheme(colorScheme = materialColors) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = assistantColors.background,
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
                        AssistantScreen(
                            store = conversationStore,
                            coordinator = coordinator,
                            initialConversationId = initialConversationId,
                            onRunRequest = onRunRequest,
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
                            onOpenTaskDisplays = openTaskDisplays,
                            overlayEnabled = overlayEnabled,
                            overlayPermissionGranted = overlayPermissionGranted,
                            onSetOverlayEnabled = onSetOverlayEnabled,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    // Keep the route for state restoration and older callers;
                    // the screen itself is a bottom sheet rather than a full
                    // page, so it retains the same presentation everywhere.
                    composable(AppRoutes.TASK_DISPLAYS) {
                        TaskDisplaysScreen(
                            records = visibleDisplayRecords,
                            onView = { record ->
                                viewerSessionKey = record.sessionKey
                                navController.popBackStack()
                            },
                            onEnd = onEndTaskDisplay,
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

                    composable(AppRoutes.PERMISSION_SETUP) {
                        PermissionOnboardingScreen(
                            step = permissionSetupStep ?: PermissionSetupStep.COMPLETE,
                            apps = apps,
                            permissions = permissions,
                            onPrimaryAction = onPermissionSetupPrimaryAction,
                            onShowOverlayStep = onShowOverlayPermissionSetup,
                            onBack = onPermissionSetupBack,
                        )
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
                    TaskDisplaysScreen(
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
                        onKeep = { conversationStore.keepInactiveConversation() },
                        onClear = { conversationStore.expireInactiveConversation() },
                    )
                }
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
