package com.phonecontrol.assistant.ui.chat.timeline

import android.view.Surface as AndroidSurface
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.chat.continuationSessionIdOrNullForUi
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.displays.surface.PreviewSurfaceDestroyed
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect

@Composable
internal fun ConversationTimeline(
    timeline: List<TimelineItem>,
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
    expandedPreviewSessionKey: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 12.dp),
) {
    val listState = rememberLazyListState()
    var timelineBounds by remember { mutableStateOf<Rect?>(null) }
    var expandButtonBounds by remember { mutableStateOf<Rect?>(null) }
    val continuationRunId = state.continuationSessionIdOrNullForUi()
    val groups = remember(timeline, continuationRunId) {
        groupTimeline(timeline, continuationRunId)
    }

    // Keep following the live answer until the user starts a real list drag.
    // Content growth can temporarily make the list report that it is no longer
    // at the end, so only a user drag disables following; reaching the end
    // enables it again.
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && listState.canScrollBackward) {
                followLatest = false
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScrollForward ->
            if (!canScrollForward) followLatest = true
        }
    }
    // The inline preview is a live child of the task group. Its decoder and
    // tool rows can change height while the user is reading older messages;
    // do not reposition the list around that child as it updates.
    // A stopped/completed run can still own a retained display. Keep the
    // preview attached to its historical task group while the display is
    // retained; stopping the run only removes action authority.
    val previewBelongsToCurrentTimeline = state.sessionIdOrNull != null &&
            previewState?.let { preview ->
                preview.belongsToRun(state.sessionIdOrNull) &&
                        groups.any { group ->
                            group.runIds.any { runId -> preview.belongsToGroup(runId) }
                        }
            } == true
    val previewExpandedInViewer = previewBelongsToCurrentTimeline &&
            previewState?.isExpanded(expandedPreviewSessionKey) == true
    val inlinePreviewVisible = previewBelongsToCurrentTimeline && !previewExpandedInViewer
    LaunchedEffect(
        groups.lastOrNull()?.id,
        groups.lastOrNull()?.activities?.size,
        groups.lastOrNull()?.steerMessages?.size,
        groups.lastOrNull()?.assistantMessages?.lastOrNull()?.text?.length,
        currentToolCall?.id,
        currentToolCall?.status,
        inlinePreviewVisible,
        previewExpandedInViewer,
    ) {
        if (
            groups.isNotEmpty() &&
            followLatest &&
            !listState.isScrollInProgress &&
            !inlinePreviewVisible &&
            !previewExpandedInViewer
        ) {
            // A very large offset positions the last item at the bottom of
            // the viewport instead of repeatedly snapping to its start.
            listState.scrollToItem(groups.lastIndex, scrollOffset = Int.MAX_VALUE)
        }
    }
    val expandSessionKey = previewState?.sessionKey ?: state.sessionIdOrNull
    Box(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            timelineBounds = coordinates.boundsInRoot()
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            // The preview has interactive Compose children. Do not keep an
            // edge overscroll gesture active over them at the list boundary.
            overscrollEffect = null,
        ) {
            items(groups, key = { it.id }) { group ->
                TaskGroupCard(
                    group = group,
                    state = state,
                    currentToolCall = currentToolCall,
                    developerStatus = developerStatus,
                    phoneRecoveryShownAtTop = phoneRecoveryShownAtTop,
                    companionRecoveryShownAtTop = companionRecoveryShownAtTop,
                    companionConnected = companionConnected,
                    onOpenPhoneAccess = onOpenPhoneAccess,
                    onOpenCompanion = onOpenCompanion,
                    onStopSession = onStopSession,
                    onAcknowledgeAttention = onAcknowledgeAttention,
                    previewState = previewState,
                    onPreviewSurfaceAvailable = onPreviewSurfaceAvailable,
                    onPreviewSurfaceDestroyed = onPreviewSurfaceDestroyed,
                    onOpenPreview = onOpenPreview,
                    onPreviewExpandBoundsChanged = { bounds ->
                        expandButtonBounds = bounds
                    },
                    expandedPreviewSessionKey = expandedPreviewSessionKey,
                    active = state.isActive &&
                            state.sessionIdOrNull?.let(group.runIds::contains) == true,
                )
            }
        }

        // Keep the visible affordance in the item for stable semantics and
        // rendering, but route physical taps through a sibling of LazyColumn.
        // LazyColumn's drag/selection/edge gesture chain can otherwise retain
        // the pointer stream after the list reaches its final offset.
        val rootBounds = timelineBounds
        val buttonBounds = expandButtonBounds
        if (
            rootBounds != null &&
            buttonBounds != null &&
            buttonBounds.left >= rootBounds.left &&
            buttonBounds.top >= rootBounds.top &&
            buttonBounds.right <= rootBounds.right &&
            buttonBounds.bottom <= rootBounds.bottom &&
            expandSessionKey != null
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (buttonBounds.left - rootBounds.left).roundToInt(),
                            y = (buttonBounds.top - rootBounds.top).roundToInt(),
                        )
                    }
                    .size(48.dp)
                    .zIndex(3f)
                    .pointerInput(expandSessionKey) {
                        detectTapGestures {
                            android.util.Log.d("DhdPreview", "Expand requested")
                            onOpenPreview(expandSessionKey)
                        }
                    },
            )
        }
    }
}

private fun LiveDisplayPreviewState.belongsToRun(runSessionKey: String?): Boolean =
    runSessionKey != null && (sessionKey == runSessionKey || this.runSessionKey == runSessionKey)

/**
 * Conversation cards are keyed by coordinator run, not by the retained
 * native display owner. A later run can claim the same display, so accepting
 * both identities here would mount one live preview in both the old and new
 * task cards. Keep the display-key fallback for callers that do not have a
 * run binding yet.
 */
internal fun LiveDisplayPreviewState.belongsToGroup(groupId: String): Boolean =
    if (runSessionKey != null) {
        runSessionKey == groupId
    } else {
        sessionKey == groupId
    }

internal fun LiveDisplayPreviewState.isExpanded(expandedSessionKey: String?): Boolean =
    expandedSessionKey != null &&
            (sessionKey == expandedSessionKey || runSessionKey == expandedSessionKey)
