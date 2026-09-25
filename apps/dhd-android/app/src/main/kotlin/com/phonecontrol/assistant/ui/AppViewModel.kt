package com.phonecontrol.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phonecontrol.assistant.app.AppContainer
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.display.TaskPreviewState
import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.displays.DisplayUiSources
import com.phonecontrol.assistant.ui.displays.DisplayUiState
import com.phonecontrol.assistant.ui.displays.mapDisplayUi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

internal data class AppUiState(
    val displaySources: DisplayUiSources,
)

internal data class PendingRunRequest(
    val request: String,
    val conversationId: String?,
    val reasoningEffort: String?,
    val fastMode: Boolean,
)

class AppViewModel internal constructor(
    activeSession: StateFlow<TaskDisplaySession?>,
    previewState: StateFlow<TaskPreviewState>,
    previewStates: StateFlow<Map<String, TaskPreviewState>>,
    displayRecords: StateFlow<List<TaskDisplayRecord>>,
    sessionState: StateFlow<SessionState>,
    events: StateFlow<List<ActivityEvent>>,
    resolveDisplay: suspend (String) -> TaskDisplaySession?,
    appLabelFor: (String) -> String?,
    mappingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val appLabels = ConcurrentHashMap<String, CachedAppLabel>()
    private val cachedAppLabelFor: (String) -> String? = { packageName ->
        appLabels.getOrPut(packageName) { CachedAppLabel(appLabelFor(packageName)) }.label
    }

    // A new coordinator run can claim a retained display whose native
    // owner key belongs to the previous run. Resolve that binding for
    // the inline viewer so the UI follows the selected display rather
    // than assuming the two keys are identical.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val resolvedDisplayForRun: StateFlow<TaskDisplaySession?> = combine(
        sessionState,
        activeSession,
        // Selecting a retained display with displayRef publishes its
        // updated registry record after the initial lookup. Include
        // the registry in the keys so the suspended lookup retries
        // once that binding becomes visible to the UI.
        displayRecords,
    ) { state, display, records ->
        DisplayResolutionKeys(state.sessionIdOrNull, display?.sessionKey, records)
    }
        .distinctUntilChanged()
        .mapLatest { keys -> keys.coordinatorSessionKey?.let { resolveDisplay(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal val uiState: StateFlow<AppUiState> = combine(
        combine(activeSession, previewState, previewStates, displayRecords, ::BackendDisplaySources),
        combine(sessionState, events, resolvedDisplayForRun, ::CoordinatorDisplaySources),
    ) { backend, coordinator -> AppUiState(displayUiSources(backend, coordinator)) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AppUiState(
                displayUiSources(
                    BackendDisplaySources(activeSession.value, previewState.value, previewStates.value, displayRecords.value),
                    CoordinatorDisplaySources(sessionState.value, events.value, resolvedDisplayForRun.value),
                ),
            ),
        )

    val displayUi: StateFlow<DisplayUiState> = uiState
        .map { state -> mapDisplayUi(state.displaySources, cachedAppLabelFor) }
        .flowOn(mappingDispatcher)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            mapDisplayUi(uiState.value.displaySources, cachedAppLabelFor),
        )

    private var requestAwaitingNotificationPermission: PendingRunRequest? = null
    private val _restoredRequest = MutableStateFlow<String?>(null)
    val restoredRequest: StateFlow<String?> = _restoredRequest.asStateFlow()

    internal fun holdForNotificationPermission(request: PendingRunRequest) {
        requestAwaitingNotificationPermission = request
    }

    internal fun onNotificationPermissionResult(granted: Boolean): PendingRunRequest? {
        val request = requestAwaitingNotificationPermission ?: return null
        requestAwaitingNotificationPermission = null
        if (granted) return request
        _restoredRequest.value = request.request
        return null
    }

    fun consumeRestoredRequest() {
        _restoredRequest.value = null
    }

    companion object {
        fun factory(container: AppContainer, appLabelFor: (String) -> String?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AppViewModel(
                        activeSession = container.taskDisplayBackend.activeSession,
                        previewState = container.taskDisplayBackend.previewState,
                        previewStates = container.taskDisplayBackend.previewStates,
                        displayRecords = container.taskDisplayBackend.displayRecords,
                        sessionState = container.sessionCoordinator.state,
                        events = container.sessionCoordinator.events,
                        resolveDisplay = { sessionKey -> container.taskDisplayBackend.current(sessionKey) },
                        appLabelFor = appLabelFor,
                    )
                }
            }
    }
}

private data class DisplayResolutionKeys(
    val coordinatorSessionKey: String?,
    val activeDisplaySessionKey: String?,
    val records: List<TaskDisplayRecord>,
)

private data class BackendDisplaySources(
    val activeDisplay: TaskDisplaySession?,
    val playback: TaskPreviewState,
    val previewStates: Map<String, TaskPreviewState>,
    val records: List<TaskDisplayRecord>,
)

private data class CoordinatorDisplaySources(
    val sessionState: SessionState,
    val events: List<ActivityEvent>,
    val resolvedDisplayForRun: TaskDisplaySession?,
)

private class CachedAppLabel(val label: String?)

private fun displayUiSources(
    backend: BackendDisplaySources,
    coordinator: CoordinatorDisplaySources,
) = DisplayUiSources(
    activeDisplay = backend.activeDisplay,
    playback = backend.playback,
    previewStates = backend.previewStates,
    records = backend.records,
    sessionState = coordinator.sessionState,
    events = coordinator.events,
    resolvedDisplayForRun = coordinator.resolvedDisplayForRun,
)
