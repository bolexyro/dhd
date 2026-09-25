package com.phonecontrol.assistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phonecontrol.assistant.app.AppContainer
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ChatViewModel internal constructor(
    val sessionState: StateFlow<SessionState>,
    val toolCalls: StateFlow<List<DhdToolCall>>,
    timeline: Flow<List<TimelineItem>>,
    initialTimeline: List<TimelineItem>,
    private val resetSession: () -> Unit,
) : ViewModel() {
    val timeline: StateFlow<List<TimelineItem>> =
        timeline.stateIn(viewModelScope, SharingStarted.Eagerly, initialTimeline)

    fun startFresh() {
        resetSession()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    sessionState = container.sessionCoordinator.state,
                    toolCalls = container.sessionCoordinator.toolCalls,
                    timeline = container.conversationRepository.timeline,
                    initialTimeline = container.conversationStore.timeline(DHD_CONVERSATION_ID).value,
                    resetSession = container.sessionCoordinator::reset,
                )
            }
        }
    }
}
