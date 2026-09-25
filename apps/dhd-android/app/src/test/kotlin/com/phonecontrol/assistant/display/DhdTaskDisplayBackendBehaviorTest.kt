package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.execution.PhoneProcessResult
import com.phonecontrol.assistant.execution.PhoneProcessRunner
import com.phonecontrol.assistant.execution.TaskDisplayCloseResult
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.TaskDisplaySpec
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.taskDisplayReference
import com.phonecontrol.assistant.observation.ActivityDumpParser
import com.phonecontrol.assistant.observation.FocusedComponent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE_EPOCH_MS = 1_750_000_000_000L
private const val RETENTION_MS = 60_000L
private const val SHOP = "com.example.shop"

private class FakeNativeDisplayManager : NativeDisplayManager {
    var nextDisplayId = 7
    val created = mutableListOf<Pair<String, DhdVirtualDisplaySpec>>()
    val closed = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var closeAllCalls = 0
    var createFailure: DhdVirtualDisplayResult.Failed? = null
    var reconcileFailure: Throwable? = null
    var reconcileCalls = 0
    val live = linkedMapOf<String, DhdVirtualDisplaySession>()
    var reconcileResult: ((Set<String>) -> Map<String, DhdVirtualDisplaySession>)? = null

    override suspend fun create(
        sessionKey: String,
        packageName: String,
        spec: DhdVirtualDisplaySpec,
    ): DhdVirtualDisplayResult {
        created += sessionKey to spec
        createFailure?.let { return it }
        val session = nativeSession(sessionKey, packageName, nextDisplayId++, spec)
        live[sessionKey] = session
        return DhdVirtualDisplayResult.Created(session)
    }

    override suspend fun attachLiveSurface(session: DhdVirtualDisplaySession): DhdLivePreviewHandle =
        error("live preview is not exercised on the JVM")

    override suspend fun capture(session: DhdVirtualDisplaySession): DhdVirtualDisplayCapture =
        DhdVirtualDisplayCapture(session, byteArrayOf(1, 2, 3))

    override suspend fun close(sessionKey: String) {
        closed += sessionKey
        live.remove(sessionKey)
    }

    override fun cancel(sessionKey: String) {
        cancelled += sessionKey
    }

    override suspend fun closeAll() {
        closeAllCalls += 1
        live.clear()
    }

    override suspend fun reconcile(
        expectedSessionKeys: Set<String>,
        force: Boolean,
    ): Map<String, DhdVirtualDisplaySession> {
        reconcileCalls += 1
        reconcileFailure?.let { throw it }
        return reconcileResult?.invoke(expectedSessionKeys) ?: live.filterKeys { it in expectedSessionKeys }
    }
}

private fun nativeSession(
    sessionKey: String,
    packageName: String,
    displayId: Int,
    spec: DhdVirtualDisplaySpec = DhdVirtualDisplaySpec(),
): DhdVirtualDisplaySession = DhdVirtualDisplaySession(
    sessionKey = sessionKey,
    packageName = packageName,
    displayId = displayId,
    width = spec.width,
    height = spec.height,
    densityDpi = spec.densityDpi,
    frameRate = spec.frameRate,
    bitRate = spec.bitRate,
    streamPort = 40_000 + displayId,
    streamToken = "token-$displayId",
    appDensityDpi = spec.appDensityDpi,
    appDisplayWidth = spec.appDisplayWidth ?: spec.width,
    appDisplayHeight = spec.appDisplayHeight ?: spec.height,
)

private class FakeTaskDisplayPlatform : TaskDisplayPlatform {
    override var sdkInt = 36
    val fullSizePackages = mutableSetOf<String>()
    val rotations = mutableMapOf<Int, Int>()

    override fun isFullSizeLayoutEnabled(packageName: String): Boolean = packageName in fullSizePackages

    override fun displayRotation(displayId: Int): Int? = rotations[displayId] ?: 0

    override fun hasLaunchIntent(packageName: String): Boolean = true
}

private class FakeActivityDump : PhoneProcessRunner {
    var output: String = """
        Display #7 (activities from top to bottom):
          mResumedActivity: ActivityRecord{a u0 $SHOP/.MainActivity t42}
        Display #8 (activities from top to bottom):
          mResumedActivity: ActivityRecord{b u0 $SHOP/.MainActivity t43}
    """.trimIndent()
    var exitCode = 0

    override suspend fun run(command: List<String>): PhoneProcessResult =
        PhoneProcessResult(exitCode, output.toByteArray(Charsets.UTF_8), "")
}

