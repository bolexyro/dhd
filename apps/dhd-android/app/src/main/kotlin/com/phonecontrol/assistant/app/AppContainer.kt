package com.phonecontrol.assistant.app

import android.content.Context
import android.view.Surface
import com.phonecontrol.assistant.adb.DhdAdbProcessRunner
import com.phonecontrol.assistant.adb.PhoneAccessController
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.apps.InstalledAppsRepository
import com.phonecontrol.assistant.bridge.AndroidBridgePlatform
import com.phonecontrol.assistant.bridge.DevBridgeServer
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.display.DhdTaskDisplayBackend
import com.phonecontrol.assistant.display.DhdVirtualDisplayManager
import com.phonecontrol.assistant.display.PreviewSurfaceDispatcher
import com.phonecontrol.assistant.execution.TaskDisplayLayoutPreferences
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TypedPhoneActionTransport
import com.phonecontrol.assistant.observation.PhoneObservationProvider
import com.phonecontrol.assistant.overlay.OverlayVisibilityGate
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.session.AssistantForegroundService
import com.phonecontrol.assistant.session.DhdNotificationVisibility
import com.phonecontrol.assistant.session.SessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val notificationVisibility = DhdNotificationVisibility()
    val appPermissionRepository = AppPermissionRepository(context)
    val phoneAccessController = PhoneAccessController(context).also { it.start() }
    val processRunner = DhdAdbProcessRunner(phoneAccessController)
    val conversationStore = ConversationStore(context)
    val taskDisplayLayoutPreferences = TaskDisplayLayoutPreferences(context)
    val taskDisplayBackend = DhdTaskDisplayBackend(
        context,
        DhdVirtualDisplayManager(context, phoneAccessController),
        processRunner,
        taskDisplayLayoutPreferences,
        conversationStore,
    )

    init {
        previewScope.launch {
            phoneAccessController.status.collect { status ->
                if (status.privilegedApiReady) {
                    // A restarted shell daemon loses its in-memory display
                    // sessions. Reconcile before a retained viewer or the
                    // next task action can use the stale app-side binding.
                    taskDisplayBackend.reconcileNativeSessionsNow()
                }
            }
        }
    }

    val observationProvider = PhoneObservationProvider(context, processRunner, taskDisplayBackend)
    val overlayVisibilityGate = OverlayVisibilityGate()
    val sessionCoordinator = SessionCoordinator(
        enabledPackagesProvider = { appPermissionRepository.enabledPackages() },
        // Structural observation checks are always enabled; guard-region
        // fingerprints add the optional stricter visual check per action.
        policyEngine = PolicyEngine(enforceObservationFreshness = true),
        transport = TypedPhoneActionTransport(
            context = context,
            observationProvider = observationProvider,
            processRunner = processRunner,
            executionReadyProvider = { phoneAccessController.status.value.privilegedApiReady },
            executionUnavailableMessageProvider = { phoneAccessController.status.value.executionUnavailableMessage },
            enforceObservationFreshness = true,
            taskDisplayBackend = taskDisplayBackend,
        ),
        conversationStore = conversationStore,
        fullAccessProvider = { appPermissionRepository.isFullAccessEnabled() },
        taskDisplayRequiredProvider = { true },
        taskDisplayBackend = taskDisplayBackend,
        phoneAccessReadyProvider = { phoneAccessController.status.value.privilegedApiReady },
        onPhoneAccessAttentionRequested = { reason, conversationId ->
            AssistantForegroundService.showAttentionNotification(context, reason, conversationId)
        },
        onPhoneAccessAttentionResolved = {
            AssistantForegroundService.removeAttentionNotification(context)
        },
    )

    val installedAppsRepository = InstalledAppsRepository(context)

    // The bridge accepts paired LAN connections for the development
    // companion. adb forwarding remains compatible because forwarded
    // clients arrive as loopback and bypass the LAN token check.
    val devBridgeServer = DevBridgeServer(
        platform = AndroidBridgePlatform(
            context = context,
            preferencesName = DevBridgeServer.PREFERENCES_NAME,
            installedAppsRepository = installedAppsRepository,
            taskDisplayLayoutPreferences = taskDisplayLayoutPreferences,
            overlayVisibilityGate = overlayVisibilityGate,
        ),
        coordinator = sessionCoordinator,
        observationProvider = observationProvider,
        allowedPackagesProvider = { appPermissionRepository.enabledPackages() },
        fullAccessProvider = { appPermissionRepository.isFullAccessEnabled() },
        taskDisplayRequiredProvider = { true },
        taskDisplayBackend = taskDisplayBackend,
    ).also { it.start() }

    private val previewSurfaces by lazy {
        PreviewSurfaceDispatcher<TaskDisplaySession, Surface>(
            scope = previewScope,
            attach = { session, surface -> taskDisplayBackend.attachLiveSurface(session, surface) },
            detach = { session, surface -> taskDisplayBackend.detachLiveSurface(session, surface) },
            onFailure = { error -> android.util.Log.w("DhdPreview", "Surface lifecycle failed", error) },
        )
    }

    fun attachTaskPreview(session: TaskDisplaySession, surface: Surface) {
        previewSurfaces.attach(surface) { session }
    }

    fun detachTaskPreview(
        surface: Surface,
        releaseSurface: () -> Unit,
    ) {
        previewSurfaces.detach(surface, releaseSurface)
    }

    /** Session lookup and cleanup run in the same order as the UI callbacks. */
    fun attachTaskPreview(sessionKey: String, surface: Surface) {
        previewSurfaces.attach(surface) { taskDisplayBackend.current(sessionKey) }
    }

    fun retryTaskPreview(sessionKey: String) {
        previewScope.launch { taskDisplayBackend.retryLiveSurface(sessionKey) }
    }

    /** End one display, stopping its owning agent run before releasing native resources. */
    fun endTaskDisplay(displayId: Int?, displayRef: String?, sessionKey: String? = null) {
        previewScope.launch {
            val activeRunKey = sessionCoordinator.activeSessionId()
            val selectedDisplayId = displayId
            if (selectedDisplayId != null) {
                if (activeRunKey != null && taskDisplayBackend.isDisplayClaimedByRun(selectedDisplayId, activeRunKey)) {
                    sessionCoordinator.stop("Display ended by the user.")
                }
                taskDisplayBackend.closeTaskDisplay(selectedDisplayId, displayRef)
            } else if (!sessionKey.isNullOrBlank()) {
                // A fallback UI record can outlive its Android display ID.
                // Close by the opaque owner key so an unavailable/stale record
                // can still be removed from Task Displays.
                taskDisplayBackend.close(sessionKey)
            }
        }
    }

    fun closeAllTaskDisplays() {
        previewScope.launch {
            taskDisplayBackend.closeAllTaskDisplays(clearRecords = true)
        }
    }

    /**
     * Start a fresh conversation session:
     * - Resets the coordinator to Idle and cleans up active jobs/steers/events.
     * - Deletes the conversation history in ConversationStore.
     * - Closes all active and retained virtual displays.
     */
    fun startFresh() {
        sessionCoordinator.reset()
        conversationStore.deleteConversation(DHD_CONVERSATION_ID)
        previewScope.launch {
            taskDisplayBackend.closeAllTaskDisplays(clearRecords = true)
        }
    }
}
