package com.phonecontrol.assistant.ui.chat.timeline

import android.view.Surface as AndroidSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.chat.status.PausedStatusIndicator
import com.phonecontrol.assistant.ui.chat.status.RunningStatusIndicator
import com.phonecontrol.assistant.ui.chat.trace.AnimatedCollapsedTrace
import com.phonecontrol.assistant.ui.chat.trace.WorkedTraceSection
import com.phonecontrol.assistant.ui.chat.trace.capActivityTrace
import com.phonecontrol.assistant.ui.chat.trace.isInFlight
import com.phonecontrol.assistant.ui.chat.trace.matchesLiveTool
import com.phonecontrol.assistant.ui.chat.workedDurationMsOrNullForUi
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreview
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewPlaceholder
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.displays.surface.PreviewSurfaceDestroyed
import com.phonecontrol.assistant.ui.recovery.AttentionRecoveryCard

@Composable
internal fun TaskGroupCard(
    group: TaskGroup,
    state: SessionState,
    currentToolCall: DhdToolCall? = null,
    developerStatus: DeveloperModeStatus,
    phoneRecoveryShownAtTop: Boolean,
    companionRecoveryShownAtTop: Boolean,
    companionConnected: Boolean,
    onOpenPhoneAccess: () -> Unit,
    onOpenCompanion: () -> Unit,
    onStopSession: () -> Unit,
    onAcknowledgeAttention: () -> Boolean,
    previewState: LiveDisplayPreviewState? = null,
    onPreviewSurfaceAvailable: (AndroidSurface) -> Unit = {},
    onPreviewSurfaceDestroyed: PreviewSurfaceDestroyed = { _, release -> release() },
    onOpenPreview: (String) -> Unit = {},
    onPreviewExpandBoundsChanged: (Rect?) -> Unit = {},
    expandedPreviewSessionKey: String? = null,
    active: Boolean,
) {
    var traceExpanded by rememberSaveable(group.id) { mutableStateOf(false) }
    var earlierActionsExpanded by rememberSaveable(group.id) { mutableStateOf(false) }

    val terminalDurationMs = state.workedDurationMsOrNullForUi()
        ?.takeIf { state.sessionIdOrNull?.let(group.runIds::contains) == true }
    val durationSeconds = remember(group, terminalDurationMs) {
        terminalDurationMs?.let { maxOf(1L, it / 1_000L) } ?: run {
            val start = group.userMessage?.timestampEpochMs ?: group.timestampEpochMs
            val end = group.assistantMessages.lastOrNull()?.timestampEpochMs
                ?: group.activities.lastOrNull()?.timestampEpochMs
                ?: start
            maxOf(1L, (end - start) / 1000L)
        }
    }
    val liveToolCall = currentToolCall?.takeIf { toolCall ->
        active && toolCall.sessionId in group.runIds
    }
    val liveActivity = liveToolCall?.let { toolCall ->
        group.activities.asReversed().firstOrNull { it.matchesLiveTool(toolCall) }
    }
    val fallbackLiveActivity = if (liveToolCall == null && active) {
        group.activities.asReversed().firstOrNull { it.isInFlight() }
    } else {
        null
    }
    val trace = capActivityTrace(
        activities = group.activities,
        active = active,
        currentActivityId = liveActivity?.id ?: fallbackLiveActivity?.id,
        hasCurrentTool = liveToolCall != null,
    )
    val visibleTraceIds = trace.visibleActivities.map { it.id }.toSet()
    val earlierTraceActivities = group.activities.filterNot { it.id in visibleTraceIds }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // User message bubble (ChatGPT navy bubble in dark, soft gray bubble in light)
        group.userMessage?.let { message ->
            SelectionContainer {
                MessageBubble(message)
            }
        }

        // Steering instructions stay attached to the current run instead of
        // appearing as a new task.
        group.steerMessages.forEach { message ->
            SelectionContainer {
                SteerMessageBubble(message)
            }
        }

        val taskPreviewState = previewState?.let { preview ->
            if (preview.sessionKey == null) {
                preview.copy(sessionKey = state.sessionIdOrNull)
            } else {
                preview
            }
        }
        val previewVisibleForGroup = taskPreviewState?.let { preview ->
            group.runIds.any { runId -> preview.belongsToGroup(runId) } &&
                    !preview.isExpanded(expandedPreviewSessionKey)
        } == true
        val previewExpandedForGroup = taskPreviewState?.let { preview ->
            group.runIds.any { runId -> preview.belongsToGroup(runId) } &&
                    preview.isExpanded(expandedPreviewSessionKey)
        } == true
        if (previewVisibleForGroup) {
            // Keep the preview outside any SelectionContainer. Its native
            // TextureView and expand control must not share a selection
            // gesture surface with the surrounding timeline text.
            LiveDisplayPreview(
                state = taskPreviewState,
                onSurfaceAvailable = onPreviewSurfaceAvailable,
                onSurfaceDestroyed = onPreviewSurfaceDestroyed,
                onExpand = { taskPreviewState.sessionKey?.let(onOpenPreview) },
                onExpandBoundsChanged = onPreviewExpandBoundsChanged,
            )
        } else if (previewExpandedForGroup) {
            // Fullscreen owns the decoder surface. Keep this equal-sized slot
            // in the timeline so opening/closing the viewer cannot change the
            // LazyColumn's measured content or clamp its scroll offset.
            LiveDisplayPreviewPlaceholder(state = taskPreviewState)
        }

        // Keep the playful status for healthy work. Phone-access recovery is
        // rendered above the conversation so it does not jump underneath the
        // user's newly submitted message.
        if (active) {
            when (state) {
                is SessionState.Running -> {
                    RunningStatusIndicator(
                        currentPurpose = state.currentPurpose,
                        attentionReason = state.attentionReason,
                        attentionActionLabel = state.attentionActionLabel,
                        startedAtEpochMs = state.startedAtEpochMs,
                        elapsedBeforeStartMs = state.elapsedBeforeStartMs,
                        phoneAccessTitle = developerStatus.recoveryTitle,
                        phoneAccessDetail = developerStatus.recoveryDetail,
                        companionConnected = companionConnected,
                        phoneAccessRecoveryShownAtTop = phoneRecoveryShownAtTop,
                        companionRecoveryShownAtTop = companionRecoveryShownAtTop,
                        onOpenPhoneAccess = onOpenPhoneAccess,
                        onOpenCompanion = onOpenCompanion,
                        onStopSession = onStopSession,
                        onAcknowledgeAttention = onAcknowledgeAttention,
                    )
                }

                is SessionState.Paused -> if (
                    state.attentionReason != null &&
                    !(phoneRecoveryShownAtTop &&
                            state.attentionActionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true))
                ) {
                    AttentionRecoveryCard(
                        reason = state.attentionReason,
                        actionLabel = state.attentionActionLabel,
                        phoneAccessTitle = developerStatus.recoveryTitle,
                        phoneAccessDetail = developerStatus.recoveryDetail,
                        onAcknowledgeAttention = onAcknowledgeAttention,
                        onOpenPhoneAccess = onOpenPhoneAccess,
                        onStopSession = onStopSession,
                    )
                } else {
                    PausedStatusIndicator(
                        currentPurpose = state.currentPurpose,
                    )
                }

                else -> Unit
            }
        }

        // While active: show the current tool and the latest four completed
        // steps directly under the thinking indicator. Older work is summarized
        // so a long-running task cannot push the conversation downward forever.
        if (active && (trace.visibleActivities.isNotEmpty() || trace.hasSyntheticCurrent)) {
            val syntheticCurrent = liveToolCall?.takeIf { trace.hasSyntheticCurrent }
            AnimatedCollapsedTrace(
                activities = trace.visibleActivities,
                earlierActivities = earlierTraceActivities,
                earlierCount = trace.earlierCount,
                earlierExpanded = earlierActionsExpanded,
                currentActivityId = trace.currentActivityId,
                syntheticCurrent = syntheticCurrent,
                onToggleEarlier = { earlierActionsExpanded = !earlierActionsExpanded },
            )
        }

        // Completed runs with phone actions keep a collapsible trace. Direct
        // responses should flow directly from the user message to the answer.
        if (!active && group.activities.isNotEmpty()) {
            WorkedTraceSection(
                durationSeconds = durationSeconds,
                activities = group.activities,
                expanded = traceExpanded,
                onToggleExpand = { traceExpanded = !traceExpanded },
            )
        }

        // Assistant response message(s)
        group.assistantMessages.forEach { message ->
            SelectionContainer {
                MessageBubble(message)
            }
        }
    }
}
