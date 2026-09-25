package com.phonecontrol.assistant.data

import com.phonecontrol.assistant.domain.ActionType
import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.execution.PhoneActionTransport
import com.phonecontrol.assistant.execution.TransportResult
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoreTest {
    private class ManualExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private val dao = InMemoryConversationDao()
    private val failures = mutableListOf<Throwable>()
    private var now = 1_750_000_000_000L
    private var realWriter: ExecutorService? = null

    @After
    fun tearDown() {
        realWriter?.shutdownNow()
        assertEquals(emptyList<Throwable>(), failures)
    }

    private fun store(writer: Executor) = ConversationStore(
        dao = dao,
        runInTransaction = { block -> block() },
        writer = writer,
        onWriteFailure = { failures += it },
        nowEpochMs = { now },
    )

    private fun realWriterStore(): ConversationStore {
        val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "test-conversation-writer") }
        realWriter = writer
        return store(writer)
    }

    private fun event(
        id: String,
        kind: ActivityEventKind,
        purpose: String? = "Tap the cart",
        message: String = "Tap",
    ) = ActivityEvent(
        id = id,
        sessionId = "run-1",
        timestampEpochMs = now,
        kind = kind,
        message = message,
        actionType = ActionType.TAP,
        purpose = purpose,
    )

    @Test
    fun `writes wait for the writer and are applied in order`() {
        val writer = ManualExecutor()
        val store = store(writer)

        val started = store.startRun("run-1", "Buy milk")
        store.recordEvent(event("proposed", ActivityEventKind.ACTION_PROPOSED))
        store.recordEvent(event("succeeded", ActivityEventKind.ACTION_SUCCEEDED))
        store.completeRun("run-1", RunStatus.COMPLETED, assistantText = "Milk added.")

        assertEquals(DHD_CONVERSATION_ID, started.conversationId)
        assertEquals(emptyList<String>(), dao.calls)

        writer.drain()

        assertEquals(RunStatus.COMPLETED.name, dao.runs.getValue("run-1").status)
        assertEquals("completed", dao.activities.getValue("proposed").status)
        assertEquals(
            listOf("user" to "Buy milk", "assistant" to "Milk added."),
            dao.messages.values.map { it.role to it.text },
        )
        val timelineIds = store.timeline().value.map(TimelineItem::id)
        assertEquals(3, timelineIds.size)
        assertTrue(started.userMessageId in timelineIds && "proposed" in timelineIds)
    }

    @Test
    fun `the database is only used on the writer thread`() {
        val store = realWriterStore()

        store.startRun("run-1", "Buy milk")
        store.setCurrentPurpose("run-1", "Opening the shop")
        store.recordSteer("steer-1", "run-1", "Get oat milk")

        assertEquals("Opening the shop", store.currentPurpose("run-1"))
        assertEquals(setOf("test-conversation-writer"), dao.callingThreads)
    }

    @Test
    fun `a burst of events refreshes the timeline once`() {
        val writer = ManualExecutor()
        val store = store(writer)
        store.startRun("run-1", "Buy milk")
        repeat(20) { index -> store.recordEvent(event("event-$index", ActivityEventKind.ACTION_PROPOSED)) }

        writer.drain()

        assertEquals(2, dao.calls.count { it == "listMessages" })
        assertEquals(20, dao.calls.count { it == "listActivities" })
        assertEquals(21, store.timeline().value.size)
    }

    @Test
    fun `a streamed agent message is stored after the run it belongs to`() {
        val writer = ManualExecutor()
        val store = store(writer)
        store.startRun("run-1", "Buy milk")
        val stored = store.upsertAgentMessage("run-1", "agent-1", "Looking for milk")
        val unknownRun = store.upsertAgentMessage("run-2", "agent-2", "Nope")

        writer.drain()

        assertTrue(stored.get())
        assertEquals(false, unknownRun.get())
        assertEquals("Looking for milk", dao.messages.getValue("agent-1").text)
    }

    @Test
    fun `an expired conversation is cleared on the writer`() = runTest {
        val store = realWriterStore()
        store.startRun("run-1", "Buy milk")
        now += DHD_THREAD_INACTIVITY_MS

        assertTrue(store.promptForInactiveConversation())
        assertTrue(store.conversationExpiryPrompt.value)
        assertTrue(store.expireInactiveConversation())

        assertEquals(emptyList<TimelineItem>(), store.timeline().value)
        assertEquals(false, store.conversationExpiryPrompt.value)
        assertEquals(emptyMap<String, ConversationEntity>(), dao.conversations)
        assertEquals(setOf("test-conversation-writer"), dao.callingThreads)
    }

    @Test
    fun `a stop right after a start is visible at once and persisted in order`() {
        val writer = ManualExecutor()
        val coordinator = SessionCoordinator(
            enabledPackagesProvider = { emptySet() },
            policyEngine = PolicyEngine(),
            transport = object : PhoneActionTransport {
                override suspend fun execute(action: PhoneAction, observation: ObservationSnapshot?): TransportResult =
                    TransportResult.Succeeded("executed")
            },
            conversationStore = store(writer),
        )

        coordinator.start("Buy milk", DHD_CONVERSATION_ID)
        coordinator.stop("Stopped by the user.")

        val stopped = coordinator.state.value as SessionState.Stopped
        assertEquals(DHD_CONVERSATION_ID, stopped.conversationId)
        assertEquals(emptyList<String>(), dao.calls)

        writer.drain()

        assertEquals(RunStatus.STOPPED.name, dao.runs.getValue(stopped.sessionId).status)
    }
}
