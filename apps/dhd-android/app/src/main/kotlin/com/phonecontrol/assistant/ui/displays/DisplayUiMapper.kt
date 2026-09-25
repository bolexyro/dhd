package com.phonecontrol.assistant.ui.displays

import android.content.pm.PackageManager
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.display.TaskPreviewState
import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.taskDisplayReference
import com.phonecontrol.assistant.session.SessionState

internal fun displayRecordsWithPreviewFallback(
    displayRecords: List<TaskDisplayUiRecord>,
    previewState: LiveDisplayPreviewState?,
): List<TaskDisplayUiRecord> =
    // Until the backend exposes its registry, the active preview remains a
    // valid single-record manager model. MainActivity can pass persisted and
    // retained records later without changing the viewer contract.
    displayRecords.ifEmpty {
        previewState?.sessionKey?.let { key ->
            listOf(
                TaskDisplayUiRecord(
                    sessionKey = key,
                    lifecycle = TaskDisplayLifecycle.RUNNING,
                    appLabel = previewState.appLabel,
                    currentPurpose = previewState.purpose,
                    currentToolName = previewState.currentToolName,
                    previewState = previewState,
                ),
            )
        }.orEmpty()
    }

internal fun viewerPreviewState(
    viewerRecord: TaskDisplayUiRecord?,
    previewState: LiveDisplayPreviewState?,
): LiveDisplayPreviewState? = viewerRecord?.previewState
    ?: previewState?.takeIf { it.sessionKey == viewerRecord?.sessionKey }
    ?: viewerRecord?.let { record ->
        val ratio = record.geometry?.let { geometry ->
            geometry.width.toFloat() / geometry.height.toFloat()
        } ?: DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO
        LiveDisplayPreviewState.unavailable(
            message = record.error,
            aspectRatio = ratio,
            sessionKey = record.sessionKey,
        ).copy(
            appLabel = record.appLabel,
            purpose = record.currentPurpose,
            currentToolName = record.currentToolName,
        )
    }

internal fun SessionState.displayPurpose(): String? = when (this) {
    is SessionState.Running -> currentPurpose
    is SessionState.Paused -> currentPurpose
    is SessionState.Stopped -> reason
    is SessionState.Completed -> "Task complete"
    SessionState.Idle -> null
}

internal fun selectDisplayForRun(
    resolvedDisplayForRun: TaskDisplaySession?,
    activeDisplay: TaskDisplaySession?,
    coordinatorSessionKey: String?,
    sessionState: SessionState,
): TaskDisplaySession? = resolvedDisplayForRun
    ?: activeDisplay?.takeIf { it.sessionKey == coordinatorSessionKey }
    // Continue creates a fresh coordinator run before the first
    // tool has a chance to claim the retained display. Keep the
    // previous backend session rendered during that handoff;
    // this callback only attaches the read-only preview surface.
    ?: activeDisplay?.takeIf {
        (sessionState as? SessionState.Running)?.isContinuation == true
    }

internal fun latestToolNameForRun(events: List<ActivityEvent>, sessionKey: String?): String? =
    sessionKey?.let {
        events.asReversed()
            .firstOrNull { event ->
                event.sessionId == sessionKey && !event.toolName.isNullOrBlank() && !ToolNames.isCloseDisplay(event.toolName)
            }
            ?.toolName
    }

internal fun currentPackageForSession(
    session: TaskDisplaySession,
    records: List<TaskDisplayRecord>,
): String = records
    .firstOrNull { it.sessionKey == session.sessionKey }
    ?.packageName ?: session.packageName

