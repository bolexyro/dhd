package com.phonecontrol.assistant.ui.displays

import android.view.Surface as AndroidSurface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.ui.displays.surface.PreviewLifecycleBinding
import com.phonecontrol.assistant.ui.displays.surface.PreviewSurfaceDestroyed
import com.phonecontrol.assistant.ui.displays.surface.ReadOnlyPreviewTextureView
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.toolActivityColor

private const val FULLSCREEN_DISPLAY_SCALE = 0.90f

private val FULLSCREEN_PURPOSE_SLOT_HEIGHT = 48.dp

/**
 * Full-screen read-only viewer for one task display.
 *
 * Only this composable owns the expanded TextureView. Callers should remove
 * the inline preview while [visible] so the same decoder surface is not
 * attached twice. The surface remains deliberately non-clickable.
 */
@Composable
fun FullScreenLiveDisplayViewer(
    record: TaskDisplayUiRecord,
    state: LiveDisplayPreviewState,
    onDismiss: () -> Unit,
    onSurfaceAvailable: (AndroidSurface) -> Unit,
    onSurfaceDestroyed: PreviewSurfaceDestroyed,
    onRetry: () -> Unit = {},
    onAcknowledgeAttention: () -> Boolean = { false },
    onStopSession: () -> Unit = {},
    onEndTaskDisplay: (TaskDisplayUiRecord) -> Unit = {},
) {
    val colors = LocalAssistantColors.current
    val viewerBackground = if (colors.isDark) colors.surfaceCard else colors.background
    val title = record.appLabel
        ?: state.appLabel
        ?: "Task display"
    val purpose = record.currentPurpose
        ?: state.purpose
        ?: state.status.displayLabel()
    val isCompleted = record.lifecycle == TaskDisplayLifecycle.COMPLETED
    val showAttentionActions = record.lifecycle == TaskDisplayLifecycle.PAUSED &&
        (record.currentPurpose.isAttentionPurpose() || state.purpose.isAttentionPurpose())
    val purposeIconColor = when {
        showAttentionActions -> colors.warningAmber
        record.lifecycle == TaskDisplayLifecycle.FAILED -> colors.errorRed
        else -> toolActivityColor(
            toolName = state.currentToolName ?: record.currentToolName,
            colors = colors,
            fallback = colors.accentBlue,
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        MaterialSurface(
            modifier = Modifier.fillMaxSize(),
            color = viewerBackground,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(viewerBackground)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MaterialSurface(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onDismiss),
                        shape = RoundedCornerShape(999.dp),
                        color = colors.composerBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderColor),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Back",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp),
                    ) {
                        Text(
                            text = title,
                            color = colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(viewerBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    val aspectRatio = state.aspectRatio.takeIf { it.isFinite() && it > 0f }
                        ?: DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO
                    val maxHeight = maxHeight.takeIf { it.value.isFinite() }
                        ?: (maxWidth / aspectRatio)
                    val surfaceWidth = minOf(maxWidth, maxHeight * aspectRatio) * FULLSCREEN_DISPLAY_SCALE
                    val surfaceHeight = surfaceWidth / aspectRatio
                    FullScreenPreviewSurface(
                        state = state,
                        width = surfaceWidth,
                        height = surfaceHeight,
                        onSurfaceAvailable = onSurfaceAvailable,
                        onSurfaceDestroyed = onSurfaceDestroyed,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(viewerBackground)
                        .navigationBarsPadding(),
                ) {
                    Column(
                        modifier = Modifier.padding(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 20.dp),
                    ) {
                        // Keep a fixed purpose slot so a one-line to two-line
                        // update changes only the text, never the available
                        // height of the phone surface above it.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FULLSCREEN_PURPOSE_SLOT_HEIGHT),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!isCompleted) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_connected_nodes),
                                        contentDescription = "MCP activity",
                                        tint = purposeIconColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    AnimatedPurposeText(
                                        text = purpose.withTrailingEllipsis(),
                                        maxLines = 2,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                        if (showAttentionActions) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(
                                    12.dp,
                                    Alignment.CenterHorizontally,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        onAcknowledgeAttention()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.warningAmber,
                                        contentColor = Color.White,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Done", fontSize = 13.sp)
                                }
                                OutlinedButton(
                                    onClick = onStopSession,
                                    border = BorderStroke(1.dp, colors.borderColor),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Stop", color = colors.textSecondary, fontSize = 13.sp)
                                }
                            }
                        }
                        if (state.status == LiveDisplayPreviewStatus.ERROR ||
                            record.lifecycle == TaskDisplayLifecycle.UNAVAILABLE
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(
                                    10.dp,
                                    Alignment.CenterHorizontally,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.status == LiveDisplayPreviewStatus.ERROR) {
                                    Button(
                                        onClick = onRetry,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.accentBlue,
                                            contentColor = Color.White,
                                        ),
                                    ) {
                                        Text("Retry preview")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onEndTaskDisplay(record) },
                                    border = BorderStroke(1.dp, colors.errorRed),
                                ) {
                                    Text("End display", color = colors.errorRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Renders the safe, sanitized purpose with the same moving shimmer used by the task status. */
@Composable
private fun AnimatedPurposeText(
    text: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val transition = rememberInfiniteTransition(label = "task_display_purpose_shimmer")
    val shimmerTranslate by transition.animateFloat(
        initialValue = -180f,
        targetValue = 480f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "task_display_purpose_translate",
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            colors.accentBlue.copy(alpha = 0.35f),
            colors.accentBlue,
            colors.textPrimary,
            colors.accentBlue,
            colors.accentBlue.copy(alpha = 0.35f),
        ),
        start = Offset(shimmerTranslate, 0f),
        end = Offset(shimmerTranslate + 180f, 0f),
    )
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            brush = shimmerBrush,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = maxLines,
    )
}

private fun String.withTrailingEllipsis(): String {
    val trimmed = trimEnd()
    return when {
        trimmed.endsWith("...") || trimmed.endsWith("…") -> trimmed
        else -> "$trimmed..."
    }
}

private fun String?.isAttentionPurpose(): Boolean =
    this?.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true) == true

@Composable
private fun FullScreenPreviewSurface(
    state: LiveDisplayPreviewState,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onSurfaceAvailable: (AndroidSurface) -> Unit,
    onSurfaceDestroyed: PreviewSurfaceDestroyed,
) {
    val latestOnSurfaceAvailable = rememberUpdatedState(onSurfaceAvailable)
    val latestOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)
    var textureView by remember { mutableStateOf<ReadOnlyPreviewTextureView?>(null) }
    val phoneShape = liveDisplayCornerShape()
    val colors = LocalAssistantColors.current

    val previewLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(textureView, state.sessionKey, previewLifecycle) {
        val view = textureView
        if (view == null) {
            onDispose { }
        } else {
            val binding = PreviewLifecycleBinding(
                lifecycle = previewLifecycle,
                attach = {
                    view.setSurfaceCallbacks(
                        onAvailable = { surface -> latestOnSurfaceAvailable.value(surface) },
                        onDestroyed = { surface, release -> latestOnSurfaceDestroyed.value(surface, release) },
                    )
                },
                detach = view::clearSurfaceCallbacks,
            )
            onDispose { binding.close() }
        }
    }

    MaterialSurface(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(phoneShape),
        shape = phoneShape,
        color = colors.surfaceCard,
        shadowElevation = LIVE_DISPLAY_SHADOW_ELEVATION,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    ReadOnlyPreviewTextureView(context).also { textureView = it }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view -> view.contentDescription = "Full-screen live app preview" },
            )
            if (state.status == LiveDisplayPreviewStatus.LIVE) {
                TaskPointerOverlay(event = state.pointerEvent)
            }
            if (state.status != LiveDisplayPreviewStatus.LIVE) {
                PreviewStatusOverlay(state)
            }
        }
    }
}
