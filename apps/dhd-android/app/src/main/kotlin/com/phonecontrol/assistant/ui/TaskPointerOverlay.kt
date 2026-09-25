package com.phonecontrol.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.domain.ClickPhase
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.domain.TASK_CLICK_MOVE_DURATION_MS
import kotlinx.coroutines.launch
import kotlin.math.max

private const val CLICK_PRESS_DURATION_MS = 150L
private const val CLICK_PRESS_DOWN_MS = 60
private const val MIN_SWIPE_VISUAL_DURATION_MS = 180L

private data class GestureVisual(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
)

/**
 * Read-only visual feedback for the task display. This is deliberately a
 * Compose sibling of the decoder TextureView: it never receives pointer
 * input, changes the decoded frame, or turns the preview into a control path.
 */
@Composable
internal fun TaskPointerOverlay(
    event: TaskPointerEvent?,
    modifier: Modifier = Modifier,
) {
    val cursorX = remember { Animatable(0f) }
    val cursorY = remember { Animatable(0f) }
    val clickScale = remember { Animatable(1f) }
    var gestureEvent by remember { mutableStateOf<TaskPointerEvent.Swipe?>(null) }
    var hasCursor by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var lastSeenSequence by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(event?.sequence) {
        val pointer = event
        if (!initialized) {
            // A new inline/full-screen surface can be created while the
            // coordinator still holds the latest event. That event was
            // already presented by the previous surface; only later
            // sequences should animate here.
            initialized = true
            lastSeenSequence = pointer?.sequence
            gestureEvent = null
            when (pointer) {
                is TaskPointerEvent.Click -> {
                    val target = mapDisplayPoint(
                        pointer.x,
                        pointer.y,
                        pointer.displayWidth,
                        pointer.displayHeight,
                    )
                    cursorX.snapTo(target.x)
                    cursorY.snapTo(target.y)
                    clickScale.snapTo(1f)
                    hasCursor = true
                }

                is TaskPointerEvent.Calibration -> {
                    val target = mapDisplayPoint(
                        pointer.x,
                        pointer.y,
                        pointer.displayWidth,
                        pointer.displayHeight,
                    )
                    cursorX.snapTo(target.x)
                    cursorY.snapTo(target.y)
                    clickScale.snapTo(1f)
                    hasCursor = true
                }

                is TaskPointerEvent.Swipe -> {
                    val target = mapDisplayPoint(
                        pointer.endX,
                        pointer.endY,
                        pointer.displayWidth,
                        pointer.displayHeight,
                    )
                    cursorX.snapTo(target.x)
                    cursorY.snapTo(target.y)
                    hasCursor = true
                }

                null -> Unit
            }
            return@LaunchedEffect
        }
        if (pointer?.sequence == lastSeenSequence) return@LaunchedEffect
        lastSeenSequence = pointer?.sequence
        if (pointer == null) {
            gestureEvent = null
            return@LaunchedEffect
        }

        when (pointer) {
            is TaskPointerEvent.Click -> {
                gestureEvent = null
                val target = mapDisplayPoint(
                    pointer.x,
                    pointer.y,
                    pointer.displayWidth,
                    pointer.displayHeight,
                )
                when (pointer.phase) {
                    ClickPhase.MOVING -> {
                        moveCursor(
                            cursorX = cursorX,
                            cursorY = cursorY,
                            target = target,
                            hasCursor = hasCursor,
                            durationMs = TASK_CLICK_MOVE_DURATION_MS,
                        )
                        hasCursor = true
                        clickScale.snapTo(1f)
                    }

                    ClickPhase.PRESSED -> {
                        // Freshness capture and visual travel can overlap, so
                        // snap here as a guard against a late recomposition
                        // or a newly attached preview surface.
                        cursorX.snapTo(target.x)
                        cursorY.snapTo(target.y)
                        hasCursor = true
                        clickScale.snapTo(1f)
                        clickScale.animateTo(
                            targetValue = 1f,
                            animationSpec = keyframes {
                                durationMillis = CLICK_PRESS_DURATION_MS.toInt()
                                0.78f at CLICK_PRESS_DOWN_MS
                                1f at CLICK_PRESS_DURATION_MS.toInt()
                            },
                        )
                        clickScale.snapTo(1f)
                    }
                }
            }

            is TaskPointerEvent.Calibration -> {
                gestureEvent = null
                val target = mapDisplayPoint(
                    pointer.x,
                    pointer.y,
                    pointer.displayWidth,
                    pointer.displayHeight,
                )
                cursorX.snapTo(target.x)
                cursorY.snapTo(target.y)
                clickScale.snapTo(1f)
                hasCursor = true
            }

            is TaskPointerEvent.Swipe -> {
                gestureEvent = pointer
                val visual = pointer.toGestureVisual()
                val start = mapDisplayPoint(
                    visual.startX,
                    visual.startY,
                    visual.displayWidth,
                    visual.displayHeight,
                )
                val end = mapDisplayPoint(
                    visual.endX,
                    visual.endY,
                    visual.displayWidth,
                    visual.displayHeight,
                )
                val durationMs = max(MIN_SWIPE_VISUAL_DURATION_MS, visual.durationMs)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                cursorX.snapTo(start.x)
                cursorY.snapTo(start.y)
                hasCursor = true
                kotlinx.coroutines.coroutineScope {
                    launch {
                        cursorX.animateTo(end.x, tween(durationMs, easing = LinearEasing))
                    }
                    launch {
                        cursorY.animateTo(end.y, tween(durationMs, easing = LinearEasing))
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gesture = gestureEvent?.toGestureVisual()
            if (gesture != null) {
                drawArrow(
                    point = Offset(cursorX.value * size.width, cursorY.value * size.height),
                    scaleFactor = 1f,
                )
            } else if (event is TaskPointerEvent.Click || event is TaskPointerEvent.Calibration) {
                drawArrow(
                    point = Offset(cursorX.value * size.width, cursorY.value * size.height),
                    scaleFactor = clickScale.value,
                )
            }
        }
    }
}

private suspend fun moveCursor(
    cursorX: Animatable<Float, AnimationVector1D>,
    cursorY: Animatable<Float, AnimationVector1D>,
    target: Offset,
    hasCursor: Boolean,
    durationMs: Long = 200L,
) {
    if (!hasCursor) {
        cursorX.snapTo(target.x)
        cursorY.snapTo(target.y)
        return
    }
    kotlinx.coroutines.coroutineScope {
        launch { cursorX.animateTo(target.x, tween(durationMs.toInt(), easing = LinearEasing)) }
        launch { cursorY.animateTo(target.y, tween(durationMs.toInt(), easing = LinearEasing)) }
    }
}

private fun TaskPointerEvent.toGestureVisual(): GestureVisual = when (this) {
    is TaskPointerEvent.Swipe -> GestureVisual(
        startX = startX,
        startY = startY,
        endX = endX,
        endY = endY,
        durationMs = durationMs,
        displayWidth = displayWidth,
        displayHeight = displayHeight,
    )

    is TaskPointerEvent.Click -> error("Clicks do not have a gesture track.")
    is TaskPointerEvent.Calibration -> error("Calibration points do not have a gesture track.")
}

private fun mapDisplayPoint(
    x: Int,
    y: Int,
    displayWidth: Int,
    displayHeight: Int,
): Offset = normalizedPoint(x, y, displayWidth, displayHeight)

internal fun normalizedPoint(x: Int, y: Int, width: Int, height: Int): Offset = Offset(
    x = x.coerceIn(0, width - 1).toFloat() / width,
    y = y.coerceIn(0, height - 1).toFloat() / height,
)

private fun DrawScope.drawArrow(
    point: Offset,
    scaleFactor: Float,
) {
    val scale = 16.dp.toPx() / 48f * scaleFactor
    val left = point.x - 4f * scale
    val top = point.y - 4f * scale
    val path = Path().apply {
        moveTo(left + 4f * scale, top + 4f * scale)
        lineTo(left + 38f * scale, top + 16f * scale)
        lineTo(left + 24f * scale, top + 24f * scale)
        lineTo(left + 16f * scale, top + 38f * scale)
        close()
    }
    drawPath(
        path = path,
        color = Color(0xFF00E6FF).copy(alpha = 0.42f),
        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(path = path, color = Color(0xFF2B8CDB))
    drawPath(
        path = path,
        color = Color.White,
        style = Stroke(width = 1.3.dp.toPx(), join = StrokeJoin.Round),
    )
}