internal fun livePreviewForRun(
    session: TaskDisplaySession,
    previewStates: Map<String, TaskPreviewState>,
    playback: TaskPreviewState,
    records: List<TaskDisplayRecord>,
    coordinatorSessionKey: String?,
    pointerEvent: TaskPointerEvent?,
    purpose: String?,
    currentToolName: String?,
    appLabelFor: (String) -> String?,
): LiveDisplayPreviewState {
    // The global preview state is retained for the legacy single
    // viewer. Once a displayRef selects a retained display, use
    // its per-session state so another display's decoder cannot
    // make this preview appear stuck in Connecting.
    val playbackForSession = previewStates[session.sessionKey]
        ?: playback.forSession(session.sessionKey)
    val error = playbackForSession as? TaskPreviewState.Error
    val ended = playbackForSession as? TaskPreviewState.Ended
    val currentPackage = currentPackageForSession(session, records)
    val appLabel = appLabelFor(currentPackage)
    return LiveDisplayPreviewState(
        status = when {
            error?.sessionKey == session.sessionKey -> LiveDisplayPreviewStatus.ERROR
            ended?.session?.sessionKey == session.sessionKey -> LiveDisplayPreviewStatus.UNAVAILABLE
            (playbackForSession as? TaskPreviewState.Attached)?.session == session ->
                LiveDisplayPreviewStatus.LIVE
            else -> LiveDisplayPreviewStatus.CONNECTING
        },
        message = error?.takeIf { it.sessionKey == session.sessionKey }?.message
            ?: ended?.takeIf { it.session.sessionKey == session.sessionKey }?.message,
        aspectRatio = session.geometry.width.toFloat() / session.geometry.height,
        appLabel = appLabel,
        sessionKey = session.sessionKey,
        runSessionKey = coordinatorSessionKey,
        pointerEvent = pointerEvent?.takeIf {
            it.sessionId == coordinatorSessionKey || it.sessionId == session.sessionKey
        },
        purpose = purpose,
        currentToolName = currentToolName,
    )
}

internal fun activeDisplayUiRecord(
    session: TaskDisplaySession,
    currentPackage: String,
    appLabel: String?,
    sessionState: SessionState,
    purpose: String?,
    currentToolName: String?,
    preview: LiveDisplayPreviewState?,
): TaskDisplayUiRecord = TaskDisplayUiRecord(
    sessionKey = session.sessionKey,
    taskId = session.taskId,
    packageName = currentPackage,
    appLabel = appLabel,
    displayId = session.displayId,
    displayRef = taskDisplayReference(session.sessionKey, session.displayId),
    geometry = session.geometry,
    lifecycle = sessionState.toUiDisplayLifecycle(),
    currentPurpose = purpose,
    createdAtEpochMs = sessionState.startedAtEpochMsOrZero(),
    currentToolName = currentToolName,
    previewState = preview,
)

internal fun mergeActiveDisplayRecord(
    records: List<TaskDisplayUiRecord>,
    activeRecord: TaskDisplayUiRecord,
    runIsActive: Boolean,
    purpose: String?,
    currentToolName: String?,
    preview: LiveDisplayPreviewState?,
): List<TaskDisplayUiRecord> {
    val merged = records.toMutableList()
    val index = merged.indexOfFirst { it.sessionKey == activeRecord.sessionKey }
    if (index >= 0) {
        val persisted = merged[index]
        // The coordinator is authoritative while this run is
        // active. Once it reaches a terminal state, retain the
        // backend's precise completed/failed/stopped status and
        // purpose instead of replacing it with a generic state
        // from the UI process.
        merged[index] = if (runIsActive) {
            persisted.copy(
                lifecycle = activeRecord.lifecycle,
                currentPurpose = purpose ?: persisted.currentPurpose,
                currentToolName = currentToolName ?: persisted.currentToolName,
                previewState = preview ?: persisted.previewState,
            )
        } else {
            persisted.copy(previewState = preview ?: persisted.previewState)
        }
    } else {
        merged += activeRecord
    }
    return merged
}

internal fun TaskDisplayRecord.toUiRecord(
    preview: TaskPreviewState?,
    packageManager: PackageManager,
    currentToolName: String? = null,
): TaskDisplayUiRecord = TaskDisplayUiRecord(
    sessionKey = sessionKey,
    taskId = taskId,
    packageName = packageName,
    appLabel = packageName.applicationLabel(packageManager),
    displayId = displayId,
    displayRef = this.displayRef,
    geometry = geometry,
    lifecycle = status.toUiLifecycle(),
    currentPurpose = lastPurpose,
    createdAtEpochMs = createdAtEpochMs,
    terminalAtEpochMs = terminalAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    error = error,
    currentToolName = currentToolName,
    previewState = preview.toUiPreview(
        geometry = geometry,
        sessionKey = sessionKey,
        appLabel = packageName.applicationLabel(packageManager),
        purpose = lastPurpose,
        currentToolName = currentToolName,
    ),
)

