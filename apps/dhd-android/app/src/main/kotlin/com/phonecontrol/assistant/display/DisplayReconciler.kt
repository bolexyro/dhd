package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.core.runCatchingUnlessCancelled
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplaySpec
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.withFullSizeAppLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

internal interface DisplayReconcilerHost {
    fun taskSessionFor(nativeSession: DhdVirtualDisplaySession): TaskDisplaySession
    suspend fun removeLocalSession(sessionKey: String, expectedTaskId: String?)
    suspend fun adopt(
        record: TaskDisplayRecord,
        nativeSession: DhdVirtualDisplaySession,
        taskSession: TaskDisplaySession,
    )
    suspend fun foregroundPackage(taskSession: TaskDisplaySession): String?
    fun publishOpenedApp(taskSession: TaskDisplaySession, packageName: String)
    suspend fun selectActiveSession()
}

internal class DisplayReconciler(
    private val nativeManager: NativeDisplayManager,
    private val records: DisplayRecordStore,
    private val bindings: RunBindingRegistry,
    private val retention: RetentionScheduler,
    private val platform: TaskDisplayPlatform,
    private val nowEpochMs: () -> Long,
    private val terminalRetentionMs: Long,
    private val host: DisplayReconcilerHost,
) {
    suspend fun reconcileWithRetry(force: Boolean = false) {
        var lastFailure: Throwable? = null
        repeat(RECONCILIATION_ATTEMPTS) { attempt ->
            try {
                reconcile(force = force)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt + 1 < RECONCILIATION_ATTEMPTS) {
                    delay(RECONCILIATION_RETRY_DELAY_MS * (1L shl attempt))
                }
            }
        }
        markUnavailable(
            lastFailure?.message ?: "The native display could not be reconciled.",
        )
    }

    private suspend fun reconcile(force: Boolean) {
        val persisted = records.records.value
        val expectedKeys = persisted.map { it.sessionKey }.toSet()
        val native = nativeManager.reconcile(expectedKeys, force = force)
        val now = nowEpochMs()
        persisted.forEach { persistedRecord ->
            val operationLock = bindings.operationLock(persistedRecord.sessionKey)
            operationLock.withLock {
                // Reconciliation can wait on the native daemon while a
                // continuation claims a retained display. Re-read the local
                // record after that wait so an old snapshot cannot overwrite
                // the newer run's lifecycle state.
                val record = records.find(persistedRecord.sessionKey) ?: return@withLock
                val nativeSession = native[record.sessionKey]
                if (nativeSession == null) {
                    host.removeLocalSession(record.sessionKey, record.taskId)
                    val next = DisplayClaimPolicy.withoutNativeSession(
                        record,
                        "The DHD display session is no longer active. The display service may have restarted.",
                        now,
                        terminalRetentionMs,
                    )
                    if (next != record) records.publish(next)
                    if (!DisplayClaimPolicy.isGone(next)) {
                        retention.schedule(next)
                    }
                    return@withLock
                }

                val taskSession = host.taskSessionFor(nativeSession)
                val sameIdentity = taskSession.displayId == record.displayId &&
                    taskSession.taskId == record.taskId &&
                    taskSession.packageName == record.nativePackageName &&
                    taskSession.geometry.width == record.width &&
                    taskSession.geometry.height == record.height &&
                    taskSession.geometry.densityDpi == record.densityDpi &&
                    matchesCurrentAppLayout(nativeSession)
                val expired = record.status == TaskDisplayStatus.EXPIRED ||
                    (record.expiresAtEpochMs != null && record.expiresAtEpochMs <= now)
                val ended = record.status == TaskDisplayStatus.ENDED
                if (!sameIdentity || expired || ended) {
                    host.removeLocalSession(record.sessionKey, record.taskId)
                    runCatchingUnlessCancelled { nativeManager.close(record.sessionKey) }
                    val next = when {
                        expired -> DisplayClaimPolicy.expired(record)
                        ended -> record
                        else -> DisplayClaimPolicy.unavailable(
                            record,
                            "The native display did not match the persisted task identity.",
                            now,
                            terminalRetentionMs,
                        )
                    }
                    if (next != record) records.publish(next)
                    if (!DisplayClaimPolicy.isGone(next)) {
                        retention.schedule(next)
                    }
                    return@withLock
                }

                host.adopt(record, nativeSession, taskSession)
                // Older records only knew the first package launched on this
                // display. Recover the currently visible app after an upgrade.
                if (record.ownerPackageName == null) {
                    val foregroundPackage = try {
                        host.foregroundPackage(taskSession)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                    val launchable = foregroundPackage?.let { packageName ->
                        runCatching {
                            platform.hasLaunchIntent(packageName)
                        }.getOrDefault(false)
                    } == true
                    if (foregroundPackage != null && launchable) {
                        host.publishOpenedApp(taskSession, foregroundPackage)
                    }
                }
                retention.schedule(record)
            }
        }
        host.selectActiveSession()
    }

    /**
     * A retained record predates the logical-canvas profile, so its durable
     * metadata cannot describe the app-visible size. Compare the native
     * session against the current per-package profile before re-adopting it;
     * otherwise a stale 720x1560 app canvas can survive an APK update and be
     * rendered as a letterboxed preview forever.
     */
    private fun matchesCurrentAppLayout(session: DhdVirtualDisplaySession): Boolean {
        val expected = TaskDisplaySpec().withFullSizeAppLayout(
            platform.isFullSizeLayoutEnabled(session.packageName),
        )
        return session.appDensityDpi == expected.appDensityDpi &&
            session.appDisplayWidth == (expected.appDisplayWidth ?: expected.width) &&
            session.appDisplayHeight == (expected.appDisplayHeight ?: expected.height)
    }

    private fun markUnavailable(message: String) {
        val now = nowEpochMs()
        records.records.value.forEach { record ->
            val next = DisplayClaimPolicy.withoutNativeSession(record, message, now, terminalRetentionMs)
            if (next != record) records.publish(next)
            if (!DisplayClaimPolicy.isGone(next)) {
                retention.schedule(next)
            }
        }
    }

    private companion object {
        const val RECONCILIATION_ATTEMPTS = 4
        const val RECONCILIATION_RETRY_DELAY_MS = 250L
    }
}
