package com.phonecontrol.assistant.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.chat.composer.PendingSteerDraft
import com.phonecontrol.assistant.ui.chat.composer.SteerDraftQueue
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

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) = ChatViewModel(
        sessionState = MutableStateFlow<SessionState>(SessionState.Idle),
        toolCalls = MutableStateFlow<List<DhdToolCall>>(emptyList()),
        timeline = timeline,
        initialTimeline = listOf(message("stored")),
        resetSession = { resets += 1 },
        savedState = savedState,
    )

    private fun draft(text: String) = PendingSteerDraft(text, "high", fastMode = false)

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

    @Test
    fun `held steer drafts start one by one in the order they were written`() {
        val viewModel = viewModel()
        viewModel.followActiveSession("run-1")
        viewModel.queueSteerDraft(draft("First"), "run-1")
        viewModel.queueSteerDraft(draft("Second"), "run-1")
        viewModel.queueSteerDraft(draft("Third"), "run-1")

        assertEquals("First", viewModel.promoteSteerDraftAfter("run-1")?.text)
        viewModel.followActiveSession("run-2")
        assertEquals(listOf("Second", "Third"), viewModel.steerDrafts.value.drafts.map(PendingSteerDraft::text))
        assertEquals(null, viewModel.promoteSteerDraftAfter("run-1"))
        assertEquals("Second", viewModel.promoteSteerDraftAfter("run-2")?.text)
        viewModel.followActiveSession("run-3")
        assertEquals("Third", viewModel.promoteSteerDraftAfter("run-3")?.text)
        assertEquals(SteerDraftQueue(emptyList(), "run-3", carryToNextRun = false), viewModel.steerDrafts.value)
    }

    @Test
    fun `steer drafts survive a new view model built from the saved state`() {
        val savedState = SavedStateHandle()
        val first = viewModel(savedState)
        first.followActiveSession("run-1")
        first.queueSteerDraft(draft("Use the blue one"), "run-1")
        first.queueSteerDraft(draft("Then pay"), "run-1")
        first.removeSteerDraft(0)

        val restored = viewModel(SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) }))

        assertEquals(SteerDraftQueue(listOf(draft("Then pay")), "run-1", carryToNextRun = false), restored.steerDrafts.value)
    }

    @Test
    fun `start fresh drops held steer drafts`() {
        val viewModel = viewModel()
        viewModel.queueSteerDraft(draft("First"), "run-1")

        viewModel.startFresh()

        assertEquals(SteerDraftQueue(emptyList(), null, carryToNextRun = false), viewModel.steerDrafts.value)
    }
}