internal fun TaskPreviewState?.toUiPreview(
    geometry: com.phonecontrol.assistant.execution.TaskDisplayGeometry,
    sessionKey: String,
    appLabel: String?,
    purpose: String,
    currentToolName: String? = null,
): LiveDisplayPreviewState? {
    val ratio = geometry.width.toFloat() / geometry.height.toFloat()
    return when (this) {
        is TaskPreviewState.Connecting -> LiveDisplayPreviewState(
            status = LiveDisplayPreviewStatus.CONNECTING,
            appLabel = appLabel,
            aspectRatio = ratio,
            sessionKey = sessionKey,
            purpose = purpose,
            currentToolName = currentToolName,
        )
        is TaskPreviewState.Attached -> LiveDisplayPreviewState(
            status = LiveDisplayPreviewStatus.LIVE,
            appLabel = appLabel,
            aspectRatio = ratio,
            sessionKey = sessionKey,
            purpose = purpose,
            currentToolName = currentToolName,
        )
        is TaskPreviewState.Error -> LiveDisplayPreviewState.error(
            message = message,
            appLabel = appLabel,
            aspectRatio = ratio,
            sessionKey = sessionKey,
        ).copy(purpose = purpose, currentToolName = currentToolName)
        is TaskPreviewState.Ended -> LiveDisplayPreviewState.unavailable(
            message = message,
            aspectRatio = ratio,
            sessionKey = sessionKey,
            currentToolName = currentToolName,
        ).copy(appLabel = appLabel, purpose = purpose)
        else -> null
    }
}

internal fun String.applicationLabel(packageManager: PackageManager): String? {
    if (isBlank()) return null
    return runCatching {
        val info = packageManager.getApplicationInfo(this, 0)
        packageManager.getApplicationLabel(info).toString().takeIf(String::isNotBlank)
    }.getOrNull()
}

internal fun TaskDisplayStatus.toUiLifecycle(): TaskDisplayLifecycle = when (this) {
    TaskDisplayStatus.RUNNING -> TaskDisplayLifecycle.RUNNING
    TaskDisplayStatus.PAUSED -> TaskDisplayLifecycle.PAUSED
    TaskDisplayStatus.COMPLETED -> TaskDisplayLifecycle.COMPLETED
    TaskDisplayStatus.FAILED -> TaskDisplayLifecycle.FAILED
    TaskDisplayStatus.STOPPED -> TaskDisplayLifecycle.STOPPED
    TaskDisplayStatus.UNAVAILABLE -> TaskDisplayLifecycle.UNAVAILABLE
    TaskDisplayStatus.ENDED -> TaskDisplayLifecycle.ENDED
    TaskDisplayStatus.EXPIRED -> TaskDisplayLifecycle.EXPIRED
}

internal fun SessionState.toUiDisplayLifecycle(): TaskDisplayLifecycle = when (this) {
    is SessionState.Running -> if (attentionReason != null) {
        TaskDisplayLifecycle.PAUSED
    } else {
        TaskDisplayLifecycle.RUNNING
    }
    is SessionState.Paused -> TaskDisplayLifecycle.PAUSED
    is SessionState.Stopped -> TaskDisplayLifecycle.STOPPED
    is SessionState.Completed -> TaskDisplayLifecycle.COMPLETED
    SessionState.Idle -> TaskDisplayLifecycle.UNAVAILABLE
}

internal fun SessionState.startedAtEpochMsOrZero(): Long = when (this) {
    is SessionState.Running -> startedAtEpochMs
    is SessionState.Paused -> startedAtEpochMs
    else -> 0L
}

internal fun TaskPreviewState.forSession(sessionKey: String): TaskPreviewState? = when (this) {
    TaskPreviewState.Detached -> null
    is TaskPreviewState.Connecting -> takeIf { session.sessionKey == sessionKey }
    is TaskPreviewState.Attached -> takeIf { session.sessionKey == sessionKey }
    is TaskPreviewState.Ended -> takeIf { session.sessionKey == sessionKey }
    is TaskPreviewState.Error -> takeIf { this.sessionKey == sessionKey }
}
