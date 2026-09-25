package com.phonecontrol.assistant.overlay.effects

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.core.needsAttention
import com.phonecontrol.assistant.overlay.OverlayPanelMode
import com.phonecontrol.assistant.overlay.shouldShowOverlayGlow
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.theme.DhdPalette
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun motionPhase(
    label: String,
    duration: Int = 6_000,
    enabled: Boolean = true,
): State<Float> {
    val context = LocalContext.current
    val systemScale = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
    }
    val shouldAnimate = enabled && systemScale > 0f
    if (!shouldAnimate) {
        return remember(label) { mutableStateOf(0f) }
    }
    val scaledDuration = (duration * systemScale.coerceIn(0.1f, 2f))
        .roundToInt()
        .coerceAtLeast(1)
    val motion = rememberInfiniteTransition(label = label)
    val animationSpec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween<Float>(scaledDuration, easing = LinearEasing),
    )
    return motion.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = animationSpec,
        label = label,
    )
}

private data class StardustParticle(
    val xRatio: Float,
    val yRatio: Float,
    val radiusDp: Float,
    val baseAlpha: Float,
    val floatSpeedDp: Float,
    val swayAmplitudeDp: Float,
    val twinklePhase: Float,
)

@Composable
fun OverlayGlow(
    sessionState: StateFlow<SessionState>,
    panelMode: StateFlow<OverlayPanelMode>,
    glowTrigger: StateFlow<Long>,
    hidden: StateFlow<Boolean>,
) {
    val state by sessionState.collectAsState()
    val mode by panelMode.collectAsState()
    val isHidden by hidden.collectAsState()
    val trigger by glowTrigger.collectAsState()

    val shouldGlow = shouldShowOverlayGlow(mode, state, isHidden)
    val anim = remember { Animatable(1f) }

    // A glow pulse is explicitly armed by the composer-open trigger. Do not
    // key this animation to panel visibility: closing a result card returns
    // to COMPOSER, but should not replay the full-screen entrance pulse.
    LaunchedEffect(trigger) {
        if (shouldGlow) {
            anim.stop()
            anim.snapTo(0f)
            anim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 2_600, easing = LinearEasing),
            )
        } else {
            anim.stop()
            anim.snapTo(1f)
        }
    }
    LaunchedEffect(shouldGlow) {
        if (!shouldGlow) {
            anim.stop()
            anim.snapTo(1f)
        }
    }

    val progress = anim.value
    if (progress >= 1f || !shouldGlow) return

    // Organic liquid alpha curve: liquid pour-in (0.0 -> 0.18), fluid slosh/shimmer (0.18 -> 0.42), viscous decay (0.42 -> 1.0)
    val overallAlpha = when {
        progress < 0.18f -> (progress / 0.18f).pow(0.85f)
        progress <= 0.42f -> 1f
        else -> {
            val decay = (progress - 0.42f) / 0.58f
            (1f - decay).pow(2.0f)
        }
    }.coerceIn(0f, 1f)

    if (overallAlpha <= 0.005f) return

    val colors = LocalAssistantColors.current
    val view = LocalView.current
    val density = LocalDensity.current
    val attention = state.needsAttention

    val deviceCornerRadius = remember(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val insets = view.rootWindowInsets
                insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
            }.getOrNull()
        } else null
    }
    val cornerRadiusPx = deviceCornerRadius?.takeIf { it > 0f }
        ?: with(density) { 34.dp.toPx() }

    // Multi-color spectrum from Gemini:
    // Right (Green) -> Bottom-Right (Purple) -> Bottom (Coral Red) -> Bottom-Left (Amber) -> Left (Blue) -> Top (Cyan)
    val colorGreen = if (attention) colors.warningAmber else DhdPalette.Emerald500
    val colorPurple = if (attention) colors.warningAmber else DhdPalette.Violet500
    val colorCoral = if (attention) colors.warningAmber else DhdPalette.Red500
    val colorAmber = if (attention) colors.warningAmber else DhdPalette.Amber500
    val colorBlue = if (attention) colors.warningAmber else DhdPalette.Blue600
    val colorCyan = if (attention) colors.warningAmber else DhdPalette.Cyan500

    val stardust = remember {
        val random = Random(2026)
        List(75) {
            StardustParticle(
                xRatio = 0.05f + random.nextFloat() * 0.90f,
                yRatio = 0.58f + random.nextFloat() * 0.40f,
                radiusDp = 0.8f + random.nextFloat() * 1.5f,
                baseAlpha = 0.35f + random.nextFloat() * 0.55f,
                floatSpeedDp = 20f + random.nextFloat() * 32f,
                swayAmplitudeDp = 5f + random.nextFloat() * 10f,
                twinklePhase = random.nextFloat() * 6.28f,
            )
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        // 1. 360 SOFT AMBIENT LIGHT BLEED (NOT A LINE - pure inward diffusion from physical screen bezels)
        // Bleeds 28dp inward from screen edges, fading smoothly to 0 opacity with no hard stroke line
        val edgeBleedWidth = with(density) { (24.dp + 4.dp * sin(progress * 4f)).toPx() }
        val topBleedHeight = with(density) { 32.dp.toPx() }

        // Left Edge Ambient Light Bleed (Electric Blue & Amber)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    colorBlue.copy(alpha = 0.30f * overallAlpha),
                    colorBlue.copy(alpha = 0.12f * overallAlpha),
                    Color.Transparent,
                ),
                startX = 0f,
                endX = edgeBleedWidth,
            ),
            topLeft = Offset.Zero,
            size = Size(edgeBleedWidth, size.height),
        )

        // Right Edge Ambient Light Bleed (Emerald Green & Purple)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    colorGreen.copy(alpha = 0.12f * overallAlpha),
                    colorGreen.copy(alpha = 0.28f * overallAlpha),
                ),
                startX = size.width - edgeBleedWidth,
                endX = size.width,
            ),
            topLeft = Offset(size.width - edgeBleedWidth, 0f),
            size = Size(edgeBleedWidth, size.height),
        )

        // Top Edge Ambient Light Bleed (Cyan / Status bar glow)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    colorCyan.copy(alpha = 0.24f * overallAlpha),
                    colorCyan.copy(alpha = 0.08f * overallAlpha),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = topBleedHeight,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, topBleedHeight),
        )

        // Top-Left Corner Ambient Light Bleed
        val cornerRadius = cornerRadiusPx * 1.6f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorCyan.copy(alpha = 0.30f * overallAlpha),
                    colorBlue.copy(alpha = 0.14f * overallAlpha),
                    Color.Transparent,
                ),
                center = Offset(0f, 0f),
                radius = cornerRadius,
            ),
            radius = cornerRadius,
            center = Offset(0f, 0f),
        )

        // Top-Right Corner Ambient Light Bleed
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorGreen.copy(alpha = 0.28f * overallAlpha),
                    colorPurple.copy(alpha = 0.12f * overallAlpha),
                    Color.Transparent,
                ),
                center = Offset(size.width, 0f),
                radius = cornerRadius,
            ),
            radius = cornerRadius,
            center = Offset(size.width, 0f),
        )

        // 3. MORE AT THE BOTTOM: Rich flowing liquid light pool
        val waveTime = progress * 2.2f * PI.toFloat()
        val waveHeightPx = with(density) { 16.dp.toPx() }
        val liquidSurge = sin(progress * PI.toFloat()).pow(0.75f)
        val liquidBaseHeight = with(density) { 260.dp.toPx() } * (0.85f + 0.25f * liquidSurge)

        val wavePath = Path().apply {
            moveTo(0f, size.height)
            val startY = size.height - liquidBaseHeight + sin(waveTime) * waveHeightPx
            lineTo(0f, startY)
            val segments = 20
            for (i in 1..segments) {
                val segX = size.width * (i.toFloat() / segments)
                val normX = i.toFloat() / segments
                val waveY = size.height - liquidBaseHeight +
                    sin(waveTime * 1.4f + normX * 5.8f) * waveHeightPx +
                    cos(waveTime * 2.0f + normX * 8.6f) * (waveHeightPx * 0.5f)
                lineTo(segX, waveY)
            }
            lineTo(size.width, size.height)
            close()
        }

        // Fill flowing liquid body with vertical gradient
        drawPath(
            path = wavePath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    colorCoral.copy(alpha = 0.12f * overallAlpha),
                    colorAmber.copy(alpha = 0.26f * overallAlpha),
                    colorCoral.copy(alpha = 0.38f * overallAlpha),
                ),
                startY = size.height - liquidBaseHeight - waveHeightPx,
                endY = size.height,
            ),
        )

        // Deep bottom radial light blooms (expanded coverage, liquid radiance)
        val bottomRadius = size.width * 0.85f

        // Bottom-Left Liquid Bloom (Amber & Electric Blue)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorAmber.copy(alpha = 0.50f * overallAlpha),
                    colorBlue.copy(alpha = 0.28f * overallAlpha),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.05f, size.height),
                radius = bottomRadius,
            ),
            radius = bottomRadius,
            center = Offset(size.width * 0.05f, size.height),
        )

        // Bottom-Center Liquid Bloom (Warm Coral Red & Crimson)
        val centerBottomRadius = size.width * 0.72f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorCoral.copy(alpha = 0.48f * overallAlpha),
                    DhdPalette.Rose600.copy(alpha = 0.26f * overallAlpha),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.5f, size.height),
                radius = centerBottomRadius,
            ),
            radius = centerBottomRadius,
            center = Offset(size.width * 0.5f, size.height),
        )

        // Bottom-Right Liquid Bloom (Violet & Emerald Green)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorPurple.copy(alpha = 0.48f * overallAlpha),
                    colorGreen.copy(alpha = 0.24f * overallAlpha),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.95f, size.height),
                radius = bottomRadius,
            ),
            radius = bottomRadius,
            center = Offset(size.width * 0.95f, size.height),
        )

        // Right Edge subtle fluid accent
        val rightRadius = size.width * 0.45f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorGreen.copy(alpha = 0.20f * overallAlpha),
                    Color.Transparent,
                ),
                center = Offset(size.width, size.height * 0.38f),
                radius = rightRadius,
            ),
            radius = rightRadius,
            center = Offset(size.width, size.height * 0.38f),
        )

        // Subtle Top / Status bar accent (very thin and light)
        val topGlowHeight = with(density) { 40.dp.toPx() }
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    colorCyan.copy(alpha = 0.08f * overallAlpha),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = topGlowHeight,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, topGlowHeight),
        )

        // 4. Subtle Monochrome White Starlight Specks (clean, gentle floating star dust)
        val stardustColor = Color.White
        for (star in stardust) {
            val swayPx = with(density) {
                (sin(progress * 4f + star.twinklePhase) * star.swayAmplitudeDp).dp.toPx()
            }
            val x = star.xRatio * size.width + swayPx
            val y = star.yRatio * size.height - with(density) { (progress * star.floatSpeedDp).dp.toPx() }
            val twinkle = (sin(progress * 10f + star.twinklePhase) + 1f) / 2f
            val dotAlpha = (star.baseAlpha * overallAlpha * (0.40f + 0.60f * twinkle)).coerceIn(0f, 1f)

            if (dotAlpha > 0.02f && y in 0f..size.height && x in 0f..size.width) {
                drawCircle(
                    color = stardustColor.copy(alpha = dotAlpha),
                    radius = with(density) { star.radiusDp.dp.toPx() },
                    center = Offset(x, y),
                )
            }
        }
    }
}
