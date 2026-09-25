package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.execution.TaskDisplayCloseResult
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.isTerminal

/**
 * A stopped run can leave its terminal record queued for asynchronous
 * persistence. The synchronous cancellation tombstone is authoritative while
 * that write is pending, so a continuation may reclaim the native display.
 */
internal fun isTaskDisplayOwnedByAnotherRun(
    status: TaskDisplayStatus,
    alreadyBoundToRun: Boolean,
    ownerKey: String,
    runSessionKey: String,
    ownerCancelled: Boolean,
): Boolean = (status == TaskDisplayStatus.RUNNING || status == TaskDisplayStatus.PAUSED) &&
    !alreadyBoundToRun &&
    !ownerCancelled &&
    ownerKey != runSessionKey

internal fun taskDisplayUnavailableForRecord(
    record: TaskDisplayRecord,
    nowEpochMs: Long,
): TaskDisplayResolution.Unavailable = when {
    record.status == TaskDisplayStatus.ENDED ->
        TaskDisplayResolution.Unavailable(
            code = "DISPLAY_ENDED",
            message = "The selected task display was explicitly ended. Call dhd_open_app with the app package to create a new display.",
            record = record,
        )
    record.status == TaskDisplayStatus.EXPIRED ||
        (record.expiresAtEpochMs != null && record.expiresAtEpochMs <= nowEpochMs) ->
        TaskDisplayResolution.Unavailable(
            code = "DISPLAY_EXPIRED",
            message = "The selected task display expired after its retention period. Call dhd_open_app with the app package to create a new display.",
            record = record,
        )
    else ->
        TaskDisplayResolution.Unavailable(
            code = "DISPLAY_UNAVAILABLE",
            message = "The selected task display is no longer backed by a native DHD session. Call dhd_open_app with the app package to create a new display.",
            record = record,
        )
}

internal object DisplayClaimPolicy {
    fun unavailableForRecord(
        record: TaskDisplayRecord?,
        nowEpochMs: Long,
    ): TaskDisplayResolution.Unavailable {
        if (record == null) {
            return TaskDisplayResolution.Unavailable(
                code = "DISPLAY_NOT_FOUND",
                message = "No DHD task display matches the selected displayRef. Call dhd_list_displays to see the available displays.",
            )
        }
        return taskDisplayUnavailableForRecord(record, nowEpochMs)
    }

    fun isReusable(record: TaskDisplayRecord, nowEpochMs: Long): Boolean =
        record.status != TaskDisplayStatus.ENDED &&
            record.status != TaskDisplayStatus.EXPIRED &&
            record.status != TaskDisplayStatus.UNAVAILABLE &&
            (record.expiresAtEpochMs == null || record.expiresAtEpochMs > nowEpochMs)

    fun isDefaultCandidate(record: TaskDisplayRecord?, nowEpochMs: Long): Boolean =
        record == null ||
            (record.status.isTerminal &&
                record.status != TaskDisplayStatus.ENDED &&
                record.status != TaskDisplayStatus.EXPIRED &&
                (record.expiresAtEpochMs == null || record.expiresAtEpochMs > nowEpochMs))

    fun claimRejection(
        record: TaskDisplayRecord,
        ownerKey: String,
        runSessionKey: String,
        alreadyBoundToRun: Boolean,
        ownerCancelled: Boolean,
        nowEpochMs: Long,
    ): TaskDisplayResolution.Unavailable? {
        if (record.status == TaskDisplayStatus.ENDED ||
            record.status == TaskDisplayStatus.EXPIRED
        ) {
            return unavailableForRecord(record, nowEpochMs)
        }
        if (record.expiresAtEpochMs != null && record.expiresAtEpochMs <= nowEpochMs) {
            return TaskDisplayResolution.Unavailable(
                code = "DISPLAY_EXPIRED",
                message = "The selected task display expired after its retention period. Call dhd_open_app with the app package to create a new display.",
                record = record,
            )
        }
        if (isTaskDisplayOwnedByAnotherRun(
                status = record.status,
                alreadyBoundToRun = alreadyBoundToRun,
                ownerKey = ownerKey,
                runSessionKey = runSessionKey,
                ownerCancelled = ownerCancelled,
            )
        ) {
            return TaskDisplayResolution.Unavailable(
                code = "DISPLAY_IN_USE",
                message = "The selected task display is being used by another active DHD run. Wait for it to finish or stop that run before selecting this display.",
                record = record,
            )
        }
        return null
    }

    fun revived(record: TaskDisplayRecord): TaskDisplayRecord = record.copy(
        status = TaskDisplayStatus.RUNNING,
        terminalAtEpochMs = null,
        expiresAtEpochMs = null,
        lastPurpose = DEFAULT_PURPOSE,
        error = null,
    )

    fun closeNotFound(): TaskDisplayCloseResult.Rejected = TaskDisplayCloseResult.Rejected(
        code = "DISPLAY_NOT_FOUND",
        message = "No DHD task display matches the selected displayRef. Call dhd_list_displays to see the available displays.",
    )