class DhdTaskDisplayBackendBehaviorTest {
    private val native = FakeNativeDisplayManager()
    private val platform = FakeTaskDisplayPlatform()
    private val activities = FakeActivityDump()

    private fun TestScope.backend(): DhdTaskDisplayBackend {
        var owners = 0
        return DhdTaskDisplayBackend(
            nativeManager = native,
            processRunner = activities,
            conversationStore = null,
            nowEpochMs = { BASE_EPOCH_MS + testScheduler.currentTime },
            terminalRetentionMs = RETENTION_MS,
            platform = platform,
            scope = backgroundScope,
            newOwnerKey = { "owner-${++owners}" },
        )
    }

    private fun DhdTaskDisplayBackend.record(sessionKey: String): TaskDisplayRecord =
        displayRecords.value.single { it.sessionKey == sessionKey }

    private fun TestScope.now(): Long = BASE_EPOCH_MS + testScheduler.currentTime

    @Test
    fun `create publishes a running record for the native display`() = runTest {
        val backend = backend()
        platform.rotations[7] = 1
        val session = backend.create("run-1", SHOP)

        assertEquals("run-1@7", session.taskId)
        assertEquals(7, session.displayId)
        assertEquals("127.0.0.1:40007", session.streamEndpoint)
        assertEquals(1, session.geometry.rotation)
        assertEquals(720 to 1560, session.appDisplayWidth to session.appDisplayHeight)
        assertEquals(DhdVirtualDisplaySpec(appDensityDpi = 320), native.created.single().second)
        assertEquals(
            TaskDisplayRecord(
                sessionKey = "run-1",
                taskId = "run-1@7",
                packageName = SHOP,
                displayId = 7,
                width = 720,
                height = 1560,
                densityDpi = 420,
                rotation = 1,
                status = TaskDisplayStatus.RUNNING,
                createdAtEpochMs = now(),
                lastPurpose = "Preparing request",
            ),
            backend.record("run-1"),
        )
        assertEquals(session, backend.current("run-1"))
        assertEquals(session, backend.activeSession.value)
    }

    @Test
    fun `create uses the full size layout for opted in apps`() = runTest {
        val backend = backend()
        platform.fullSizePackages += SHOP
        val session = backend.create("run-1", SHOP)
        assertEquals(
            DhdVirtualDisplaySpec(appDensityDpi = 420, appDisplayWidth = 945, appDisplayHeight = 2048),
            native.created.single().second,
        )
        assertEquals(945 to 2048, session.appDisplayWidth to session.appDisplayHeight)
    }

    @Test
    fun `create reports native failures and duplicate keys`() = runTest {
        val backend = backend()
        native.createFailure = DhdVirtualDisplayResult.Failed(
            DhdVirtualDisplayResult.Code.DISPLAY_UNAVAILABLE,
            "DHD display session limit reached.",
        )
        val failure = runCatching { backend.create("run-1", SHOP) }.exceptionOrNull()
        assertTrue(failure is DhdTaskDisplayBackend.TaskDisplayException)
        assertEquals("DHD display session limit reached.", failure?.message)
        assertTrue(backend.displayRecords.value.isEmpty())

        native.createFailure = null
        backend.create("run-1", SHOP)
        val duplicate = runCatching { backend.create("run-1", SHOP) }.exceptionOrNull()
        assertEquals("The task display session is already active.", duplicate?.message)
    }

    @Test
    fun `create on a phone without android 16 explains the requirement without asking the daemon`() = runTest {
        val backend = backend()
        platform.sdkInt = 35

        val failure = runCatching { backend.create("run-1", SHOP) }.exceptionOrNull()

        assertTrue(failure is DhdTaskDisplayBackend.TaskDisplayException)
        assertEquals(
            "DHD task displays currently require Android 16 (API 36). This phone runs Android API 35.",
            failure?.message,
        )
        assertEquals(emptyList<Pair<String, DhdVirtualDisplaySpec>>(), native.created)
    }

    @Test
    fun `task displays are supported on android 16 only`() {
        assertEquals(null, taskDisplayUnsupportedReason(36))
        assertEquals(
            "DHD task displays currently require Android 16 (API 36). This phone runs Android API 37.",
            taskDisplayUnsupportedReason(37),
        )
    }

    @Test
    fun `create after cancel is refused`() = runTest {
        val backend = backend()
        backend.cancel("run-1")
        assertEquals(listOf("run-1"), native.cancelled)
        val failure = runCatching { backend.create("run-1", SHOP) }.exceptionOrNull()
        assertEquals("The task display session was stopped before creation.", failure?.message)
    }

