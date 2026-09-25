package com.phonecontrol.assistant.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepositoryTest {
    private class CachingSource : ConversationSource {
        private val rows = mutableListOf<TimelineItem>()
        private val flows = mutableMapOf<String, MutableStateFlow<List<TimelineItem>>>()
        private val prompt = MutableStateFlow(false)
        val calls = mutableListOf<String>()

        override val conversationExpiryPrompt: StateFlow<Boolean> = prompt.asStateFlow()

        override fun timeline(conversationId: String): StateFlow<List<TimelineItem>> =
            flows.getOrPut(conversationId) { MutableStateFlow(rows.toList()) }.asStateFlow()

        override suspend fun deleteConversation(conversationId: String): Boolean {
            rows.clear()
            flows[conversationId]?.value = emptyList()
            flows.remove(conversationId)
            return true
        }

        override suspend fun promptForInactiveConversation(): Boolean {
            calls += "prompt"
            prompt.value = true
            return true
        }

        override fun dismissInactiveConversationPrompt() {
            calls += "dismiss"
            prompt.value = false
        }

        override suspend fun keepInactiveConversation(): Boolean {
            calls += "keep"
            return true
        }

        var expired = false

        override suspend fun expireInactiveConversation(): Boolean {
            calls += "expire"
            if (!expired) return false
            deleteConversation(DHD_CONVERSATION_ID)
            return true
        }

        fun write(item: TimelineItem) {
            rows += item
            flows[DHD_CONVERSATION_ID]?.value = rows.toList()
        }
    }

    private fun message(id: String) = TimelineItem.Message(id, "run-1", "user", id, 1L)

    @Test
    fun `timeline keeps publishing after the conversation is deleted`() = runTest(UnconfinedTestDispatcher()) {
        val source = CachingSource()
        val repository = ConversationRepository(source)
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { repository.timeline.collect { items -> seen += items.map(TimelineItem::id) } }

        source.write(message("before"))
        repository.deleteConversation()
        source.write(message("after"))

        assertEquals(listOf(emptyList(), listOf("before"), emptyList(), listOf("after")), seen)
    }

    @Test
    fun `timeline keeps publishing after an inactive conversation is cleared`() = runTest(UnconfinedTestDispatcher()) {
        val source = CachingSource().apply { expired = true }
        val repository = ConversationRepository(source)
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { repository.timeline.collect { items -> seen += items.map(TimelineItem::id) } }

        source.write(message("before"))
        assertTrue(repository.expireInactiveConversation())
        source.write(message("after"))

        assertEquals(listOf(emptyList(), listOf("before"), emptyList(), listOf("after")), seen)
    }

    @Test
    fun `a flow taken from the store before a delete stops updating`() = runTest {
        val source = CachingSource()
        val stale = source.timeline(DHD_CONVERSATION_ID)

        source.deleteConversation(DHD_CONVERSATION_ID)
        source.write(message("after"))

        assertEquals(emptyList<TimelineItem>(), stale.value)
    }

    @Test
    fun `expiry calls delegate to the store in order`() = runTest {
        val source = CachingSource()
        val repository = ConversationRepository(source)

        assertTrue(repository.promptForInactiveConversation())
        assertTrue(repository.conversationExpiryPrompt.value)
        repository.dismissInactiveConversationPrompt()
        assertTrue(repository.keepInactiveConversation())
        assertFalse(repository.expireInactiveConversation())

        assertFalse(repository.conversationExpiryPrompt.value)
        assertEquals(listOf("prompt", "dismiss", "keep", "expire"), source.calls)
    }
}
