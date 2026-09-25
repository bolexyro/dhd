package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.domain.ActionType
import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ActivityLog(
    private val conversationStore: ConversationStore?,
) {
    private val _events = MutableStateFlow<List<ActivityEvent>>(emptyList())

    val events: StateFlow<List<ActivityEvent>> = _events.asStateFlow()

    fun append(
        kind: ActivityEventKind,
        message: String,
        sessionId: String?,
        actionType: ActionType? = null,
        toolName: String? = null,
        purpose: String? = null,
        observationId: String? = null,
        targetDescription: String? = null,
        eventId: String? = null,
    ) {
        val event = ActivityEvent(
            id = eventId ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestampEpochMs = System.currentTimeMillis(),
            kind = kind,
            message = message,
            actionType = actionType,
            toolName = toolName,
            purpose = purpose,
            observationId = observationId,
            targetDescription = targetDescription,
        )
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
        conversationStore?.recordEvent(event)
    }

    fun clear() {
        _events.value = emptyList()
    }

    private companion object {
        const val MAX_EVENTS = 100
    }
}
