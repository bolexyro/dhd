package com.phonecontrol.assistant.ui.displays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.phonecontrol.assistant.domain.TaskPointerEvent
import kotlinx.coroutines.flow.StateFlow

val LocalRunPointerEvents = staticCompositionLocalOf<StateFlow<TaskPointerEvent?>?> { null }

@Composable
internal fun pointerEventFor(state: LiveDisplayPreviewState): TaskPointerEvent? {
    val runPointerEvents = LocalRunPointerEvents.current
    if (!state.followsRunPointer || runPointerEvents == null) return state.pointerEvent
    val event by runPointerEvents.collectAsState()
    return runPointerFor(state, event)
}

internal fun runPointerFor(state: LiveDisplayPreviewState, event: TaskPointerEvent?): TaskPointerEvent? =
    event?.takeIf { it.sessionId == state.runSessionKey || it.sessionId == state.sessionKey }
