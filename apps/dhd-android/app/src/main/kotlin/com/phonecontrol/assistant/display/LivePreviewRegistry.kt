package com.phonecontrol.assistant.display

import android.view.Surface
import com.phonecontrol.assistant.execution.TaskDisplaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** State that the DHD preview can render without knowing the native backend. */
sealed interface TaskPreviewState {
    data object Detached : TaskPreviewState
    data class Connecting(val session: TaskDisplaySession) : TaskPreviewState
    data class Attached(val session: TaskDisplaySession) : TaskPreviewState
    data class Ended(
        val session: TaskDisplaySession,
        val message: String,
    ) : TaskPreviewState
    data class Error(val sessionKey: String?, val message: String) : TaskPreviewState
}

internal class LivePreviewRegistry(
    private val scope: CoroutineScope,
    private val stateLock: Mutex,
    private val activeSessionKey: () -> String?,
) {
    private val publishLock = Any()
    private val liveHandles = mutableMapOf<String, LiveHandle>()
    private val previewStateJobs = mutableMapOf<String, Job>()
    private val _previewState = MutableStateFlow<TaskPreviewState>(TaskPreviewState.Detached)
    private val _previewStates = MutableStateFlow<Map<String, TaskPreviewState>>(emptyMap())

    val previewState: StateFlow<TaskPreviewState> = _previewState.asStateFlow()

    val previewStates: StateFlow<Map<String, TaskPreviewState>> = _previewStates.asStateFlow()

    fun handleLocked(sessionKey: String): DhdLivePreviewHandle? = liveHandles[sessionKey]?.handle

    fun surfaceLocked(sessionKey: String): Surface? = liveHandles[sessionKey]?.surface

    fun hasSurfaceLocked(sessionKey: String, surface: Surface): Boolean =
        liveHandles[sessionKey]?.takeIf { it.surface === surface } != null

    fun attachLocked(session: TaskDisplaySession, surface: Surface, handle: DhdLivePreviewHandle) {
        liveHandles[session.sessionKey] = LiveHandle(surface, handle)
        publish(
            session.sessionKey,
            TaskPreviewState.Connecting(session),
        )
        if (previewStateJobs[session.sessionKey]?.isActive != true) {
            previewStateJobs[session.sessionKey] = observe(
                session = session,
                handle = handle,
            )
        }
    }

    fun detachSurfaceLocked(sessionKey: String, surface: Surface): DhdLivePreviewHandle? =
        liveHandles[sessionKey]
            ?.takeIf { it.surface === surface }
            ?.also {
                liveHandles[sessionKey] = it.copy(surface = null)
                previewStateJobs.remove(sessionKey)?.cancel()
            }
            ?.handle

    fun stopObservingLocked(sessionKey: String) {
        previewStateJobs.remove(sessionKey)?.cancel()
    }

    fun removeHandleLocked(sessionKey: String): DhdLivePreviewHandle? = liveHandles.remove(sessionKey)?.handle

    fun isInlineSessionLocked(sessionKey: String): Boolean = _previewState.value.sessionKeyOrNull() == sessionKey

    fun resetInlineLocked() {
        synchronized(publishLock) {
            _previewState.value = TaskPreviewState.Detached
        }
    }

    fun publish(sessionKey: String, state: TaskPreviewState) {
        // The legacy single-preview flow feeds the inline assistant card. A
        // retained display opened from the manager may attach concurrently;
        // keep that viewer in the per-session map without replacing the
        // active task's inline state.
        val activeKey = activeSessionKey()
        synchronized(publishLock) {
            if (activeKey == null || activeKey == sessionKey ||
                _previewState.value.sessionKeyOrNull() == sessionKey
            ) {
                _previewState.value = state
            }
            _previewStates.value = _previewStates.value.toMutableMap().apply {
                if (state is TaskPreviewState.Detached) remove(sessionKey) else put(sessionKey, state)
            }
        }
    }

    /**
     * Mirror the decoder's state without exposing the native handle to the
     * execution layer. The identity check is essential: a late CLOSED/ERROR
     * emission from an old decoder must not overwrite a replacement surface.
     */
    private fun observe(
        session: TaskDisplaySession,
        handle: DhdLivePreviewHandle,
    ): Job = scope.launch {
        handle.state.collectLatest { state ->
            stateLock.withLock {
                val liveHandle = liveHandles[session.sessionKey]
                if (liveHandle?.handle !== handle || liveHandle.surface == null) return@withLock
                publish(session.sessionKey, when (state.phase) {
                    DhdLivePreviewPhase.CONNECTING -> TaskPreviewState.Connecting(session)
                    DhdLivePreviewPhase.LIVE -> {
                        TaskPreviewState.Attached(session)
                    }
                    DhdLivePreviewPhase.ERROR -> TaskPreviewState.Error(
                        sessionKey = session.sessionKey,
                        message = state.message ?: "The live preview decoder failed.",
                    )
                    DhdLivePreviewPhase.CLOSED -> TaskPreviewState.Detached
                })
            }
        }
    }

    private data class LiveHandle(
        val surface: Surface?,
        val handle: DhdLivePreviewHandle,
    )

    private fun TaskPreviewState.sessionKeyOrNull(): String? = when (this) {
        TaskPreviewState.Detached -> null
        is TaskPreviewState.Connecting -> session.sessionKey
        is TaskPreviewState.Attached -> session.sessionKey
        is TaskPreviewState.Ended -> session.sessionKey
        is TaskPreviewState.Error -> sessionKey
    }
}
