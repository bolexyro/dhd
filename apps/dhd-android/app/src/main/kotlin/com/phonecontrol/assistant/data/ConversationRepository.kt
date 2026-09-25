package com.phonecontrol.assistant.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

internal interface ConversationSource {
    val conversationExpiryPrompt: StateFlow<Boolean>
    fun timeline(conversationId: String): StateFlow<List<TimelineItem>>
    fun deleteConversation(conversationId: String): Boolean
    fun promptForInactiveConversation(): Boolean
    fun dismissInactiveConversationPrompt()
    fun keepInactiveConversation(): Boolean
    fun expireInactiveConversation(): Boolean
}

private class StoreConversationSource(private val store: ConversationStore) : ConversationSource {
    override val conversationExpiryPrompt: StateFlow<Boolean>
        get() = store.conversationExpiryPrompt

    override fun timeline(conversationId: String): StateFlow<List<TimelineItem>> = store.timeline(conversationId)

    override fun deleteConversation(conversationId: String): Boolean = store.deleteConversation(conversationId)

    override fun promptForInactiveConversation(): Boolean = store.promptForInactiveConversation()

    override fun dismissInactiveConversationPrompt() {
        store.dismissInactiveConversationPrompt()
    }

    override fun keepInactiveConversation(): Boolean = store.keepInactiveConversation()

    override fun expireInactiveConversation(): Boolean = store.expireInactiveConversation()
}

class ConversationRepository internal constructor(private val source: ConversationSource) {
    constructor(store: ConversationStore) : this(StoreConversationSource(store))

    private val timelineGeneration = MutableStateFlow(0)

    val conversationExpiryPrompt: StateFlow<Boolean>
        get() = source.conversationExpiryPrompt

    @OptIn(ExperimentalCoroutinesApi::class)
    val timeline: Flow<List<TimelineItem>> = timelineGeneration
        .flatMapLatest { source.timeline(DHD_CONVERSATION_ID) }
        .distinctUntilChanged()

    fun deleteConversation(conversationId: String = DHD_CONVERSATION_ID): Boolean =
        source.deleteConversation(conversationId).also { refreshTimeline() }

    fun promptForInactiveConversation(): Boolean = source.promptForInactiveConversation()

    fun dismissInactiveConversationPrompt() {
        source.dismissInactiveConversationPrompt()
    }

    fun keepInactiveConversation(): Boolean = source.keepInactiveConversation()

    fun expireInactiveConversation(): Boolean =
        source.expireInactiveConversation().also { expired -> if (expired) refreshTimeline() }

    private fun refreshTimeline() {
        timelineGeneration.update { generation -> generation + 1 }
    }
}
