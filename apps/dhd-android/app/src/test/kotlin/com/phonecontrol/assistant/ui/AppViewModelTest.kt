package com.phonecontrol.assistant.ui

import com.phonecontrol.assistant.display.TaskPreviewState
import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.execution.TaskDisplayGeometry
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.displays.DisplayUiSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val geometry = TaskDisplayGeometry(720, 1560, 420, 0)
    private val activeSession = MutableStateFlow<TaskDisplaySession?>(null)
    private val previewState = MutableStateFlow<TaskPreviewState>(TaskPreviewState.Detached)
    private val previewStates = MutableStateFlow<Map<String, TaskPreviewState>>(emptyMap())
    private val displayRecords = MutableStateFlow<List<TaskDisplayRecord>>(emptyList())
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val events = MutableStateFlow<List<ActivityEvent>>(emptyList())
    private val resolvedKeys = mutableListOf<String>()
    private val labelLookups = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun session(key: String) = TaskDisplaySession(
        sessionKey = key,
        taskId = "$key@7",
        displayId = 7,
        geometry = geometry,
        packageName = "com.example.shop",
    )

    private fun record(key: String) = TaskDisplayRecord(
        sessionKey = key,
        taskId = "$key@7",
        packageName = "com.example.shop",
        displayId = 7,
        width = 720,
        height = 1560,
        densityDpi = 420,
        rotation = 0,
        status = TaskDisplayStatus.RUNNING,
        createdAtEpochMs = 100L,
        lastPurpose = "Checking the cart",
    )

    private fun running(sessionId: String) = SessionState.Running(
        sessionId = sessionId,
        request = "Buy milk",
        currentPurpose = "Opening Shop",
        startedAtEpochMs = 5_000L,
    )

    private fun viewModel() = AppViewModel(
        activeSession = activeSession,
        previewState = previewState,
        previewStates = previewStates,
        displayRecords = displayRecords,
        sessionState = sessionState,
        events = events,
        resolveDisplay = { key ->
            resolvedKeys += key
            session("owner-of-$key")
        },
        appLabelFor = { packageName ->
            labelLookups += packageName
            "label:$packageName"
        },
        mappingDispatcher = Dispatchers.Main,
    )

    @Test
    fun `ui state carries the current display sources`() {
        val retained = session("retained")
        activeSession.value = retained
        displayRecords.value = listOf(record("retained"))
        val event = ActivityEvent("e1", null, 1L, ActivityEventKind.SYSTEM, "m")
        events.value = listOf(event)

        val viewModel = viewModel()

        assertEquals(
            DisplayUiSources(
                activeDisplay = retained,
                playback = TaskPreviewState.Detached,
                previewStates = emptyMap(),
                records = listOf(record("retained")),
                sessionState = SessionState.Idle,
                events = listOf(event),
                resolvedDisplayForRun = null,
            ),
            viewModel.uiState.value.displaySources,
        )
        assertEquals(emptyList<String>(), resolvedKeys)
    }

    @Test
    fun `run display is resolved again only when its lookup keys change`() {
        val viewModel = viewModel()

        sessionState.value = running("run-1")
        assertEquals(session("owner-of-run-1"), viewModel.uiState.value.displaySources.resolvedDisplayForRun)
        assertEquals(listOf("run-1"), resolvedKeys)

        events.value = listOf(ActivityEvent("e1", "run-1", 1L, ActivityEventKind.SYSTEM, "m"))
        sessionState.value = running("run-1").copy(currentPurpose = "Checking out")
        assertEquals(listOf("run-1"), resolvedKeys)

        displayRecords.value = listOf(record("owner-of-run-1"))
        activeSession.value = session("owner-of-run-1")
        assertEquals(listOf("run-1", "run-1", "run-1"), resolvedKeys)

        sessionState.value = SessionState.Completed("run-1", "Done")
        assertEquals(listOf("run-1", "run-1", "run-1"), resolvedKeys)

        sessionState.value = SessionState.Idle
        assertEquals(null, viewModel.uiState.value.displaySources.resolvedDisplayForRun)
        assertEquals(listOf("run-1", "run-1", "run-1"), resolvedKeys)
    }

    @Test
    fun `a request held for the notification permission starts once it is granted`() {
        val viewModel = viewModel()
        val request = PendingRunRequest("Buy milk", "conversation-1", "high", fastMode = true)
        viewModel.holdForNotificationPermission(request)

        assertEquals(request, viewModel.onNotificationPermissionResult(granted = true))
        assertEquals(null, viewModel.restoredRequest.value)
        assertEquals(null, viewModel.onNotificationPermissionResult(granted = true))
    }

    @Test
    fun `a request refused the notification permission goes back to the composer`() {
        val viewModel = viewModel()
        viewModel.holdForNotificationPermission(PendingRunRequest("Buy milk", null, null, fastMode = false))

        assertEquals(null, viewModel.onNotificationPermissionResult(granted = false))
        assertEquals("Buy milk", viewModel.restoredRequest.value)

        viewModel.consumeRestoredRequest()
        assertEquals(null, viewModel.restoredRequest.value)
    }

    @Test
    fun `display ui is mapped in the view model and looks up each app label once`() {
        displayRecords.value = listOf(record("retained"))
        val viewModel = viewModel()
        assertEquals(listOf("retained"), viewModel.displayUi.value.displayRecords.map { it.sessionKey })
        assertEquals("label:com.example.shop", viewModel.displayUi.value.displayRecords.single().appLabel)

        events.value = listOf(ActivityEvent("e1", null, 1L, ActivityEventKind.SYSTEM, "m"))
        displayRecords.value = listOf(record("retained"), record("second"))

        assertEquals(listOf("retained", "second"), viewModel.displayUi.value.displayRecords.map { it.sessionKey }.sorted())
        assertEquals(listOf("com.example.shop"), labelLookups)
    }

    @Test
    fun `an event that does not change the mapped display ui publishes nothing new`() {
        displayRecords.value = listOf(record("retained"))
        val viewModel = viewModel()
        val before = viewModel.displayUi.value

        events.value = listOf(ActivityEvent("e1", null, 1L, ActivityEventKind.SYSTEM, "m"))

        assertSame(before, viewModel.displayUi.value)
    }
}
