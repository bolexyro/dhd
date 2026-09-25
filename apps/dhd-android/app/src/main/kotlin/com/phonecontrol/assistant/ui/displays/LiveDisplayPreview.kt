package com.phonecontrol.assistant.ui.displays

import android.view.Surface as AndroidSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.ui.displays.surface.PreviewLifecycleBinding
import com.phonecontrol.assistant.ui.displays.surface.PreviewSurfaceDestroyed
import com.phonecontrol.assistant.ui.displays.surface.ReadOnlyPreviewTextureView
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

internal const val LIVE_DISPLAY_CORNER_RADIUS_DP = 12
internal val LIVE_DISPLAY_SHADOW_ELEVATION = 2.dp

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

@Composable
internal fun PreviewStatusOverlay(state: LiveDisplayPreviewState) {
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
