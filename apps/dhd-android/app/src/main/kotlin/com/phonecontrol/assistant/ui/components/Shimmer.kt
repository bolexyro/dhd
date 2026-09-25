package com.phonecontrol.assistant.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

internal data class Shimmer(
    val brush: Brush,
    val pulseAlpha: Float,
)

internal data class ShimmerLabels(
    val transition: String,
    val translate: String,
    val pulse: String,
)

@Composable
internal fun rememberPulsingShimmer(
    accent: Color,
    highlight: Color,
    labels: ShimmerLabels,
): Shimmer {
    val infiniteTransition = rememberInfiniteTransition(label = labels.transition)
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = -150f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = labels.translate,
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = labels.pulse,
    )
    return Shimmer(
        brush = shimmerBrush(accent, highlight, shimmerTranslate, bandWidth = 160f),
        pulseAlpha = pulseAlpha,
    )
}

internal fun shimmerBrush(
    accent: Color,
    highlight: Color,
    translate: Float,
    bandWidth: Float,
): Brush = Brush.linearGradient(
    colors = listOf(
        accent.copy(alpha = 0.35f),
        accent,
        highlight,
        accent,
        accent.copy(alpha = 0.35f),
    ),
    start = Offset(translate, 0f),
    end = Offset(translate + bandWidth, 0f),
)
