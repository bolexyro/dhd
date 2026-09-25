package com.phonecontrol.assistant.overlay.cards

import android.view.Surface as AndroidSurface
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.display.TaskPreviewState
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.overlay.sessionOrNull
import com.phonecontrol.assistant.ui.components.GlassSurface
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreview
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.displays.liveDisplayCornerShape
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
internal fun FloatingVirtualDisplayCard(
    previewState: TaskPreviewState,
    taskDisplaySession: TaskDisplaySession?,
    pointerEvent: TaskPointerEvent?,
    onHide: () -> Unit,
    onContinue: () -> Unit,
    onSurfaceAvailable: (TaskDisplaySession, AndroidSurface) -> Unit,
    onSurfaceDestroyed: (TaskDisplaySession, AndroidSurface, () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val cardShape = liveDisplayCornerShape()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        ClosePillButton(
            onClick = onHide,
            modifier = Modifier.padding(bottom = 4.dp, end = 6.dp),
            label = stringResource(R.string.common_close),
        )

        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                OverlayVirtualDisplayPreview(
                    previewState = previewState,
                    taskDisplaySession = taskDisplaySession,
                    pointerEvent = pointerEvent,
                    onHide = onHide,
                    onContinue = onContinue,
                    onSurfaceAvailable = onSurfaceAvailable,
                    onSurfaceDestroyed = onSurfaceDestroyed,
                )
            }
        }
    }
}

@Composable
private fun OverlayVirtualDisplayPreview(
    previewState: TaskPreviewState,
    taskDisplaySession: TaskDisplaySession?,
    pointerEvent: TaskPointerEvent?,
    onHide: () -> Unit,
    onContinue: () -> Unit,
    onSurfaceAvailable: (TaskDisplaySession, AndroidSurface) -> Unit,
    onSurfaceDestroyed: (TaskDisplaySession, AndroidSurface, () -> Unit) -> Unit,
) {
    val colors = LocalAssistantColors.current
    // The backend keeps the display session alive while its decoder Surface is
    // detached during an Activity/overlay handoff. Use that session as the
    // identity for composition; previewState only describes playback. If the
    // state is Detached, composing a connecting preview is what creates the
    // replacement TextureView and lets the surface callback reattach it.
    val session = taskDisplaySession ?: previewState.sessionOrNull()
    val livePreview = session?.let { displaySession ->
        when (previewState) {
            is TaskPreviewState.Error,
            is TaskPreviewState.Ended,
            -> null
            is TaskPreviewState.Attached -> LiveDisplayPreviewState.live(
                appLabel = displaySession.packageName.takeIf(String::isNotBlank),
                aspectRatio = displaySession.geometry.width.toFloat() /
                    displaySession.geometry.height.toFloat(),
                sessionKey = displaySession.sessionKey,
            )
            is TaskPreviewState.Connecting,
            TaskPreviewState.Detached,
            -> LiveDisplayPreviewState.connecting(
                appLabel = displaySession.packageName.takeIf(String::isNotBlank),
                aspectRatio = displaySession.geometry.width.toFloat() /
                    displaySession.geometry.height.toFloat(),
                sessionKey = displaySession.sessionKey,
            )
        }?.let { preview ->
            displaySession to preview.copy(pointerEvent = pointerEvent)
        }
    }
    val statusMessage = when (previewState) {
        TaskPreviewState.Detached -> "The virtual display will appear when a task opens one."
        is TaskPreviewState.Ended -> previewState.message
        is TaskPreviewState.Error -> previewState.message
        else -> null
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.common_live_view),
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (livePreview != null) {
            val (displaySession, live) = livePreview
            LiveDisplayPreview(
                state = live,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 248.dp),
                onSurfaceAvailable = { surface -> onSurfaceAvailable(displaySession, surface) },
                onSurfaceDestroyed = { surface, release ->
                    onSurfaceDestroyed(displaySession, surface, release)
                },
                onExpand = onContinue,
                showCardChrome = true,
            )
        } else {
            Text(
                text = statusMessage ?: "No virtual display is attached yet.",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}
