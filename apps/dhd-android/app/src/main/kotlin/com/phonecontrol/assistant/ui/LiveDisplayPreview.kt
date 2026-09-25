package com.phonecontrol.assistant.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface as AndroidSurface
import android.view.TextureView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.execution.TaskDisplayGeometry
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.toolActivityColor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Surface destruction is asynchronous at the preview boundary. The owner
 * releases the TextureView surface only after the decoder has stopped using it.
 * The gate is idempotent because a TextureView can report destruction both
 * while its callbacks are being cleared and from its listener afterwards.
 */
internal class PreviewSurfaceLease(
    private val releaseAction: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

internal typealias PreviewSurfaceDestroyed = (AndroidSurface, () -> Unit) -> Unit

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

private const val FULLSCREEN_DISPLAY_SCALE = 0.90f
internal const val LIVE_DISPLAY_CORNER_RADIUS_DP = 12
private val LIVE_DISPLAY_SHADOW_ELEVATION = 2.dp
private val FULLSCREEN_PURPOSE_SLOT_HEIGHT = 48.dp

internal fun liveDisplayCornerShape() = RoundedCornerShape(LIVE_DISPLAY_CORNER_RADIUS_DP.dp)

/**
 * Renders the agent's virtual display continuously into a read-only surface.
 *
 * The callbacks are the only bridge to the display implementation. A local
 * virtual display can render directly into [AndroidSurface], while a decoder-backed
 * implementation can use the same surface as its output target. The preview
 * consumes all touch events and never forwards input to the rendered app.
 * Surface dimensions are intentionally not exposed as a resize request.
 */
@Composable
fun LiveDisplayPreview(
    state: LiveDisplayPreviewState,
    modifier: Modifier = Modifier,
    onSurfaceAvailable: (AndroidSurface) -> Unit,
    onSurfaceDestroyed: PreviewSurfaceDestroyed,
    onExpand: () -> Unit = {},
    onExpandBoundsChanged: (Rect?) -> Unit = {},
    showCardChrome: Boolean = true,
) {
    val latestOnSurfaceAvailable = rememberUpdatedState(onSurfaceAvailable)
    val latestOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)
    val latestOnExpandBoundsChanged = rememberUpdatedState(onExpandBoundsChanged)
    var textureView by remember { mutableStateOf<ReadOnlyPreviewTextureView?>(null) }
    val colors = LocalAssistantColors.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    DisposableEffect(state.sessionKey) {
        onDispose { latestOnExpandBoundsChanged.value(null) }
    }

    val previewLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(textureView, state.sessionKey, previewLifecycle) {
        val view = textureView
        if (view == null) {
            onDispose { }
        } else {
            // TextureView keeps decoder output inside the normal view
            // hierarchy. SurfaceView uses a separate window, which can go
            // blank while this preview is moved by the conversation's
            // LazyColumn during a scroll.
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

    val previewAspectRatio = state.aspectRatio.takeIf { it.isFinite() && it > 0f }
        ?: DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO
    val shape = liveDisplayCornerShape()
    val previewContainerModifier = if (showCardChrome) {
        Modifier
            .background(colors.surfaceCard, shape)
            .clip(shape)
    } else {
        Modifier
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 360.dp)
            .then(previewContainerModifier)
            .semantics { contentDescription = "Live app preview" }
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                val insetPx = with(density) { 6.dp.toPx() }
                val buttonSizePx = with(density) { 48.dp.toPx() }
                latestOnExpandBoundsChanged.value(
                    Rect(
                        left = bounds.right - insetPx - buttonSizePx,
                        top = bounds.top + insetPx,
                        right = bounds.right - insetPx,
                        bottom = bounds.top + insetPx + buttonSizePx,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val boundedMaxHeight = maxHeight.takeIf { it.value.isFinite() }
            ?: (maxWidth / previewAspectRatio)
        val previewWidth = minOf(maxWidth, boundedMaxHeight * previewAspectRatio)
        val previewHeight = previewWidth / previewAspectRatio

        MaterialSurface(
            modifier = Modifier
                .width(previewWidth)
                .height(previewHeight)
                .clip(shape),
            shape = shape,
            color = if (showCardChrome) colors.surfaceCard else Color.Transparent,
            shadowElevation = if (showCardChrome) LIVE_DISPLAY_SHADOW_ELEVATION else 0.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        ReadOnlyPreviewTextureView(context).also { view ->
                            textureView = view
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        // The preview is not an input surface for the agent. It
                        // consumes taps so a preview cannot accidentally become a
                        // second control path.
                        .then(
                            if (showCardChrome) {
                                Modifier.background(colors.surfaceCard)
                            } else {
                                Modifier
                            },
                        ),
                    update = { view ->
                        view.contentDescription = "Live app preview"
                    },
                )

                if (state.status != LiveDisplayPreviewStatus.LIVE) {
                    PreviewStatusOverlay(state = state)
                }

                if (state.status == LiveDisplayPreviewStatus.LIVE) {
                    TaskPointerOverlay(event = state.pointerEvent)
                }
            }
        }

        // Keep the expand affordance in the letterbox outside the phone surface
        // so it never obscures the app or looks like an app control.
        MaterialSurface(
            onClick = {
                android.util.Log.d("DhdPreview", "Expand requested")
                onExpand()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(48.dp)
                .zIndex(2f)
                .clip(RoundedCornerShape(999.dp))
                .semantics {
                    contentDescription = "Open full-screen viewer"
                },
            shape = RoundedCornerShape(999.dp),
            color = colors.composerBackground.copy(alpha = 0.92f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_fullscreen),
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

/**
 * Reserves the inline preview's measured space while its surface is owned by
 * [FullScreenLiveDisplayViewer]. The placeholder deliberately has no
 * AndroidView, so the same decoder surface is never attached twice.
 */
@Composable
internal fun LiveDisplayPreviewPlaceholder(
    state: LiveDisplayPreviewState,
    modifier: Modifier = Modifier,
    showCardChrome: Boolean = true,
) {
    val colors = LocalAssistantColors.current
    val previewAspectRatio = state.aspectRatio.takeIf { it.isFinite() && it > 0f }
        ?: DEFAULT_LIVE_DISPLAY_PREVIEW_ASPECT_RATIO
    val shape = liveDisplayCornerShape()
    val previewContainerModifier = if (showCardChrome) {
        Modifier
            .background(colors.surfaceCard, shape)
            .clip(shape)
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 360.dp)
            .then(previewContainerModifier),
        contentAlignment = Alignment.Center,
    ) {
        val boundedMaxHeight = maxHeight.takeIf { it.value.isFinite() }
            ?: (maxWidth / previewAspectRatio)
        val previewWidth = minOf(maxWidth, boundedMaxHeight * previewAspectRatio)
        val previewHeight = previewWidth / previewAspectRatio

        MaterialSurface(
            modifier = Modifier
                .width(previewWidth)
                .height(previewHeight)
                .clip(shape),
            shape = shape,
            color = if (showCardChrome) colors.surfaceCard else Color.Transparent,
            shadowElevation = if (showCardChrome) LIVE_DISPLAY_SHADOW_ELEVATION else 0.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

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

@Composable
private fun PreviewStatusOverlay(state: LiveDisplayPreviewState) {
    val title: String
    val detail: String
    when (state.status) {
        LiveDisplayPreviewStatus.UNAVAILABLE -> {
            title = "Live preview unavailable"
            detail = state.message ?: "The agent has no active app display."
        }
        LiveDisplayPreviewStatus.CONNECTING -> {
            title = "Connecting to live preview"
            detail = state.message ?: "The app will appear here when the display is ready."
        }
        LiveDisplayPreviewStatus.ERROR -> {
            title = "Live preview unavailable"
            detail = state.message ?: "The app display could not be attached."
        }
        LiveDisplayPreviewStatus.LIVE -> return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                modifier = Modifier.padding(top = 6.dp),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
            )
        }
    }
}

/** A scroll-safe decoder target with no control semantics. */
private class ReadOnlyPreviewTextureView(context: Context) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private val surfaceRecordsLock = Any()
    private val pendingSurfaceReleases = java.util.IdentityHashMap<SurfaceTexture, DecoderSurface>()
    private var decoderSurface: DecoderSurface? = null
    private var availableTexture: SurfaceTexture? = null
    private var surfaceCallbackGeneration = 0L
    private var onAvailable: ((AndroidSurface) -> Unit)? = null
    private var onDestroyed: PreviewSurfaceDestroyed? = null

    init {
        surfaceTextureListener = this
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        setOnTouchListener { _, _ -> true }
    }

    fun setSurfaceCallbacks(
        onAvailable: (AndroidSurface) -> Unit,
        onDestroyed: PreviewSurfaceDestroyed,
    ) {
        var immediateSurface: AndroidSurface? = null
        var immediateCallback: ((AndroidSurface) -> Unit)? = null
        var pendingRelease: DecoderSurface? = null
        var pendingTexture: SurfaceTexture? = null
        var releaseImmediately: DecoderSurface? = null
        var generation = 0L
        synchronized(surfaceRecordsLock) {
            generation = ++surfaceCallbackGeneration
            this.onAvailable = onAvailable
            this.onDestroyed = onDestroyed

            val current = decoderSurface
            if (current != null && !current.destroyNotified && current.surface.isValid) {
                immediateSurface = current.surface
                immediateCallback = onAvailable
            } else {
                // A session-key change can dispose and recreate this effect
                // while TextureView keeps the same SurfaceTexture available.
                // Wait for the old asynchronous detach before wrapping that
                // texture again; otherwise its release can invalidate the
                // replacement decoder's target.
                val texture = availableTexture
                if (current != null) {
                    decoderSurface = null
                    if (!current.destroyNotified) {
                        notifySurfaceDestroyedLocked(current)
                        if (this.onDestroyed == null) releaseImmediately = current
                    }
                }
                if (texture != null) {
                    pendingSurfaceReleases[texture]?.let { pending ->
                        pendingRelease = pending
                        pendingTexture = texture
                    } ?: DecoderSurface(texture).also { created ->
                        decoderSurface = created
                        immediateSurface = created.surface
                        immediateCallback = onAvailable
                    }
                }
            }
        }
        releaseImmediately?.release()
        invokeAvailableIfCurrent(immediateSurface, immediateCallback, generation)
        if (pendingRelease != null && pendingTexture != null) {
            val texture = pendingTexture!!
            pendingRelease!!.whenReleased {
                announceAvailableAfterRelease(texture, generation)
            }
        }
    }

    fun clearSurfaceCallbacks() {
        var releaseImmediately = false
        val current: DecoderSurface?
        synchronized(surfaceRecordsLock) {
            ++surfaceCallbackGeneration
            current = decoderSurface?.also {
                decoderSurface = null
                if (!it.destroyNotified) {
                    notifySurfaceDestroyedLocked(it)
                    releaseImmediately = onDestroyed == null
                }
            }
            onAvailable = null
            onDestroyed = null
        }
        // If no callback was registered, the view still owns this record and
        // must release it. Normal callers always release it from their async
        // detach completion.
        if (current != null && releaseImmediately) current.release()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        var immediateSurface: AndroidSurface? = null
        var immediateCallback: ((AndroidSurface) -> Unit)? = null
        var pendingRelease: DecoderSurface? = null
        var releaseImmediately: DecoderSurface? = null
        var generation = 0L
        synchronized(surfaceRecordsLock) {
            generation = surfaceCallbackGeneration
            availableTexture = surface
            val current = decoderSurface
            if (current != null && current.texture === surface &&
                !current.destroyNotified && current.surface.isValid
            ) {
                immediateSurface = current.surface
                immediateCallback = onAvailable
            } else {
                current?.let { previous ->
                    decoderSurface = null
                    if (!previous.destroyNotified) {
                        notifySurfaceDestroyedLocked(previous)
                        if (onDestroyed == null) releaseImmediately = previous
                    }
                }
                pendingSurfaceReleases[surface]?.let { pending ->
                    pendingRelease = pending
                    generation = surfaceCallbackGeneration
                } ?: DecoderSurface(surface).also { created ->
                    decoderSurface = created
                    immediateSurface = created.surface
                    immediateCallback = onAvailable
                }
            }
        }
        releaseImmediately?.release()
        invokeAvailableIfCurrent(immediateSurface, immediateCallback, generation)
        if (pendingRelease != null) {
            pendingRelease!!.whenReleased {
                announceAvailableAfterRelease(surface, generation)
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        var releaseImmediately = false
        val current: DecoderSurface?
        synchronized(surfaceRecordsLock) {
            val record = decoderSurface?.takeIf { it.texture === surface }
                ?: pendingSurfaceReleases[surface]
            if (decoderSurface === record) decoderSurface = null
            if (availableTexture === surface) availableTexture = null
            val wasAlreadyNotified = record?.destroyNotified == true
            if (record != null) {
                record.markTextureDestroyed()
                if (!wasAlreadyNotified) {
                    notifySurfaceDestroyedLocked(record)
                    releaseImmediately = onDestroyed == null
                }
            }
            current = record
        }
        if (current == null) return true
        // A pending record was already handed to the asynchronous detach
        // callback. Clearing callbacks after that handoff must not turn this
        // later TextureView notification into an early release.
        if (releaseImmediately) current.release()
        // Returning false transfers SurfaceTexture ownership to us. The
        // release callback runs after the decoder has detached from this
        // surface, which prevents BufferQueue/MediaCodec races.
        return false
    }

    private fun announceAvailableAfterRelease(
        texture: SurfaceTexture,
        generation: Long,
    ) {
        var surface: AndroidSurface? = null
        var callback: ((AndroidSurface) -> Unit)? = null
        synchronized(surfaceRecordsLock) {
            if (generation != surfaceCallbackGeneration ||
                onAvailable == null ||
                availableTexture !== texture ||
                decoderSurface != null ||
                pendingSurfaceReleases.containsKey(texture)
            ) {
                return@synchronized
            }
            DecoderSurface(texture).also { created ->
                decoderSurface = created
                surface = created.surface
                callback = onAvailable
            }
        }
        invokeAvailableIfCurrent(surface, callback, generation)
    }

    private fun invokeAvailableIfCurrent(
        surface: AndroidSurface?,
        callback: ((AndroidSurface) -> Unit)?,
        generation: Long,
    ) {
        if (surface == null || callback == null) return
        val stillCurrent = synchronized(surfaceRecordsLock) {
            surfaceCallbackGeneration == generation && onAvailable === callback
        }
        if (stillCurrent) callback(surface)
    }

    private fun notifySurfaceDestroyedLocked(record: DecoderSurface) {
        if (!record.markDestroyed()) return
        pendingSurfaceReleases[record.texture] = record
        onDestroyed?.invoke(record.surface, record::release)
    }

    private inner class DecoderSurface(
        val texture: SurfaceTexture,
    ) {
        val surface = AndroidSurface(texture)
        private val released = AtomicBoolean(false)
        private val textureDestroyed = AtomicBoolean(false)
        private val releaseListeners = mutableListOf<() -> Unit>()
        private val releaseLease = PreviewSurfaceLease {
            runCatching { surface.release() }
            val releaseTexture: Boolean
            val listeners: List<() -> Unit>
            synchronized(surfaceRecordsLock) {
                releaseTexture = textureDestroyed.get()
                if (pendingSurfaceReleases[texture] === this@DecoderSurface) {
                    pendingSurfaceReleases.remove(texture)
                }
                released.set(true)
                listeners = releaseListeners.toList()
                releaseListeners.clear()
            }
            if (releaseTexture) runCatching { texture.release() }
            listeners.forEach { listener -> runCatching { listener() } }
        }
        private val destroyed = AtomicBoolean(false)

        val destroyNotified: Boolean
            get() = destroyed.get()

        fun markDestroyed(): Boolean = destroyed.compareAndSet(false, true)

        fun markTextureDestroyed() {
            textureDestroyed.set(true)
        }

        fun whenReleased(listener: () -> Unit) {
            val invokeImmediately = synchronized(surfaceRecordsLock) {
                if (released.get()) {
                    true
                } else {
                    releaseListeners += listener
                    false
                }
            }
            if (invokeImmediately) listener()
        }

        fun release() = releaseLease.release()
    }
}
