package com.phonecontrol.assistant.display

import android.content.Context
import android.view.Surface
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.core.runCatchingUnlessCancelled
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.observation.ActivityDumpParser
import com.phonecontrol.assistant.observation.ForegroundAppInfo
import com.phonecontrol.assistant.execution.PhoneProcessRunner
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplayCapture
import com.phonecontrol.assistant.execution.TaskDisplayGeometry
import com.phonecontrol.assistant.execution.TaskDisplayLayoutPreferences
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplaySpec
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplayCloseResult
import com.phonecontrol.assistant.execution.TaskDisplayOpenResult
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.TaskDisplayTarget
import com.phonecontrol.assistant.execution.isTerminal
import com.phonecontrol.assistant.execution.taskDisplayAppLayoutMatches
import com.phonecontrol.assistant.execution.terminalized
import com.phonecontrol.assistant.execution.withFullSizeAppLayout
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Adapts the shell-UID native display service to the execution-layer contract.
 *
 * The adapter owns the app-process mapping from coordinator session keys to the
 * native session object. This is intentional: the native manager's map is
 * private and its API returns a result rather than a nullable current-session
 * lookup. Keeping the identity map here gives observations/actions one shared
 * lease and lets Stop invalidate it before asynchronous native cleanup runs.
 */
class DhdTaskDisplayBackend internal constructor(
    private val nativeManager: NativeDisplayManager,
    private val processRunner: PhoneProcessRunner,
    private val conversationStore: ConversationStore?,
    private val nowEpochMs: () -> Long,
    private val terminalRetentionMs: Long,
    private val platform: TaskDisplayPlatform,
    private val scope: CoroutineScope,
    private val newOwnerKey: () -> String,
) : TaskDisplayBackend {
    constructor(
        context: Context,
        nativeManager: DhdVirtualDisplayManager,
        processRunner: PhoneProcessRunner,
        layoutPreferences: TaskDisplayLayoutPreferences,
        conversationStore: ConversationStore? = null,
        nowEpochMs: () -> Long = { System.currentTimeMillis() },
        terminalRetentionMs: Long = TERMINAL_RETENTION_MS,
    ) : this(
        nativeManager = nativeManager,
        processRunner = processRunner,
        conversationStore = conversationStore,
        nowEpochMs = nowEpochMs,
        terminalRetentionMs = terminalRetentionMs,
        platform = AndroidTaskDisplayPlatform(context.applicationContext, layoutPreferences),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        newOwnerKey = { "dhd-${UUID.randomUUID()}" },
    )

    private val stateLock = Mutex()
    private val sessions = LinkedHashMap<String, BoundSession>()
    private val bindings = RunBindingRegistry()
    private val appOpenMutex = Mutex()
    private val _activeSession = MutableStateFlow<TaskDisplaySession?>(null)
    private val records = DisplayRecordStore(conversationStore)
    private val previews = LivePreviewRegistry(
        scope = scope,
        stateLock = stateLock,
        activeSessionKey = { _activeSession.value?.sessionKey },
    )
    private val retention = RetentionScheduler(scope, nowEpochMs) { sessionKey, expiresAt ->
        expire(sessionKey, expiresAt)
    }
    private val livenessMonitor = TaskLivenessMonitor(
        processRunner = processRunner,
        liveSessions = { stateLock.withLock { sessions.values.map { it.taskSession } } },
        currentPackageName = { session -> records.find(session.sessionKey)?.packageName ?: session.packageName },
        onTaskMissing = ::endMissingAppTask,
    )
    private val reconciler = DisplayReconciler(
        nativeManager = nativeManager,
        records = records,
        bindings = bindings,
        retention = retention,
        platform = platform,
        nowEpochMs = nowEpochMs,
        terminalRetentionMs = terminalRetentionMs,
        host = object : DisplayReconcilerHost {
            override fun taskSessionFor(nativeSession: DhdVirtualDisplaySession): TaskDisplaySession =
                nativeSession.toTaskSession()

            override suspend fun removeLocalSession(sessionKey: String, expectedTaskId: String?) =
                this@DhdTaskDisplayBackend.removeLocalSession(sessionKey, expectedTaskId)

            override suspend fun adopt(
                record: TaskDisplayRecord,
                nativeSession: DhdVirtualDisplaySession,
                taskSession: TaskDisplaySession,
            ) = adoptNativeSession(record, nativeSession, taskSession)

            override suspend fun foregroundPackage(taskSession: TaskDisplaySession): String? =
                resolveForeground(taskSession)?.packageName

            override fun publishOpenedApp(taskSession: TaskDisplaySession, packageName: String) =
                this@DhdTaskDisplayBackend.publishOpenedApp(taskSession, packageName)

            override suspend fun selectActiveSession() = selectActiveSessionAfterReconcile()
        },
    )
    private val reconciliationJob: Job
    private val taskLivenessJob: Job

    init {
        records.restore(nowEpochMs())
        reconciliationJob = scope.launch { reconciler.reconcileWithRetry() }
        taskLivenessJob = scope.launch {
            reconciliationJob.join()
            livenessMonitor.run()
        }
    }

    /** The one task display currently shown by the DHD task UI, if any. */
    val activeSession: StateFlow<TaskDisplaySession?> = _activeSession.asStateFlow()

    /** Attach/detach/error status for the read-only AVC decoder surface. */
    val previewState: StateFlow<TaskPreviewState> = previews.previewState

    /** Per-session preview state used by the full-screen viewer and manager. */
    val previewStates: StateFlow<Map<String, TaskPreviewState>> = previews.previewStates

    override val displayRecords: StateFlow<List<TaskDisplayRecord>> = records.records

    override suspend fun create(
        sessionKey: String,
        packageName: String,
        spec: TaskDisplaySpec,
    ): TaskDisplaySession = createOwned(sessionKey, sessionKey, packageName, spec)

    private suspend fun createOwned(
        sessionKey: String,
        runSessionKey: String,
        packageName: String,
        spec: TaskDisplaySpec,
    ): TaskDisplaySession {
        val operationLock = bindings.operationLock(sessionKey)
        return operationLock.withLock {
            reconciliationJob.join()
            if (bindings.isCancelled(sessionKey)) {
                throw TaskDisplayException("The task display session was stopped before creation.")
            }
            taskDisplayUnsupportedReason(platform.sdkInt)?.let { reason -> throw TaskDisplayException(reason) }
            stateLock.withLock {
                if (sessions.containsKey(sessionKey)) {
                    throw TaskDisplayException("The task display session is already active.")
                }
            }
            val effectiveSpec = spec.withFullSizeAppLayout(
                platform.isFullSizeLayoutEnabled(packageName),
            )
            val nativeSpec = DhdVirtualDisplaySpec(
                width = effectiveSpec.width,
                height = effectiveSpec.height,
                densityDpi = effectiveSpec.densityDpi,
                appDensityDpi = effectiveSpec.appDensityDpi,
                appDisplayWidth = effectiveSpec.appDisplayWidth,
                appDisplayHeight = effectiveSpec.appDisplayHeight,
            )
            val result = nativeManager.create(sessionKey, packageName, nativeSpec)
            val nativeSession = when (result) {
                is DhdVirtualDisplayResult.Created -> result.session
                is DhdVirtualDisplayResult.Failed -> {
                    throw TaskDisplayException(result.message)
                }
            }
            val taskSession = nativeSession.toTaskSession()
            val bound = BoundSession(nativeSession, taskSession)
            val record = taskSession.toRecord(
                status = TaskDisplayStatus.RUNNING,
                createdAtEpochMs = nowEpochMs(),
                lastPurpose = conversationStore?.currentPurpose(runSessionKey)
                    ?.take(MAX_RECORD_PURPOSE_CHARS)
                    ?.ifBlank { null }
                    ?: CoordinatorCopy.PREPARING_REQUEST,
            )
            val shouldClose = stateLock.withLock {
                bindings.locked {
                    if (bindings.isCancelled(sessionKey) || bindings.isCancelled(runSessionKey)) {
                        true
                    } else {
                        sessions[sessionKey] = bound
                        bindings.bind(runSessionKey, sessionKey)
                        _activeSession.value = taskSession
                        previews.publish(
                            sessionKey,
                            TaskPreviewState.Connecting(taskSession),
                        )
                        // Publish while both locks are held. Stop either
                        // tombstones this owner first or sees the new binding.
                        records.publish(record)
                        false
                    }
                }
            }
            if (shouldClose) {
                nativeManager.close(sessionKey)
                throw TaskDisplayException("The task display session was stopped while it was starting.")
            }
            taskSession
        }
    }

    /**
     * Prepare a display for an app launch without multiplying valid task
     * displays. A changed per-app layout makes the current display
     * incompatible, so it is retired and recreated under the requesting run.
     */
    override suspend fun openApp(
        sessionKey: String,
        packageName: String,
        spec: TaskDisplaySpec,
    ): TaskDisplayOpenResult = appOpenMutex.withLock {
        reconciliationJob.join()
        val expectedSpec = spec.withFullSizeAppLayout(
            platform.isFullSizeLayoutEnabled(packageName),
        )
        val current = current(sessionKey)
        if (current != null) {
            if (taskDisplayAppLayoutMatches(current, expectedSpec)) {
                return@withLock TaskDisplayOpenResult(current, created = false)
            }
            close(current)
            return@withLock TaskDisplayOpenResult(
                createWithFreshOwner(sessionKey, packageName, spec),
                created = true,
            )
        }

        reusableDisplayCandidates(sessionKey, packageName, expectedSpec).forEach { candidate ->
            when (val resolution = resolveDisplay(
                displayId = candidate.session.displayId,
                claimForSessionKey = sessionKey,
                expectedDisplayRef = candidate.displayRef,
            )) {
                is TaskDisplayResolution.Ready -> {
                    return@withLock TaskDisplayOpenResult(
                        resolution.target.session,
                        created = false,
                    )
                }

                is TaskDisplayResolution.Unavailable -> Unit
            }
        }

        // Every app open gets a fresh native owner key. A previous display
        // may have been closed or expired even when no record is left here;
        // its key remains permanently tombstoned in the native manager.
        TaskDisplayOpenResult(
            createWithFreshOwner(sessionKey, packageName, spec),
            created = true,
        )
    }

    override suspend fun markAppOpened(session: TaskDisplaySession, packageName: String) {
        val operationLock = bindings.operationLock(session.sessionKey)
        operationLock.withLock {
            val stillCurrent = stateLock.withLock {
                sessions[session.sessionKey]?.taskSession == session
            }
            if (stillCurrent) publishOpenedApp(session, packageName)
        }
    }

    private fun publishOpenedApp(session: TaskDisplaySession, packageName: String) {
        val record = records.find(session.sessionKey) ?: return
        if (record.taskId != session.taskId || record.packageName == packageName) return
        records.publish(record.withOpenedPackage(packageName))
    }

    private suspend fun createWithFreshOwner(
        runSessionKey: String,
        packageName: String,
        spec: TaskDisplaySpec,
    ): TaskDisplaySession {
        val ownerKey = newOwnerKey()
        if (!bindings.reserveOwner(runSessionKey, ownerKey)) {
            throw TaskDisplayException("The task display run was stopped before creation.")
        }
        return try {
            createOwned(ownerKey, runSessionKey, packageName, spec)
        } catch (error: Throwable) {
            bindings.unbind(ownerKey)
            throw error
        }
    }

    private suspend fun reusableDisplayCandidates(
        runSessionKey: String,
        packageName: String,
        expectedSpec: TaskDisplaySpec,
    ): List<TaskDisplayTarget> {
        val now = nowEpochMs()
        val boundSessions = stateLock.withLock { sessions.values.toList() }
        return boundSessions.mapNotNull { bound ->
            val session = bound.taskSession
            val record = records.find(session.sessionKey) ?: return@mapNotNull null
            val (alreadyBoundToRun, ownerCancelled) = bindings.claimState(runSessionKey, session.sessionKey)
            if (record.packageName != packageName ||
                !DisplayClaimPolicy.isReusable(record, now) ||
                isTaskDisplayOwnedByAnotherRun(
                    status = record.status,
                    alreadyBoundToRun = alreadyBoundToRun,
                    ownerKey = session.sessionKey,
                    runSessionKey = runSessionKey,
                    ownerCancelled = ownerCancelled,
                ) ||
                !taskDisplayAppLayoutMatches(session, expectedSpec)
            ) {
                return@mapNotNull null
            }
            TaskDisplayTarget(session, record)
        }.sortedByDescending { it.record.createdAtEpochMs }
    }

    override suspend fun current(sessionKey: String): TaskDisplaySession? {
        // Surface callbacks can arrive while the Activity is being recreated;
        // wait for startup reconciliation so a retained display is attachable
        // on the first callback instead of requiring a manual retry.
        reconciliationJob.join()
        return stateLock.withLock {
            // A terminal display remains viewable, but [withSession] still
            // rejects it for agent actions after [cancel] has installed the
            // tombstone.
            sessions[sessionKey]?.taskSession
                // A retained display keeps its native owner key when a
                // later coordinator run claims it. Resolve the logical
                // run key back to that owner so subsequent observe,
                // execute, and foreground calls stay on the same display.
                ?: bindings.boundOwnerKeys(sessionKey)
                    .asSequence()
                    .mapNotNull { ownerKey -> sessions[ownerKey]?.taskSession }
                    .lastOrNull()
        }
    }

    override suspend fun activeDisplaySessions(): List<TaskDisplaySession> {
        reconciliationJob.join()
        return stateLock.withLock { sessions.values.map { it.taskSession } }
    }

    override suspend fun isDisplayClaimedByRun(displayId: Int, runSessionKey: String): Boolean {
        if (displayId <= 0 || runSessionKey.isBlank()) return false
        reconciliationJob.join()
        val ownerKey = stateLock.withLock {
            sessions.values
                .firstOrNull { it.taskSession.displayId == displayId }
                ?.taskSession
                ?.sessionKey
        } ?: return false
        return bindings.isBound(runSessionKey, ownerKey)
    }

    override suspend fun resolveDisplay(
        displayId: Int,
        claimForSessionKey: String?,
        expectedDisplayRef: String?,
    ): TaskDisplayResolution {
        if (displayId <= 0) {
            return TaskDisplayResolution.Unavailable(
                code = "INVALID_DISPLAY_ID",
                message = "The selected task display reference is invalid; the physical display is never controlled by DHD.",
            )
        }
        reconciliationJob.join()
        val bound = stateLock.withLock {
            sessions.values.firstOrNull { it.taskSession.displayId == displayId }
        }
        val record = records.findByDisplayId(displayId)
        if (bound == null) {
            return DisplayClaimPolicy.unavailableForRecord(record, nowEpochMs())
        }
        val target = TaskDisplayTarget(bound.taskSession, record ?: bound.taskSession.toRecord(
            status = TaskDisplayStatus.RUNNING,
            createdAtEpochMs = nowEpochMs(),
        ))
        if (expectedDisplayRef != null && expectedDisplayRef != target.displayRef) {
            return TaskDisplayResolution.Unavailable(
                code = "DISPLAY_REFERENCE_CHANGED",
                message = "The selected task display no longer matches the supplied displayRef. Call dhd_list_displays or dhd_observe again and use the current display.",
                record = record,
            )
        }
        if (claimForSessionKey == null || claimForSessionKey == bound.taskSession.sessionKey) {
            return TaskDisplayResolution.Ready(target)
        }
        return claimDisplayForRun(target, claimForSessionKey)
    }

    override suspend fun resolveDefaultDisplay(
        claimForSessionKey: String?,
    ): TaskDisplayResolution {
        reconciliationJob.join()
        if (claimForSessionKey != null) {
            current(claimForSessionKey)?.let { currentSession ->
                return resolveDisplay(currentSession.displayId, claimForSessionKey)
            }
        }
        val now = nowEpochMs()
        val candidates = stateLock.withLock {
            sessions.values.mapNotNull { bound ->
                val record = records.findByDisplayId(bound.taskSession.displayId)
                val eligible = DisplayClaimPolicy.isDefaultCandidate(record, now)
                if (eligible) TaskDisplayTarget(
                    bound.taskSession,
                    record ?: bound.taskSession.toRecord(
                        status = TaskDisplayStatus.RUNNING,
                        createdAtEpochMs = now,
                    ),
                ) else null
            }
        }
        if (candidates.isEmpty()) {
            return TaskDisplayResolution.Unavailable(
                code = "TASK_DISPLAY_UNAVAILABLE",
                message = "No active or retained task display is available. Call dhd_open_app to create one.",
            )
        }
        if (candidates.size > 1) {
            return TaskDisplayResolution.Unavailable(
                code = "DISPLAY_SELECTION_REQUIRED",
                message = "More than one task display is available. Call dhd_list_displays, choose a displayRef, then retry the tool with that displayRef.",
            )
        }
        val candidate = candidates.single()
        return if (claimForSessionKey == null) {
            TaskDisplayResolution.Ready(candidate)
        } else {
            claimDisplayForRun(candidate, claimForSessionKey)
        }
    }

    private suspend fun claimDisplayForRun(
        target: TaskDisplayTarget,
        runSessionKey: String,
    ): TaskDisplayResolution {
        val ownerKey = target.session.sessionKey
        val operationLock = bindings.operationLock(ownerKey)
        return operationLock.withLock {
            val currentRecord = records.find(ownerKey) ?: target.record
            val (alreadyBoundToRun, ownerCancelled) = bindings.claimState(runSessionKey, ownerKey)
            DisplayClaimPolicy.claimRejection(
                record = currentRecord,
                ownerKey = ownerKey,
                runSessionKey = runSessionKey,
                alreadyBoundToRun = alreadyBoundToRun,
                ownerCancelled = ownerCancelled,
                nowEpochMs = nowEpochMs(),
            )?.let { return@withLock it }
            bindings.clearCancelled(ownerKey)
            bindings.bind(runSessionKey, ownerKey)
            if (currentRecord.status.isTerminal) {
                retention.cancel(ownerKey)
                records.publish(DisplayClaimPolicy.revived(currentRecord))
            }
            TaskDisplayResolution.Ready(
                TaskDisplayTarget(
                    target.session,
                    records.find(ownerKey) ?: currentRecord.copy(status = TaskDisplayStatus.RUNNING),
                ),
            )
        }
    }

    override suspend fun capture(session: TaskDisplaySession): TaskDisplayCapture {
        // Captures are read-only and are also used to verify a retained display
        // from the manager after the run's action tombstone is installed.
        return withDisplayLease(session) {
            val bound = stateLock.withLock {
                sessions[session.sessionKey]
                    ?.takeIf { it.taskSession == session }
                    ?: throw TaskDisplayException("The task display session is no longer active.")
            }
            val capture = nativeManager.capture(bound.nativeSession)
            if (capture.session.sessionKey != session.sessionKey ||
                capture.session.displayId != session.displayId
            ) {
                throw TaskDisplayException("The task display changed while it was being captured.")
            }
            val foreground = resolveForeground(session)
                ?: throw TaskDisplayException(
                    "The foreground app on the selected task display could not be identified.",
                )
            val geometry = session.geometry.copy(
                width = capture.session.width,
                height = capture.session.height,
                densityDpi = capture.session.densityDpi,
                rotation = foreground.rotation,
            )
            TaskDisplayCapture(
                screenshot = capture.png,
                foreground = foreground.copy(
                    displayId = session.displayId,
                    width = capture.session.width,
                    height = capture.session.height,
                    rotation = geometry.rotation,
                ),
                taskId = session.taskId,
                geometry = geometry,
            )
        }
    }

    override suspend fun <T> withSession(
        session: TaskDisplaySession,
        block: suspend () -> T,
    ): T {
        val operationLock = bindings.operationLock(session.sessionKey)
        return operationLock.withLock {
            if (bindings.isCancelled(session.sessionKey)) {
                throw TaskDisplayException("The task display session was stopped.")
            }
            stateLock.withLock {
                if (sessions[session.sessionKey]?.taskSession != session) {
                    throw TaskDisplayException("The task display session is no longer active.")
                }
            }
            refreshRetainedExpiry(session.sessionKey)
            block()
        }
    }

    override suspend fun attachLiveSurface(session: TaskDisplaySession, surface: Surface) {
        try {
            withDisplayLease(session) {
                val bound = stateLock.withLock {
                    sessions[session.sessionKey]
                        ?.takeIf { it.taskSession == session }
                        ?: throw TaskDisplayException("The task display session is no longer active.")
                }
                val existingHandle = stateLock.withLock {
                    previews.handleLocked(session.sessionKey)
                }
                val handle = existingHandle ?: nativeManager.attachLiveSurface(bound.nativeSession)
                stateLock.withLock {
                    if (sessions[session.sessionKey]?.taskSession != session) {
                        if (existingHandle == null) handle.close()
                        throw TaskDisplayException("The task display session ended during preview attach.")
                    }
                    previews.attachLocked(session, surface, handle)
                }
                // The controller owns the authenticated stream. Replacing a
                // viewer only swaps this decoder's Surface and replays the
                // cached GOP; it never tears down the native stream.
                handle.attachSurface(surface)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (isNativeDisplaySessionMissing(error)) {
                val reconciled = runCatchingUnlessCancelled {
                    reconcileNativeSessionsNow()
                }.isSuccess
                if (reconciled) {
                    val stillBound = stateLock.withLock {
                        sessions[session.sessionKey]?.taskSession == session
                    }
                    if (!stillBound) throw error
                }
            }
            val message = error.message ?: error::class.java.simpleName
            previews.publish(
                session.sessionKey,
                TaskPreviewState.Error(session.sessionKey, message),
            )
            throw error
        }
    }

    override suspend fun detachLiveSurface(session: TaskDisplaySession, surface: Surface) {
        val matchingSurface = stateLock.withLock {
            previews.hasSurfaceLocked(session.sessionKey, surface)
        }
        if (!matchingSurface) return
        try {
            withDisplayLease(session) {
                val handle = stateLock.withLock {
                    previews.detachSurfaceLocked(session.sessionKey, surface)
                }
                if (handle == null) return@withDisplayLease
                // A newer Surface may have won the lease while this stale
                // destroy callback was waiting. It owns the decoder. Keep
                // the stream controller alive so the next surface can reuse
                // its authenticated connection and cached GOP.
                handle.detachSurface(surface)
                stateLock.withLock {
                    previews.publish(session.sessionKey, TaskPreviewState.Detached)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Surface destruction is best-effort. A stale detach must never
            // affect the newly attached surface, and the caller has no useful
            // recovery action once its Surface is gone.
        }
    }

    override suspend fun retryLiveSurface(sessionKey: String) {
        val session = current(sessionKey) ?: return
        val surface = stateLock.withLock {
            previews.surfaceLocked(sessionKey)
        } ?: return
        // attachLiveSurface serializes the replacement with any in-flight
        // detach and reuses the persistent stream controller.
        attachLiveSurface(session, surface)
    }

    override fun cancel(sessionKey: String) {
        bindings.markCancelled(sessionKey)
        // Tombstone both layers synchronously. This closes the race where an
        // action/create has crossed into the daemon but cleanup has not yet
        // acquired its per-key operation lease. The display itself remains
        // alive until retain() schedules expiry or the user explicitly closes
        // it from the task-display manager.
        nativeManager.cancel(sessionKey)
    }

    override fun cancelForRun(sessionKey: String) {
        // A retained display can be claimed by a later coordinator run while
        // keeping its original native owner key. Invalidate every owner bound
        // to this run so an in-flight action cannot outlive the run that
        // authorized it.
        val ownerKeys = bindings.cancelRun(sessionKey)
        ownerKeys.forEach(nativeManager::cancel)
    }

    /** Reconcile app state after the native maintenance daemon is restarted. */
    suspend fun reconcileNativeSessionsNow() {
        reconciliationJob.join()
        reconciler.reconcileWithRetry(force = true)
    }

    private suspend fun endMissingAppTask(session: TaskDisplaySession) {
        val stillCurrent = stateLock.withLock {
            sessions[session.sessionKey]?.taskSession == session
        }
        if (!stillCurrent) return
        closeInternal(
            sessionKey = session.sessionKey,
            finalStatus = TaskDisplayStatus.ENDED,
            finalPurpose = "App task ended",
            finalError = "The target app task is no longer present on the virtual display.",
        )
    }

    override suspend fun retain(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        require(status.isTerminal) { "Only terminal statuses may retain a task display." }
        val operationLock = bindings.operationLock(sessionKey)
        operationLock.withLock {
            retainLocked(sessionKey, status, error)
        }
    }

    private suspend fun retainLocked(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        val bound = stateLock.withLock { sessions[sessionKey] }
        val existing = records.find(sessionKey)
        if (existing?.status == TaskDisplayStatus.ENDED || existing?.status == TaskDisplayStatus.EXPIRED) {
            return
        }
        val base = existing ?: bound?.taskSession?.toRecord(
            status = status,
            createdAtEpochMs = nowEpochMs(),
        ) ?: return
        val retained = base.terminalized(
            status = status,
            terminalAtEpochMs = nowEpochMs(),
            retentionMs = terminalRetentionMs,
            error = error,
        )
        records.publish(retained)
        retention.schedule(retained)
    }

    override suspend fun retainForRun(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        bindings.boundOwnerKeys(sessionKey).forEach { ownerKey ->
            val operationLock = bindings.operationLock(ownerKey)
            operationLock.withLock {
                val stillBoundToRun = bindings.isBound(sessionKey, ownerKey)
                if (stillBoundToRun) retainLocked(ownerKey, status, error)
            }
        }
    }

    override suspend fun updateStatus(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        val operationLock = bindings.operationLock(sessionKey)
        operationLock.withLock {
            updateStatusLocked(sessionKey, status, error)
        }
    }

    private fun updateStatusLocked(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        val existing = records.find(sessionKey) ?: return
        if (existing.status.isTerminal && !status.isTerminal) return
        records.publish(
            existing.copy(
                status = status,
                error = error?.trim()?.take(MAX_RECORD_ERROR_CHARS),
            ),
        )
    }

    override suspend fun updateStatusForRun(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        bindings.boundOwnerKeys(sessionKey).forEach { ownerKey ->
            val operationLock = bindings.operationLock(ownerKey)
            operationLock.withLock {
                val stillBoundToRun = bindings.isBound(sessionKey, ownerKey)
                if (stillBoundToRun) updateStatusLocked(ownerKey, status, error)
            }
        }
    }

    override fun updatePurpose(sessionKey: String, purpose: String) {
        val safePurpose = purpose.trim().take(MAX_RECORD_PURPOSE_CHARS).ifBlank { return }
        val existing = records.find(sessionKey) ?: return
        records.publish(existing.copy(lastPurpose = safePurpose))
    }

    override fun updatePurposeForRun(sessionKey: String, purpose: String) {
        bindings.boundOwnerKeys(sessionKey).forEach { ownerKey ->
            updatePurpose(ownerKey, purpose)
        }
    }

    override suspend fun close(session: TaskDisplaySession) {
        close(session.sessionKey, expected = session)
    }

    override suspend fun close(sessionKey: String) {
        close(sessionKey, expected = null)
    }

    private suspend fun close(sessionKey: String, expected: TaskDisplaySession?) {
        val operationLock = bindings.operationLock(sessionKey)
        withContext(NonCancellable) {
            operationLock.withLock {
                var endedSession: TaskDisplaySession? = null
                val shouldClose = stateLock.withLock {
                    val current = sessions[sessionKey]
                    if (expected != null && current?.taskSession != expected) {
                        false
                    } else {
                        endedSession = sessions.remove(sessionKey)?.taskSession
                        previews.stopObservingLocked(sessionKey)
                        previews.removeHandleLocked(sessionKey)?.close()
                        if (_activeSession.value?.sessionKey == sessionKey) {
                            _activeSession.value = sessions.values.lastOrNull()?.taskSession
                        }
                        endedSession?.let { session ->
                            previews.publish(
                                sessionKey,
                                TaskPreviewState.Ended(
                                    session = session,
                                    message = "The virtual display ended.",
                                ),
                            )
                        }
                        true
                    }
                }
                if (!shouldClose && expected != null) return@withLock
                bindings.unbind(sessionKey)
                retention.cancel(sessionKey)
                val existing = records.find(sessionKey)
                if (existing != null) {
                    records.publish(DisplayClaimPolicy.ended(existing, nowEpochMs()))
                }
                // Publish the local terminal state before talking to the daemon.
                // A restarted/unavailable maintenance service must not make the
                // Task Displays End action appear unresponsive. Native close is
                // still attempted by exact owner key as best effort cleanup.
                if (shouldClose || expected == null) {
                    runCatchingUnlessCancelled { nativeManager.close(sessionKey) }
                }
            }
        }
    }

    override suspend fun closeTaskDisplay(
        displayId: Int,
        expectedDisplayRef: String?,
    ): TaskDisplayCloseResult {
        if (displayId <= 0) {
            return TaskDisplayCloseResult.Rejected(
                code = "INVALID_DISPLAY_ID",
                message = "The selected task display reference is invalid; the physical display is never controlled by DHD.",
            )
        }
        // Display IDs can be reused after a native display service restart.
        // When the caller supplies the generation-aware reference, resolve by
        // that reference first; resolving by displayId alone can select an
        // older persisted record and reject a valid End request as stale.
        val record = if (expectedDisplayRef != null) {
            records.findByDisplayRef(expectedDisplayRef)
        } else {
            records.findByDisplayId(displayId)
        }
            ?: return DisplayClaimPolicy.closeNotFound()
        DisplayClaimPolicy.closeRejection(record, displayId, expectedDisplayRef)?.let { return it }
        val bound = stateLock.withLock {
            sessions.values.firstOrNull { it.taskSession.displayId == displayId }
        }
        if (bound != null) {
            close(bound.taskSession)
        } else {
            // The persisted record can outlive a native session. Closing by
            // its exact owner key is still safe and never targets display 0.
            close(record.sessionKey)
        }
        val closed = records.find(record.sessionKey) ?: record.copy(
            status = TaskDisplayStatus.ENDED,
            terminalAtEpochMs = record.terminalAtEpochMs ?: nowEpochMs(),
            expiresAtEpochMs = nowEpochMs(),
        )
        return TaskDisplayCloseResult.Closed(closed)
    }

    override suspend fun closeAllTaskDisplays(clearRecords: Boolean) {
        withContext(NonCancellable) {
            val now = nowEpochMs()
            val allKeys = mutableSetOf<String>()
            stateLock.withLock {
                allKeys.addAll(sessions.keys)
            }
            allKeys.addAll(records.sessionKeys())

            allKeys.forEach { key ->
                bindings.markCancelled(key)
                nativeManager.cancel(key)
            }

            stateLock.withLock {
                sessions.values.forEach { bound ->
                    val sessionKey = bound.taskSession.sessionKey
                    previews.stopObservingLocked(sessionKey)
                    previews.removeHandleLocked(sessionKey)?.close()
                    previews.publish(
                        sessionKey,
                        TaskPreviewState.Ended(
                            session = bound.taskSession,
                            message = "The virtual display ended.",
                        ),
                    )
                }
                sessions.clear()
                _activeSession.value = null
                previews.resetInlineLocked()
            }

            bindings.clear()

            allKeys.forEach { key ->
                retention.cancel(key)
            }

            if (clearRecords) {
                records.deleteAll()
            } else {
                records.endAll(now)
            }

            runCatchingUnlessCancelled { nativeManager.closeAll() }
        }
    }

    /** Serialize preview attach/detach without applying the action tombstone. */
    private suspend fun <T> withDisplayLease(
        session: TaskDisplaySession,
        block: suspend () -> T,
    ): T {
        val operationLock = bindings.operationLock(session.sessionKey)
        return operationLock.withLock {
            stateLock.withLock {
                if (sessions[session.sessionKey]?.taskSession != session) {
                    throw TaskDisplayException("The task display session is no longer active.")
                }
            }
            refreshRetainedExpiry(session.sessionKey)
            block()
        }
    }

    private suspend fun adoptNativeSession(
        record: TaskDisplayRecord,
        nativeSession: DhdVirtualDisplaySession,
        taskSession: TaskDisplaySession,
    ) {
        stateLock.withLock {
            val claimedByDifferentRun = bindings.isOwnerBoundToDifferentRun(record.sessionKey)
            sessions[record.sessionKey] = BoundSession(nativeSession, taskSession)
            // A forced reconciliation can happen while a
            // continuation is already using this native owner. Keep
            // that logical binding instead of silently moving it back
            // to the stopped owner's key.
            bindings.ensureOwnerBinding(record.sessionKey)
            // Keep the terminal action tombstone, and keep any
            // in-memory stop tombstone until a continuation explicitly
            // reclaims the display. A live record is not enough to
            // prove that the stopped run is still active.
            if (record.status.isTerminal && !claimedByDifferentRun) {
                bindings.markCancelled(record.sessionKey)
            }
        }
    }

    private suspend fun selectActiveSessionAfterReconcile() {
        stateLock.withLock {
            val liveRecords = displayRecords.value.filter {
                it.status == TaskDisplayStatus.RUNNING || it.status == TaskDisplayStatus.PAUSED
            }
            val candidateRecords = liveRecords.ifEmpty {
                displayRecords.value.filter {
                    it.status.isTerminal &&
                        it.status != TaskDisplayStatus.ENDED &&
                        it.status != TaskDisplayStatus.EXPIRED
                }
            }
            _activeSession.value = candidateRecords
                .mapNotNull { sessions[it.sessionKey]?.taskSession }
                .maxByOrNull { session -> candidateRecords.first { it.sessionKey == session.sessionKey }.createdAtEpochMs }
        }
    }

    private suspend fun removeLocalSession(sessionKey: String, expectedTaskId: String? = null) {
        var removed = false
        val staleHandle = stateLock.withLock {
            val current = sessions[sessionKey]
            if (current == null ||
                (expectedTaskId != null && current.taskSession.taskId != expectedTaskId)
            ) {
                null
            } else {
                removed = true
                sessions.remove(sessionKey)
                previews.stopObservingLocked(sessionKey)
                if (_activeSession.value?.sessionKey == sessionKey) {
                    _activeSession.value = sessions.values.lastOrNull()?.taskSession
                }
                if (previews.isInlineSessionLocked(sessionKey)) {
                    previews.publish(sessionKey, TaskPreviewState.Detached)
                }
                previews.removeHandleLocked(sessionKey)
            }
        }
        staleHandle?.close()
        if (removed) bindings.unbind(sessionKey)
    }

    private fun refreshRetainedExpiry(sessionKey: String) {
        val record = records.find(sessionKey) ?: return
        val refreshed = DisplayClaimPolicy.refreshedExpiry(record, nowEpochMs(), terminalRetentionMs) ?: return
        records.publish(refreshed)
        retention.schedule(refreshed)
    }

    private suspend fun expire(sessionKey: String, expectedExpiry: Long) {
        val record = records.find(sessionKey) ?: return
        if (!DisplayClaimPolicy.isExpiryDue(record, expectedExpiry, nowEpochMs())) return
        closeInternal(
            sessionKey,
            finalStatus = TaskDisplayStatus.EXPIRED,
            expectedExpiry = expectedExpiry,
        )
    }

    private suspend fun closeInternal(
        sessionKey: String,
        finalStatus: TaskDisplayStatus,
        expectedExpiry: Long? = null,
        finalPurpose: String? = null,
        finalError: String? = null,
    ) {
        val operationLock = bindings.operationLock(sessionKey)
        withContext(NonCancellable) {
            operationLock.withLock {
                if (expectedExpiry != null &&
                    !DisplayClaimPolicy.canCloseExpired(records.find(sessionKey), expectedExpiry, nowEpochMs())
                ) {
                    return@withLock
                }
                bindings.markCancelled(sessionKey)
                var endedSession: TaskDisplaySession? = null
                stateLock.withLock {
                    endedSession = sessions.remove(sessionKey)?.taskSession
                    previews.stopObservingLocked(sessionKey)
                    previews.removeHandleLocked(sessionKey)?.close()
                    if (_activeSession.value?.sessionKey == sessionKey) {
                        _activeSession.value = sessions.values.lastOrNull()?.taskSession
                    }
                    endedSession?.let { session ->
                        previews.publish(
                            sessionKey,
                            if (finalStatus == TaskDisplayStatus.ENDED) {
                                TaskPreviewState.Ended(
                                    session = session,
                                    message = finalError ?: "The virtual display ended.",
                                )
                            } else {
                                TaskPreviewState.Detached
                            },
                        )
                    }
                }
                bindings.unbind(sessionKey)
                runCatchingUnlessCancelled { nativeManager.close(sessionKey) }
                retention.cancel(sessionKey)
                records.find(sessionKey)?.let { existing ->
                    records.publish(
                        existing.copy(
                            status = finalStatus,
                            terminalAtEpochMs = existing.terminalAtEpochMs ?: nowEpochMs(),
                            expiresAtEpochMs = existing.expiresAtEpochMs ?: nowEpochMs(),
                            lastPurpose = finalPurpose ?: existing.lastPurpose,
                            error = finalError ?: existing.error,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun resolveForeground(session: TaskDisplaySession): ForegroundAppInfo? {
        val result = processRunner.run(listOf("dumpsys", "activity", "activities"))
        if (result.timedOut || result.exitCode != 0) return null
        val text = result.stdout.toString(Charsets.UTF_8)
        val focused = ActivityDumpParser.displayFocusedWindow(text, session.displayId) ?: return null
        val rotation = platform.displayRotation(session.displayId) ?: return null
        return ForegroundAppInfo(
            packageName = focused.packageName,
            activityName = focused.activityName,
            displayId = session.displayId,
            rotation = rotation,
            width = session.geometry.width,
            height = session.geometry.height,
        )
    }

    private fun DhdVirtualDisplaySession.toTaskSession(): TaskDisplaySession {
        val rotation = platform.displayRotation(displayId)
            ?: Surface.ROTATION_0
        return TaskDisplaySession(
            sessionKey = sessionKey,
            // Native currently exposes the owner key + logical display ID;
            // combining them gives an immutable identity across display-ID
            // reuse and remains distinct when a backend recreates a session.
            taskId = "$sessionKey@$displayId",
            packageName = packageName,
            displayId = displayId,
            streamEndpoint = "127.0.0.1:$streamPort",
            geometry = TaskDisplayGeometry(width, height, densityDpi, rotation),
            appDisplayWidth = appDisplayWidth ?: width,
            appDisplayHeight = appDisplayHeight ?: height,
        )
    }

    private fun TaskDisplaySession.toRecord(
        status: TaskDisplayStatus,
        createdAtEpochMs: Long,
        terminalAtEpochMs: Long? = null,
        expiresAtEpochMs: Long? = null,
        lastPurpose: String = DisplayClaimPolicy.DEFAULT_PURPOSE,
        error: String? = null,
    ): TaskDisplayRecord = TaskDisplayRecord(
        sessionKey = sessionKey,
        taskId = taskId,
        packageName = packageName.ifBlank { "unknown" },
        displayId = displayId,
        width = geometry.width,
        height = geometry.height,
        densityDpi = geometry.densityDpi,
        rotation = geometry.rotation,
        status = status,
        createdAtEpochMs = createdAtEpochMs,
        terminalAtEpochMs = terminalAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        lastPurpose = lastPurpose,
        error = error,
    )

    private data class BoundSession(
        val nativeSession: DhdVirtualDisplaySession,
        val taskSession: TaskDisplaySession,
    )

    class TaskDisplayException(message: String) : IOException(message)

    companion object {
        const val TERMINAL_RETENTION_MS: Long = 30 * 60 * 1000L
        private const val MAX_RECORD_PURPOSE_CHARS = 240
        private const val MAX_RECORD_ERROR_CHARS = 4_000
    }
}

internal fun isNativeDisplaySessionMissing(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        val message = current.message.orEmpty()
        if (message.contains("DHD display session is not active", ignoreCase = true) ||
            message.contains("virtual display session is not active", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