    fun closeRejection(
        record: TaskDisplayRecord,
        displayId: Int,
        expectedDisplayRef: String?,
    ): TaskDisplayCloseResult.Rejected? {
        if (record.displayId != displayId) {
            return TaskDisplayCloseResult.Rejected(
                code = "DISPLAY_REFERENCE_CHANGED",
                message = "The selected task display no longer matches the supplied display reference. Call dhd_list_displays and retry with the current display.",
                record = record,
            )
        }
        if (expectedDisplayRef != null && expectedDisplayRef != record.displayRef) {
            return TaskDisplayCloseResult.Rejected(
                code = "DISPLAY_REFERENCE_CHANGED",
                message = "The selected task display no longer matches the supplied displayRef. Call dhd_list_displays and retry with the current display.",
                record = record,
            )
        }
        if (record.status == TaskDisplayStatus.ENDED) {
            return TaskDisplayCloseResult.Rejected(
                code = "DISPLAY_ENDED",
                message = "The selected task display has already been ended.",
                record = record,
            )
        }
        if (record.status == TaskDisplayStatus.EXPIRED) {
            return TaskDisplayCloseResult.Rejected(
                code = "DISPLAY_EXPIRED",
                message = "The selected task display has already expired and cannot be closed again.",
                record = record,
            )
        }
        return null
    }

    fun ended(record: TaskDisplayRecord, nowEpochMs: Long): TaskDisplayRecord = record.copy(
        status = TaskDisplayStatus.ENDED,
        terminalAtEpochMs = record.terminalAtEpochMs ?: nowEpochMs,
        expiresAtEpochMs = nowEpochMs,
        lastPurpose = record.lastPurpose.ifBlank { "Display ended" },
    )

    fun isGone(record: TaskDisplayRecord): Boolean =
        record.status == TaskDisplayStatus.ENDED || record.status == TaskDisplayStatus.EXPIRED

    fun expired(record: TaskDisplayRecord): TaskDisplayRecord = record.copy(
        status = TaskDisplayStatus.EXPIRED,
        error = record.error ?: "The retained display expired.",
    )

    fun restored(record: TaskDisplayRecord, nowEpochMs: Long): TaskDisplayRecord = when {
        record.status == TaskDisplayStatus.EXPIRED || record.status == TaskDisplayStatus.ENDED -> record
        record.expiresAtEpochMs != null && record.expiresAtEpochMs <= nowEpochMs -> expired(record)
        else -> record
    }

    /**
     * Retained and unavailable displays are useful only while they are being
     * used. Active RUNNING/PAUSED displays deliberately have no idle deadline
     * so a long-running task cannot disappear underneath its agent.
     */
    fun refreshedExpiry(
        record: TaskDisplayRecord,
        nowEpochMs: Long,
        retentionMs: Long,
    ): TaskDisplayRecord? {
        if (record.status == TaskDisplayStatus.ENDED ||
            record.status == TaskDisplayStatus.EXPIRED ||
            (!record.status.isTerminal && record.status != TaskDisplayStatus.UNAVAILABLE)
        ) {
            return null
        }
        return record.copy(
            terminalAtEpochMs = record.terminalAtEpochMs ?: nowEpochMs,
            expiresAtEpochMs = nowEpochMs + retentionMs.coerceAtLeast(0L),
        )
    }

    fun withoutNativeSession(
        record: TaskDisplayRecord,
        message: String,
        nowEpochMs: Long,
        retentionMs: Long,
    ): TaskDisplayRecord = when {
        record.status == TaskDisplayStatus.ENDED ||
            record.status == TaskDisplayStatus.EXPIRED -> record
        record.status.isTerminal && record.expiresAtEpochMs != null &&
            record.expiresAtEpochMs <= nowEpochMs -> expired(record)
        record.status.isTerminal -> record.copy(
            expiresAtEpochMs = record.expiresAtEpochMs
                ?: nowEpochMs + retentionMs.coerceAtLeast(0L),
        )
        record.status == TaskDisplayStatus.UNAVAILABLE &&
            record.expiresAtEpochMs != null && record.expiresAtEpochMs <= nowEpochMs -> expired(record)
        else -> unavailable(record, message, nowEpochMs, retentionMs)
    }

    fun unavailable(
        record: TaskDisplayRecord,
        message: String,
        nowEpochMs: Long,
        retentionMs: Long,
    ): TaskDisplayRecord {
        val unavailableAt = (record.terminalAtEpochMs ?: nowEpochMs)
            .coerceAtLeast(record.createdAtEpochMs)
        return record.copy(
            status = TaskDisplayStatus.UNAVAILABLE,
            terminalAtEpochMs = unavailableAt,
            expiresAtEpochMs = record.expiresAtEpochMs
                ?: unavailableAt + retentionMs.coerceAtLeast(0L),
            error = record.error ?: message,
        )
    }

    fun isExpiryDue(record: TaskDisplayRecord, expectedExpiry: Long, nowEpochMs: Long): Boolean =
        !((record.terminalAtEpochMs == null &&
            !record.status.isTerminal &&
            record.status != TaskDisplayStatus.UNAVAILABLE) ||
            record.expiresAtEpochMs != expectedExpiry ||
            expectedExpiry > nowEpochMs)

    fun canCloseExpired(record: TaskDisplayRecord?, expectedExpiry: Long, nowEpochMs: Long): Boolean =
        !(record == null ||
            record.expiresAtEpochMs != expectedExpiry ||
            (!record.status.isTerminal && record.status != TaskDisplayStatus.UNAVAILABLE) ||
            record.status == TaskDisplayStatus.ENDED ||
            record.status == TaskDisplayStatus.EXPIRED ||
            expectedExpiry > nowEpochMs)

    const val DEFAULT_PURPOSE = CoordinatorCopy.PREPARING_REQUEST
}
