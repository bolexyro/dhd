package com.phonecontrol.assistant.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phonecontrol.assistant.app.AppContainer
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.chat.composer.PendingSteerDraft
import com.phonecontrol.assistant.ui.chat.composer.SteerDraftQueue
import com.phonecontrol.assistant.ui.chat.composer.decodeSteerDrafts
import com.phonecontrol.assistant.ui.chat.composer.encodeSteerDrafts
import com.phonecontrol.assistant.ui.chat.composer.forActiveSession
import com.phonecontrol.assistant.ui.chat.composer.promoteAfterCompletion
import com.phonecontrol.assistant.ui.chat.composer.withDraft
import com.phonecontrol.assistant.ui.chat.composer.without
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class ChatViewModel internal constructor(
    val sessionState: StateFlow<SessionState>,
    val toolCalls: StateFlow<List<DhdToolCall>>,
    timeline: Flow<List<TimelineItem>>,
    initialTimeline: List<TimelineItem>,
    private val resetSession: () -> Unit,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val timeline: StateFlow<List<TimelineItem>> =
        timeline.stateIn(viewModelScope, SharingStarted.Eagerly, initialTimeline)

    private val _steerDrafts = MutableStateFlow(
        SteerDraftQueue(
            drafts = decodeSteerDrafts(savedState.get<ArrayList<String>>(KEY_STEER_DRAFTS).orEmpty()),
            sessionId = savedState.get<String>(KEY_STEER_DRAFT_SESSION_ID),
            carryToNextRun = savedState.get<Boolean>(KEY_CARRY_STEER_DRAFTS) ?: false,
        ),
    )
    internal val steerDrafts: StateFlow<SteerDraftQueue> = _steerDrafts.asStateFlow()

    fun startFresh() {
        clearSteerDrafts()
        resetSession()
    }

    internal fun queueSteerDraft(draft: PendingSteerDraft, activeSessionId: String?) {
        updateSteerDrafts { withDraft(draft, activeSessionId) }
    }

    internal fun removeSteerDraft(index: Int) {
        updateSteerDrafts { without(index) }
    }

    internal fun clearSteerDrafts() {
        updateSteerDrafts { SteerDraftQueue(drafts = emptyList(), sessionId = null, carryToNextRun = false) }
    }

    internal fun followActiveSession(activeSessionId: String?) {
        updateSteerDrafts { forActiveSession(activeSessionId) }
    }

    internal fun promoteSteerDraftAfter(completedSessionId: String): PendingSteerDraft? {
        val promotion = _steerDrafts.value.promoteAfterCompletion(completedSessionId) ?: return null
        updateSteerDrafts { promotion.queue }
        return promotion.draft
    }

    private fun updateSteerDrafts(transform: SteerDraftQueue.() -> SteerDraftQueue) {
        val next = _steerDrafts.value.transform()
        _steerDrafts.value = next
        savedState[KEY_STEER_DRAFTS] = encodeSteerDrafts(next.drafts)
        savedState[KEY_STEER_DRAFT_SESSION_ID] = next.sessionId
        savedState[KEY_CARRY_STEER_DRAFTS] = next.carryToNextRun
    }

    companion object {
        private const val KEY_STEER_DRAFTS = "steer_drafts"
        private const val KEY_STEER_DRAFT_SESSION_ID = "steer_draft_session_id"
        private const val KEY_CARRY_STEER_DRAFTS = "carry_steer_drafts_to_next_run"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    sessionState = container.sessionCoordinator.state,
                    toolCalls = container.sessionCoordinator.toolCalls,
                    timeline = container.conversationRepository.timeline,
                    initialTimeline = container.conversationStore.timeline(DHD_CONVERSATION_ID).value,
                    resetSession = container.sessionCoordinator::reset,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
}
