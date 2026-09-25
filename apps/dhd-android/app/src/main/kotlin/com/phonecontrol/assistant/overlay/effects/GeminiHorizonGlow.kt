package com.phonecontrol.assistant.overlay.effects

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun BoxScope.GeminiHorizonGlow(attention: Boolean) {
    val density = LocalDensity.current
    val wavePhase by motionPhase(
        label = "gemini-horizon-wave",
        duration = 3_200,
        enabled = true,
    )
    val breathPhase by motionPhase(
        label = "gemini-horizon-breath",
        duration = 4_000,
        enabled = true,
    )
    val breath = 0.82f + 0.18f * sin(breathPhase)

    Canvas(Modifier.matchParentSize()) {
        // 1. Ambient floor gradient across bottom 42%
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.36f to Color.Transparent,
                0.68f to if (attention) Color(0xFFD97706).copy(alpha = 0.26f * breath) else Color(0xFF1E40AF).copy(alpha = 0.28f * breath),
                1.0f to if (attention) Color(0xFFF59E0B).copy(alpha = 0.62f * breath) else Color(0xFF2563EB).copy(alpha = 0.65f * breath),
                startY = 0f,
                endY = size.height,
            ),
        )

        // 2. Primary undulating horizontal light wave (flowing crest)
        val primaryX = size.width * (0.50f + 0.26f * sin(wavePhase))
        val primaryY = size.height * 1.05f
        val primaryRadius = size.width * 0.46f
        val primaryColors = if (attention) {
            listOf(
                Color(0xFFFBBF24).copy(alpha = 0.75f * breath),
                Color(0xFFF59E0B).copy(alpha = 0.50f * breath),
                Color(0xFFD97706).copy(alpha = 0.22f * breath),
                Color.Transparent,
            )
        } else {
            listOf(
                Color(0xFF38BDF8).copy(alpha = 0.70f * breath),
                Color(0xFF2563EB).copy(alpha = 0.80f * breath),
                Color(0xFF1D4ED8).copy(alpha = 0.40f * breath),
                Color.Transparent,
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = primaryColors,
                center = Offset(primaryX, primaryY),
                radius = primaryRadius,
            ),
            center = Offset(primaryX, primaryY),
            radius = primaryRadius,
        )

        // 3. Secondary harmonic wave counter-pulsing
        val secondaryX = size.width * (0.50f - 0.20f * cos(wavePhase * 0.78f))
        val secondaryY = size.height * 1.02f
        val secondaryRadius = size.width * 0.38f
        val secondaryColors = if (attention) {
            listOf(
                Color(0xFFF59E0B).copy(alpha = 0.40f * breath),
                Color(0xFFD97706).copy(alpha = 0.18f * breath),
                Color.Transparent,
            )
        } else {
            listOf(
                Color(0xFF60A5FA).copy(alpha = 0.45f * breath),
                Color(0xFF1E40AF).copy(alpha = 0.30f * breath),
                Color.Transparent,
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = secondaryColors,
                center = Offset(secondaryX, secondaryY),
                radius = secondaryRadius,
            ),
            center = Offset(secondaryX, secondaryY),
            radius = secondaryRadius,
        )

        // 4. Luminous rim edge accent along the bottom border curve
        val rimXRatio = (primaryX / size.width).coerceIn(0f, 1f)
        val rimBrush = Brush.horizontalGradient(
            0.0f to Color.Transparent,
            (rimXRatio - 0.25f).coerceAtLeast(0f) to Color.Transparent,
            rimXRatio to if (attention) Color(0xFFFDE68A).copy(alpha = 0.65f * breath) else Color(0xFF93C5FD).copy(alpha = 0.70f * breath),
            (rimXRatio + 0.25f).coerceAtMost(1f) to Color.Transparent,
            1.0f to Color.Transparent,
        )
        val rimHeightPx = with(density) { 2.2.dp.toPx() }
        drawRoundRect(
            brush = rimBrush,
            topLeft = Offset(0f, size.height - rimHeightPx),
            size = Size(size.width, rimHeightPx),
            cornerRadius = CornerRadius(rimHeightPx),
        )
    }
}
