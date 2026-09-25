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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.ui.theme.DhdPalette
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

@Composable
internal fun ComposerPerimeterGlow(
    modifier: Modifier,
    hasRecovery: Boolean,
    capsule: Boolean,
) {
    val colors = LocalAssistantColors.current
    val density = LocalDensity.current
    val glowOrbitPhase by motionPhase(
        label = "composer-orbit-glow",
        duration = 3_400,
        enabled = true,
    )
    val glowBreathPhase by motionPhase(
        label = "composer-ambient-glow",
        duration = 4_500,
        enabled = true,
    )
    val breath = 0.88f + 0.12f * sin(glowBreathPhase)
    val glowBase = if (colors.isDark) Color.White else DhdPalette.Slate800
    val glowAccent = if (colors.isDark) DhdPalette.Blue300 else DhdPalette.Blue500
    val chromaticColors = if (hasRecovery) {
        listOf(
            colors.warningAmber,
            DhdPalette.Amber400,
            colors.warningAmber,
            DhdPalette.Amber400,
            colors.warningAmber,
            DhdPalette.Amber400,
        )
    } else {
        listOf(
            DhdPalette.Emerald500,
            DhdPalette.Cyan500,
            DhdPalette.Blue500,
            DhdPalette.Violet500,
            DhdPalette.Red500,
            DhdPalette.Amber500,
        )
    }
    val shift = (glowOrbitPhase / (2 * PI.toFloat())) % 1f
    val stops = List(17) { index ->
        val fraction = index.toFloat() / 16f
        val sampleFraction = ((fraction - shift) % 1f + 1f) % 1f
        val scaled = sampleFraction * 6f
        val colorIndex = scaled.toInt() % 6
        val nextIndex = (colorIndex + 1) % 6
        val blend = scaled - scaled.toInt()
        val first = chromaticColors[colorIndex]
        val second = chromaticColors[nextIndex]
        val distanceFromHead = abs(((sampleFraction % 1f) + 1.5f) % 1f - 0.5f)
        val pulse = (1f - distanceFromHead * 2f).coerceIn(0f, 1f).pow(2.2f) * 0.40f
        val red = (first.red + (second.red - first.red) * blend + pulse).coerceAtMost(1f)
        val green = (first.green + (second.green - first.green) * blend + pulse).coerceAtMost(1f)
        val blue = (first.blue + (second.blue - first.blue) * blend + pulse).coerceAtMost(1f)
        fraction to Color(red, green, blue)
    }

    Canvas(modifier) {
        val baseCornerPx = if (capsule) size.height / 2f else with(density) { 28.dp.toPx() }
        val sweepBrush = Brush.sweepGradient(
            *stops.toTypedArray(),
            center = Offset(size.width / 2f, size.height / 2f),
        )
        val outerSpread = with(density) { 5.dp.toPx() }
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowAccent.copy(alpha = 0.018f * breath),
                    glowBase.copy(alpha = 0.008f * breath),
                    Color.Transparent,
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = (size.width / 2f) + outerSpread,
            ),
            topLeft = Offset(-outerSpread, -outerSpread),
            size = Size(size.width + outerSpread * 2f, size.height + outerSpread * 2f),
            cornerRadius = CornerRadius(baseCornerPx + outerSpread),
        )
        drawRoundRect(
            brush = sweepBrush,
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(baseCornerPx),
            style = Stroke(with(density) { 6.dp.toPx() }),
            alpha = 0.48f * breath,
        )
    }
}
