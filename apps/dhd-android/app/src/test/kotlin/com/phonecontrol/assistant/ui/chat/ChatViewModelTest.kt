package com.phonecontrol.assistant.ui.chat

import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val timeline = MutableSharedFlow<List<TimelineItem>>()
    private var resets = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun message(id: String) = TimelineItem.Message(id, "run-1", "user", id, 1L)

    private fun viewModel() = ChatViewModel(
        sessionState = MutableStateFlow<SessionState>(SessionState.Idle),
        toolCalls = MutableStateFlow<List<DhdToolCall>>(emptyList()),
        timeline = timeline,
        initialTimeline = listOf(message("stored")),
        resetSession = { resets += 1 },
    )

    @Test
    fun `timeline starts from the stored rows and follows the repository`() = runTest {
        val viewModel = viewModel()
        assertEquals(listOf("stored"), viewModel.timeline.value.map(TimelineItem::id))

        timeline.emit(emptyList())
        assertEquals(emptyList<String>(), viewModel.timeline.value.map(TimelineItem::id))

        timeline.emit(listOf(message("after")))
        assertEquals(listOf("after"), viewModel.timeline.value.map(TimelineItem::id))
    }

    @Test
    fun `start fresh resets the session`() {
        viewModel().startFresh()
        assertEquals(1, resets)
    }
}
