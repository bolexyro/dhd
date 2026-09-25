package com.phonecontrol.assistant.ui.components.reasoning

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.ui.theme.DhdPalette
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.math.roundToInt

internal fun reasoningTrackEfforts(visibleEfforts: List<ReasoningEffort>): List<ReasoningEffort> =
    visibleEfforts.distinct().sortedBy(ReasoningEffort::ordinal)
        .ifEmpty { listOf(ReasoningEffort.default) }

internal fun reasoningTrackPosition(orderedEfforts: List<ReasoningEffort>, selectedEffort: ReasoningEffort): Float {
    val selectedIndex = orderedEfforts.indexOf(selectedEffort).coerceAtLeast(0)
    return if (orderedEfforts.size == 1) {
        1.0f
    } else {
        selectedIndex.toFloat() / orderedEfforts.lastIndex.toFloat()
    }
}

internal fun reasoningTrackFraction(x: Float, width: Float, innerMargin: Float, thumbRadius: Float): Float {
    val startX = innerMargin + thumbRadius
    val endX = width - innerMargin - thumbRadius
    val usableWidth = (endX - startX).coerceAtLeast(1f)
    return ((x - startX) / usableWidth).coerceIn(0f, 1f)
}

internal fun reasoningEffortAtFraction(fraction: Float, orderedEfforts: List<ReasoningEffort>): ReasoningEffort {
    val nearestIndex = (fraction * orderedEfforts.lastIndex).roundToInt()
        .coerceIn(0, orderedEfforts.lastIndex)
    return orderedEfforts[nearestIndex]
}

@Composable
internal fun ReasoningEffortTrack(
    selectedEffort: ReasoningEffort,
    visibleEfforts: List<ReasoningEffort>,
    onSelect: (ReasoningEffort) -> Unit,
) {
    val colors = LocalAssistantColors.current
    val density = LocalDensity.current
    val orderedEfforts = reasoningTrackEfforts(visibleEfforts)
    val trackShape = CircleShape
    val innerMargin = with(density) { 6.dp.toPx() }
    val thumbRadius = with(density) { 23.dp.toPx() }
    val targetPosition = reasoningTrackPosition(orderedEfforts, selectedEffort)

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val animatedPosition by animateFloatAsState(
        targetValue = if (isDragging) dragFraction else targetPosition,
        animationSpec = if (isDragging) spring(stiffness = Spring.StiffnessHigh) else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "reasoning_slider_position",
    )

    val trackBg = if (colors.isDark) colors.composerBackground else DhdPalette.TrackLight
    val trackBorder = if (colors.isDark) colors.borderColor else DhdPalette.TrackBorderLight

    Surface(
        shape = trackShape,
        color = trackBg,
        border = BorderStroke(1.dp, trackBorder),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clip(trackShape),
        ) {
            val updateFractionAndEffort: (Float, Float) -> Unit = { x, width ->
                if (orderedEfforts.size > 1) {
                    val fraction = reasoningTrackFraction(x, width, innerMargin, thumbRadius)
                    dragFraction = fraction
                    onSelect(reasoningEffortAtFraction(fraction, orderedEfforts))
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription =
                            "Reasoning effort selector. Selected ${selectedEffort.label}. Swipe to choose."
                    }
                    .pointerInput(orderedEfforts) {
                        detectTapGestures(
                            onTap = { offset ->
                                updateFractionAndEffort(offset.x, size.width.toFloat())
                            },
                        )
                    }
                    .pointerInput(orderedEfforts) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                updateFractionAndEffort(offset.x, size.width.toFloat())
                            },
                            onDragEnd = {
                                isDragging = false
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                updateFractionAndEffort(change.position.x, size.width.toFloat())
                            },
                        )
                    },
            ) {
                val startX = innerMargin + thumbRadius
                val endX = size.width - innerMargin - thumbRadius
                val usableWidth = (endX - startX).coerceAtLeast(1f)
                val currentFraction = animatedPosition.coerceIn(0f, 1f)
                val selectedX = startX + usableWidth * currentFraction
                val centerY = size.height / 2f
                val pillHeight = size.height - 2f * innerMargin

                // Active blue pill track
                if (currentFraction > 0.001f) {
                    val pillWidth =
                        (selectedX + thumbRadius - innerMargin).coerceIn(pillHeight, size.width - 2f * innerMargin)
                    drawRoundRect(
                        color = colors.accentBlue,
                        topLeft = Offset(innerMargin, innerMargin),
                        size = androidx.compose.ui.geometry.Size(
                            width = if (currentFraction >= 0.999f) size.width - 2f * innerMargin else pillWidth,
                            height = pillHeight,
                        ),
                        cornerRadius = CornerRadius(pillHeight / 2f, pillHeight / 2f),
                    )
                }

                // Reasoning level dots
                orderedEfforts.forEachIndexed { index, effort ->
                    val dotPosition = if (orderedEfforts.size == 1) {
                        1.0f
                    } else {
                        index.toFloat() / orderedEfforts.lastIndex.toFloat()
                    }
                    val x = startX + usableWidth * dotPosition
                    val dotRadius = with(density) { 4.5.dp.toPx() }
                    if (kotlin.math.abs(x - selectedX) > thumbRadius * 0.65f) {
                        drawCircle(
                            color = if (x < selectedX) {
                                Color.White.copy(alpha = 0.5f)
                            } else {
                                if (colors.isDark) colors.textSecondary.copy(alpha = 0.75f) else DhdPalette.SystemGray
                            },
                            radius = dotRadius,
                            center = Offset(x, centerY),
                        )
                    }
                }

                // In light mode, add a subtle soft shadow/outline ring around the white knob for crisp definition
                if (!colors.isDark) {
                    drawCircle(
                        color = DhdPalette.TrackShadow,
                        radius = thumbRadius + with(density) { 1.5.dp.toPx() },
                        center = Offset(selectedX, centerY + with(density) { 0.75.dp.toPx() }),
                    )
                }

                // Solid white circular knob
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = Offset(selectedX, centerY),
                )
            }
        }
    }
}
