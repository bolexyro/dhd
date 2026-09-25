package com.phonecontrol.assistant.ui.displays

import androidx.compose.foundation.layout.height
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.execution.TaskDisplayGeometry

/**
 * The state rendered by [LiveDisplayPreview].
 *
 * [aspectRatio] describes the preview card only. It is deliberately not sent
 * to the display controller: the controller owns a fixed virtual-display
 * buffer geometry and must not resize it in response to Compose layout.
 */
data class LiveDisplayPreviewState(
    val status: LiveDisplayPreviewStatus,
    val appLabel: String? = null,
    val message: String? = null,
    val aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
    /** Identifies the task/display session using this surface. */
    val sessionKey: String? = null,
    /**
     * Identifies the current coordinator run that is presenting this display.
     *
     * A retained display keeps its native owner key when a later run selects
     * it with displayRef. The owner key remains the surface/manager identity,
     * while this optional key lets the conversation attach the preview to the
     * current run's message group.
     */
    val runSessionKey: String? = null,
    /** Latest pointer feedback to render above the read-only stream. */
    val pointerEvent: TaskPointerEvent? = null,
    /** Sanitized purpose shown in the full-screen viewer footer. */
    val purpose: String? = null,
    /** The latest tool associated with [purpose], used for its activity tint. */
    val currentToolName: String? = null,
) {
    companion object {
        fun unavailable(
            message: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
            currentToolName: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.UNAVAILABLE,
                message = message,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
                currentToolName = currentToolName,
            )

        fun connecting(
            appLabel: String? = null,
            message: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
            currentToolName: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.CONNECTING,
                appLabel = appLabel,
                message = message,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
                currentToolName = currentToolName,
            )

        fun live(
            appLabel: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
            currentToolName: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.LIVE,
                appLabel = appLabel,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
                currentToolName = currentToolName,
            )

        fun error(
            message: String,
            appLabel: String? = null,
            aspectRatio: Float = DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO,
            sessionKey: String? = null,
            currentToolName: String? = null,
        ): LiveDisplayPreviewState =
            LiveDisplayPreviewState(
                status = LiveDisplayPreviewStatus.ERROR,
                appLabel = appLabel,
                message = message,
                aspectRatio = aspectRatio,
                sessionKey = sessionKey,
                currentToolName = currentToolName,
            )
    }
}

/** Lifecycle values used by the display manager UI. */
enum class TaskDisplayLifecycle {
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    STOPPED,
    UNAVAILABLE,
    ENDED,
    EXPIRED,
}

/**
 * UI-facing display record. The backend is free to maintain a richer
 * persisted record; MainActivity maps that record into this small immutable
 * view model so the UI does not depend on native display implementation
 * details.
 */
data class TaskDisplayUiRecord(
    val sessionKey: String,
    val taskId: String = sessionKey,
    val packageName: String? = null,
    val appLabel: String? = null,
    val displayId: Int? = null,
    val displayRef: String? = null,
    val geometry: TaskDisplayGeometry? = null,
    val lifecycle: TaskDisplayLifecycle = TaskDisplayLifecycle.RUNNING,
    val currentPurpose: String? = null,
    val createdAtEpochMs: Long = 0L,
    val terminalAtEpochMs: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val error: String? = null,
    val previewState: LiveDisplayPreviewState? = null,
    /** Latest tool associated with [currentPurpose], when available in memory. */
    val currentToolName: String? = null,
)

enum class LiveDisplayPreviewStatus {
    UNAVAILABLE,
    CONNECTING,
    LIVE,
    ERROR,
}

/** Width / height for the preview card, independent of the display buffer. */
const val DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO = 9f / 16f

internal fun TaskDisplayLifecycle.displayLabel(): String = when (this) {
    TaskDisplayLifecycle.RUNNING -> "Running"
    TaskDisplayLifecycle.PAUSED -> "Waiting"
    TaskDisplayLifecycle.COMPLETED -> "Completed"
    TaskDisplayLifecycle.FAILED -> "Failed"
    TaskDisplayLifecycle.STOPPED -> "Stopped"
    TaskDisplayLifecycle.UNAVAILABLE -> "Unavailable"
    TaskDisplayLifecycle.ENDED -> "Ended"
    TaskDisplayLifecycle.EXPIRED -> "Expired"
}

internal fun LiveDisplayPreviewStatus.displayLabel(): String = when (this) {
    LiveDisplayPreviewStatus.UNAVAILABLE -> "Preview unavailable"
    LiveDisplayPreviewStatus.CONNECTING -> "Connecting to preview"
    LiveDisplayPreviewStatus.LIVE -> "Streaming app"
    LiveDisplayPreviewStatus.ERROR -> "Preview error"
}