    @Test
    fun `open app creates one display per run with a fresh owner key`() = runTest {
        val backend = backend()
        val first = backend.openApp("run-1", SHOP, TaskDisplaySpec())
        assertTrue(first.created)
        assertEquals("owner-1", first.session.sessionKey)
        assertEquals(first.session, backend.current("run-1"))

        val again = backend.openApp("run-1", SHOP, TaskDisplaySpec())
        assertFalse(again.created)
        assertEquals(first.session, again.session)

        val other = backend.openApp("run-2", SHOP, TaskDisplaySpec())
        assertTrue(other.created)
        assertEquals("owner-2", other.session.sessionKey)
        assertEquals(8, other.session.displayId)
    }

    @Test
    fun `open app recreates the display when the app layout changed`() = runTest {
        val backend = backend()
        val first = backend.openApp("run-1", SHOP, TaskDisplaySpec()).session
        platform.fullSizePackages += SHOP
        val second = backend.openApp("run-1", SHOP, TaskDisplaySpec())
        assertTrue(second.created)
        assertEquals("owner-2", second.session.sessionKey)
        assertEquals(listOf("owner-1"), native.closed)
        assertEquals(TaskDisplayStatus.ENDED, backend.record(first.sessionKey).status)
    }

    @Test
    fun `a running display cannot be claimed by another run`() = runTest {
        val backend = backend()
        val session = backend.create("run-1", SHOP)
        val resolution = backend.resolveDisplay(session.displayId, claimForSessionKey = "run-2")
        assertEquals("DISPLAY_IN_USE", (resolution as TaskDisplayResolution.Unavailable).code)
        assertFalse(backend.isDisplayClaimedByRun(session.displayId, "run-2"))
        assertTrue(backend.isDisplayClaimedByRun(session.displayId, "run-1"))
    }

    @Test
    fun `a continuation reclaims the stopped run display`() = runTest {
        val backend = backend()
        val session = backend.openApp("run-1", SHOP, TaskDisplaySpec()).session
        backend.cancelForRun("run-1")
        assertEquals(listOf("owner-1"), native.cancelled)

        val reopened = backend.openApp("run-2", SHOP, TaskDisplaySpec())
        assertFalse(reopened.created)
        assertEquals(session, reopened.session)
        assertEquals(session, backend.current("run-2"))
        assertTrue(backend.isDisplayClaimedByRun(session.displayId, "run-2"))
        assertFalse(backend.isDisplayClaimedByRun(session.displayId, "run-1"))
    }

    @Test
    fun `resolve display reports invalid missing and changed references`() = runTest {
        val backend = backend()
        val session = backend.create("run-1", SHOP)
        assertEquals("INVALID_DISPLAY_ID", (backend.resolveDisplay(0) as TaskDisplayResolution.Unavailable).code)
        assertEquals("DISPLAY_NOT_FOUND", (backend.resolveDisplay(99) as TaskDisplayResolution.Unavailable).code)
        assertEquals(
            "DISPLAY_REFERENCE_CHANGED",
            (backend.resolveDisplay(session.displayId, expectedDisplayRef = "dsp_00000000000000") as TaskDisplayResolution.Unavailable).code,
        )
        val ready = backend.resolveDisplay(session.displayId, expectedDisplayRef = taskDisplayReference("run-1", 7))
        assertEquals(session, (ready as TaskDisplayResolution.Ready).target.session)
    }

    @Test
    fun `retain terminalizes the record and expiry closes the display`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        val retainedAt = now()
        backend.retainForRun("run-1", TaskDisplayStatus.COMPLETED)

        val retained = backend.record("run-1")
        assertEquals(TaskDisplayStatus.COMPLETED, retained.status)
        assertEquals(retainedAt, retained.terminalAtEpochMs)
        assertEquals(retainedAt + RETENTION_MS, retained.expiresAtEpochMs)
        assertEquals("Task complete", retained.lastPurpose)

        advanceTimeBy(RETENTION_MS - 1)
        runCurrent()
        assertEquals(TaskDisplayStatus.COMPLETED, backend.record("run-1").status)

