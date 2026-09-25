package com.phonecontrol.assistant.overlay.bubble

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.overlay.effects.GeminiHorizonGlow
import com.phonecontrol.assistant.overlay.effects.motionPhase
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.math.sin

@Composable
internal fun DhdBubblePreview(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .semantics { contentDescription = "DHD floating bubble" },
        contentAlignment = Alignment.Center,
    ) {
        DhdIdentity(
            modifier = Modifier.fillMaxSize(),
            working = false,
            attention = false,
            animated = true,
        )
    }
}

@Composable
internal fun BubbleButton(
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    running: Boolean,
    attention: Boolean,
    animated: Boolean,
) {
    val colors = LocalAssistantColors.current
    val bubbleShape = CircleShape

    Box(
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = "Open DHD assistant. Drag to move; release to dock to a side." }
            .pointerInput(onDragEnd) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, delta ->
                    change.consume()
                    onDrag(delta.x, delta.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(bubbleShape)
                    .background(
                        colors.composerBackground.copy(
                            alpha = if (colors.isDark) 0.98f else 0.96f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                GeminiHorizonGlow(attention = attention)
                DhdIdentity(
                    modifier = Modifier.size(36.dp),
                    working = !attention,
                    attention = attention,
                    animated = animated,
                )
            }
        } else {
            DhdIdentity(
                modifier = Modifier.fillMaxSize(),
                working = false,
                attention = attention,
                animated = animated,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(bubbleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Open assistant",
                    onClick = onClick,
                )
                .semantics { contentDescription = "Open DHD assistant" },
        )
    }
}

@Composable
internal fun DhdIdentity(
    modifier: Modifier,
    working: Boolean,
    attention: Boolean = false,
    animated: Boolean = true,
) {
    val colors = LocalAssistantColors.current
    val phase by motionPhase(
        label = "dhd-identity-" + if (working) "working" else "idle",
        duration = if (working) 3_800 else 8_000,
        enabled = animated,
    )
    val haloColor = if (attention) colors.warningAmber else colors.accentBlue

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * 0.48f
            val breath = (sin(phase) + 1f) / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        haloColor.copy(alpha = 0.18f + breath * 0.04f),
                        Color.Transparent,
                    ),
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = haloColor.copy(alpha = 0.12f + breath * 0.05f),
                radius = radius * 0.84f,
                style = Stroke(1.1.dp.toPx()),
            )
            drawArc(
                color = haloColor.copy(alpha = if (working) 0.38f else 0.2f),
                startAngle = if (animated) phase * 57.29578f else -90f,
                sweepAngle = if (working) 72f else 42f,
                useCenter = false,
                style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        DhdIcon(
            modifier = Modifier
                .fillMaxSize()
                .padding(1.dp),
        )
    }
}

@Composable
private fun DhdIcon(modifier: Modifier) {
    val context = LocalContext.current
    val icon = remember(context) {
        context.packageManager.getApplicationIcon(context.packageName)
    }
    Box(
        modifier = modifier.clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                icon.draw(canvas.nativeCanvas)
            }
        }
    }
}