        advanceTimeBy(2)
        runCurrent()
        val expired = backend.record("run-1")
        assertEquals(TaskDisplayStatus.EXPIRED, expired.status)
        assertEquals(retainedAt + RETENTION_MS, expired.expiresAtEpochMs)
        assertEquals(listOf("run-1"), native.closed)
        assertNull(backend.current("run-1"))
    }

    @Test
    fun `retain keeps an explicit purpose and error`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        backend.updatePurposeForRun("run-1", "  Checking out  ")
        backend.retainForRun("run-1", TaskDisplayStatus.FAILED, "  The app crashed.  ")
        val record = backend.record("run-1")
        assertEquals("Checking out", record.lastPurpose)
        assertEquals("The app crashed.", record.error)
    }

    @Test
    fun `a retained display is the default target and is revived when claimed`() = runTest {
        val backend = backend()
        val session = backend.create("run-1", SHOP)
        assertEquals(
            "TASK_DISPLAY_UNAVAILABLE",
            (backend.resolveDefaultDisplay() as TaskDisplayResolution.Unavailable).code,
        )
        backend.cancelForRun("run-1")
        backend.retainForRun("run-1", TaskDisplayStatus.STOPPED)

        val claimed = backend.resolveDefaultDisplay(claimForSessionKey = "run-2")
        assertEquals(session, (claimed as TaskDisplayResolution.Ready).target.session)
        val revived = backend.record("run-1")
        assertEquals(TaskDisplayStatus.RUNNING, revived.status)
        assertNull(revived.terminalAtEpochMs)
        assertNull(revived.expiresAtEpochMs)
        assertEquals("Preparing request", revived.lastPurpose)
    }

    @Test
    fun `two retained displays require an explicit selection`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        backend.create("run-2", SHOP)
        backend.retainForRun("run-1", TaskDisplayStatus.COMPLETED)
        backend.retainForRun("run-2", TaskDisplayStatus.COMPLETED)
        assertEquals(
            "DISPLAY_SELECTION_REQUIRED",
            (backend.resolveDefaultDisplay() as TaskDisplayResolution.Unavailable).code,
        )
    }

    @Test
    fun `an expired display cannot be claimed`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        backend.retainForRun("run-1", TaskDisplayStatus.COMPLETED)
        advanceTimeBy(RETENTION_MS + 1)
        runCurrent()
        val resolution = backend.resolveDisplay(7, claimForSessionKey = "run-2")
        assertEquals("DISPLAY_EXPIRED", (resolution as TaskDisplayResolution.Unavailable).code)
    }

    @Test
    fun `close by display id and by display reference`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        backend.create("run-2", SHOP)

        val byId = backend.closeTaskDisplay(7, expectedDisplayRef = null)
        val closedRecord = (byId as TaskDisplayCloseResult.Closed).record
        assertEquals(TaskDisplayStatus.ENDED, closedRecord.status)
        assertEquals(now(), closedRecord.expiresAtEpochMs)
        assertEquals(listOf("run-1"), native.closed)

        val byRef = backend.closeTaskDisplay(8, taskDisplayReference("run-2", 8))
        assertEquals("run-2", (byRef as TaskDisplayCloseResult.Closed).record.sessionKey)
        assertEquals(listOf("run-1", "run-2"), native.closed)

        val again = backend.closeTaskDisplay(7, taskDisplayReference("run-1", 7))
        assertEquals("DISPLAY_ENDED", (again as TaskDisplayCloseResult.Rejected).code)
    }

    @Test
    fun `close rejects invalid unknown and mismatched references`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        assertEquals("INVALID_DISPLAY_ID", (backend.closeTaskDisplay(0, null) as TaskDisplayCloseResult.Rejected).code)
        assertEquals("DISPLAY_NOT_FOUND", (backend.closeTaskDisplay(9, null) as TaskDisplayCloseResult.Rejected).code)
        assertEquals(
            "DISPLAY_NOT_FOUND",
            (backend.closeTaskDisplay(7, "dsp_00000000000000") as TaskDisplayCloseResult.Rejected).code,
        )
        assertEquals(
            "DISPLAY_REFERENCE_CHANGED",
            (backend.closeTaskDisplay(8, taskDisplayReference("run-1", 7)) as TaskDisplayCloseResult.Rejected).code,
        )
        assertTrue(native.closed.isEmpty())
    }

    @Test
    fun `close by owner key ends the record`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        backend.close("run-1")
        assertEquals(TaskDisplayStatus.ENDED, backend.record("run-1").status)
        assertEquals(listOf("run-1"), native.closed)
        assertNull(backend.current("run-1"))
        assertNull(backend.activeSession.value)
    }

    @Test
    fun `close all ends every display without clearing records by default`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        backend.create("run-2", SHOP)
        backend.closeAllTaskDisplays()
        assertEquals(listOf(TaskDisplayStatus.ENDED, TaskDisplayStatus.ENDED), backend.displayRecords.value.map { it.status })
        assertEquals(1, native.closeAllCalls)
        assertEquals(setOf("run-1", "run-2"), native.cancelled.toSet())
        backend.closeAllTaskDisplays(clearRecords = true)
        assertTrue(backend.displayRecords.value.isEmpty())
    }

    @Test
    fun `reconcile keeps matching native sessions`() = runTest {
        val backend = backend()
        val session = backend.create("run-1", SHOP)
        backend.reconcileNativeSessionsNow()
        assertEquals(session, backend.current("run-1"))
        assertEquals(TaskDisplayStatus.RUNNING, backend.record("run-1").status)
        assertTrue(native.closed.isEmpty())
    }

    @Test
    fun `reconcile marks a lost native session unavailable`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        native.live.clear()
        backend.reconcileNativeSessionsNow()
        val record = backend.record("run-1")
        assertEquals(TaskDisplayStatus.UNAVAILABLE, record.status)
        assertEquals(now(), record.terminalAtEpochMs)
        assertEquals(now() + RETENTION_MS, record.expiresAtEpochMs)
        assertEquals("The DHD display session is no longer active. The display service may have restarted.", record.error)
        assertNull(backend.current("run-1"))
    }

    @Test
    fun `reconcile closes a native session with a different identity`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        native.reconcileResult = { mapOf("run-1" to nativeSession("run-1", SHOP, displayId = 9)) }
        backend.reconcileNativeSessionsNow()
        assertEquals(listOf("run-1"), native.closed)
        val record = backend.record("run-1")
        assertEquals(TaskDisplayStatus.UNAVAILABLE, record.status)
        assertEquals("The native display did not match the persisted task identity.", record.error)
    }

    @Test
    fun `reconcile failure retries then marks records unavailable`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        native.reconcileFailure = IllegalStateException("daemon restarting")
        val callsBefore = native.reconcileCalls
        backend.reconcileNativeSessionsNow()
        assertEquals(4, native.reconcileCalls - callsBefore)
        assertEquals(1_750L, testScheduler.currentTime)
        val record = backend.record("run-1")
        assertEquals(TaskDisplayStatus.UNAVAILABLE, record.status)
        assertEquals("daemon restarting", record.error)
    }

    @Test
    fun `a missing app task ends the display after three polls`() = runTest {
        val backend = backend()
        backend.create("run-1", SHOP)
        activities.output = """
            Display #7 (activities from top to bottom):
              mResumedActivity: ActivityRecord{a u0 com.android.launcher/.Launcher t1}
        """.trimIndent()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(TaskDisplayStatus.RUNNING, backend.record("run-1").status)
        advanceTimeBy(1_000)
        runCurrent()
        val ended = backend.record("run-1")
        assertEquals(TaskDisplayStatus.ENDED, ended.status)
        assertEquals("App task ended", ended.lastPurpose)
        assertEquals("The target app task is no longer present on the virtual display.", ended.error)
        assertEquals(listOf("run-1"), native.closed)
    }

    @Test
    fun `capture returns the frame with the focused app on its display`() = runTest {
        val backend = backend()
        val session = backend.create("run-1", SHOP)
        val capture = backend.capture(session)
        assertEquals(listOf<Byte>(1, 2, 3), capture.screenshot.toList())
        assertEquals(SHOP, capture.foreground.packageName)
        assertEquals("$SHOP.MainActivity", capture.foreground.activityName)
        assertEquals(7, capture.foreground.displayId)
        assertEquals("run-1@7", capture.taskId)
    }

    @Test
    fun `focused window parsing is scoped to the requested display`() = runTest {
        val dump = """
            Display #0 (activities from top to bottom):
              mResumedActivity: ActivityRecord{a u0 com.example.home/.Home t1}
            Display #7 (activities from top to bottom):
              topResumedActivity=ActivityRecord{b u0 com.example.shop/com.example.shop.CartActivity t42}
              mFocusedApp=ActivityRecord{c u0 com.example.shop/.CheckoutActivity t42}
            Display #8 (activities from top to bottom):
              mResumedActivity: ActivityRecord{d u0 com.example.mail/.Inbox t43}
        """.trimIndent()
        assertEquals(
            FocusedComponent("com.example.shop", "com.example.shop.CheckoutActivity"),
            ActivityDumpParser.displayFocusedWindow(dump, 7),
        )
        assertEquals(
            FocusedComponent("com.example.mail", "com.example.mail.Inbox"),
            ActivityDumpParser.displayFocusedWindow(dump, 8),
        )
        assertNull(ActivityDumpParser.displayFocusedWindow(dump, 9))
        assertNull(ActivityDumpParser.displayFocusedWindow("Display #7\n  mCurrentFocus=null", 7))
    }
}
