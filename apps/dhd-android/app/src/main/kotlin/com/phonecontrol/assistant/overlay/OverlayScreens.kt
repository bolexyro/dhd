package com.phonecontrol.assistant.overlay

import android.os.Build
import android.view.Surface as AndroidSurface
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.phonecontrol.assistant.developer.TaskPreviewState
import com.phonecontrol.assistant.developer.DeveloperModeStatus
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.AssistantColorScheme
import com.phonecontrol.assistant.ui.FastModeButton
import com.phonecontrol.assistant.ui.LiveDisplayPreview
import com.phonecontrol.assistant.ui.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.LocalAssistantColors
import com.phonecontrol.assistant.ui.MarkdownContent
import com.phonecontrol.assistant.ui.ReasoningEffortButton
import com.phonecontrol.assistant.ui.ReasoningEffortTrack
import com.phonecontrol.assistant.ui.THINKING_WORDS
import com.phonecontrol.assistant.ui.liveDisplayCornerShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class OverlayPanelMode { BUBBLE, COMPOSER, WORKING, ATTENTION, RESULT }

enum class OverlaySwipeDirection { LEFT, RIGHT }

internal enum class OverlayRecoveryKind { ATTENTION, COMPANION, DEVELOPER }

private const val MAX_DRAFT_LENGTH = 4_000

@Composable
private fun motionPhase(
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
    val attention = state.needsAttention()

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
    val colorGreen = if (attention) colors.warningAmber else Color(0xFF10B981)
    val colorPurple = if (attention) colors.warningAmber else Color(0xFF8B5CF6)
    val colorCoral = if (attention) colors.warningAmber else Color(0xFFEF4444)
    val colorAmber = if (attention) colors.warningAmber else Color(0xFFF59E0B)
    val colorBlue = if (attention) colors.warningAmber else Color(0xFF2563EB)
    val colorCyan = if (attention) colors.warningAmber else Color(0xFF06B6D4)

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
                    Color(0xFFE11D48).copy(alpha = 0.26f * overallAlpha),
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

@Composable
private fun ClosePillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Close",
) {
    val colors = LocalAssistantColors.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.composerBackground.copy(
            alpha = if (colors.isDark) 0.95f else 0.92f,
        ),
        border = BorderStroke(
            0.8.dp,
            colors.borderColor.copy(alpha = if (colors.isDark) 0.85f else 0.9f),
        ),
        modifier = modifier.semantics { contentDescription = "$label assistant card" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(com.phonecontrol.assistant.R.drawable.ic_close),
                contentDescription = null,
                tint = colors.textPrimary.copy(alpha = 0.85f),
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

@Composable
private fun FloatingResultCard(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val cardShape = RoundedCornerShape(28.dp)
    val compactResult = remember(message) {
        val trimmed = message.trim()
        trimmed.isNotEmpty() && !trimmed.contains('\n') && trimmed.length <= 120
    }
    val resultScrollState = rememberScrollState()
    val showResultTopFade by remember {
        derivedStateOf { resultScrollState.value > 0 }
    }
    val showResultBottomFade by remember {
        derivedStateOf { resultScrollState.value < resultScrollState.maxValue }
    }
    val resultSurfaceColor = colors.composerBackground.copy(
        alpha = if (colors.isDark) 0.98f else 0.97f,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        ClosePillButton(
            onClick = onDismiss,
            modifier = Modifier.padding(bottom = 4.dp, end = 6.dp),
            label = "Close",
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = colors.composerBackground.copy(
                alpha = if (colors.isDark) 0.98f else 0.97f,
            ),
            border = BorderStroke(
                width = 0.8.dp,
                color = colors.borderColor.copy(alpha = if (colors.isDark) 0.9f else 0.95f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 360.dp),
                ) {
                    // The scroll container is deliberately separate from the
                    // fade layer. A draw modifier on verticalScroll can be
                    // clipped by the scroll viewport before its gradient is
                    // composited, leaving the old squared-off crop.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 360.dp)
                            .verticalScroll(resultScrollState),
                        contentAlignment = if (compactResult) Alignment.Center else Alignment.TopStart,
                    ) {
                        MarkdownContent(
                            markdown = message,
                            modifier = Modifier.fillMaxWidth(),
                            baseTextStyle = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 16.sp,
                                lineHeight = 23.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                        )
                    }

                    if (showResultTopFade) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            resultSurfaceColor,
                                            resultSurfaceColor.copy(alpha = 0.84f),
                                            Color.Transparent,
                                        ),
                                    ),
                                ),
                        )
                    }
                    if (showResultBottomFade) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            resultSurfaceColor.copy(alpha = 0.84f),
                                            resultSurfaceColor,
                                        ),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingRecoveryCard(
    kind: OverlayRecoveryKind,
    state: SessionState,
    developerStatus: DeveloperModeStatus,
    onAcknowledgeAttention: () -> Boolean,
    onStop: () -> Unit,
    onOpenCompanion: () -> Unit,
    onOpenPhoneAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val cardShape = RoundedCornerShape(28.dp)
    val title: String
    val detail: String
    val icon: Int
    val primaryLabel: String
    val primaryAction: () -> Unit
    val secondaryLabel: String?
    val secondaryAction: (() -> Unit)?

    when (kind) {
        OverlayRecoveryKind.ATTENTION -> {
            val attentionActionLabel = state.attentionActionLabelOrNull()
            val phoneAccessRecovery = attentionActionLabel.equals("View instructions", ignoreCase = true)
            title = if (phoneAccessRecovery) {
                developerStatus.recoveryTitle
            } else {
                "DHD needs your attention"
            }
            detail = if (phoneAccessRecovery) {
                developerStatus.recoveryDetail
            } else {
                state.attentionReasonOrNull()
                    ?: "Review the phone and complete the requested step before continuing."
            }
            icon = com.phonecontrol.assistant.R.drawable.ic_info
            primaryLabel = if (phoneAccessRecovery) "View instructions" else attentionActionLabel ?: "Done"
            primaryAction = if (phoneAccessRecovery) onOpenPhoneAccess else { { onAcknowledgeAttention() } }
            secondaryLabel = if (phoneAccessRecovery) null else "Stop"
            secondaryAction = if (phoneAccessRecovery) null else onStop
        }

        OverlayRecoveryKind.COMPANION -> {
            title = "Desktop companion not connected"
            detail = "DHD is waiting for the desktop companion. Connect this phone on your local network."
            icon = com.phonecontrol.assistant.R.drawable.ic_laptop
            primaryLabel = "View connection instructions"
            primaryAction = onOpenCompanion
            secondaryLabel = null
            secondaryAction = null
        }

        OverlayRecoveryKind.DEVELOPER -> {
            title = developerStatus.recoveryTitle
            detail = developerStatus.recoveryDetail
            icon = com.phonecontrol.assistant.R.drawable.ic_shield
            primaryLabel = "View instructions"
            primaryAction = onOpenPhoneAccess
            secondaryLabel = null
            secondaryAction = null
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        color = colors.composerBackground.copy(
            alpha = if (colors.isDark) 0.98f else 0.97f,
        ),
        border = BorderStroke(
            width = 0.8.dp,
            color = colors.borderColor.copy(alpha = if (colors.isDark) 0.9f else 0.95f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = colors.warningAmber,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = detail,
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 30.dp, top = 6.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 30.dp, top = 12.dp),
            ) {
                RecoveryActionButton(
                    label = primaryLabel,
                    onClick = primaryAction,
                    primary = true,
                )
                if (secondaryLabel != null && secondaryAction != null) {
                    RecoveryActionButton(
                        label = secondaryLabel,
                        onClick = secondaryAction,
                        primary = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecoveryActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    val colors = LocalAssistantColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (primary) colors.warningAmber else Color.Transparent,
        border = if (primary) null else BorderStroke(1.dp, colors.borderColor),
    ) {
        Text(
            text = label,
            color = if (primary) Color.White else colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun FloatingVirtualDisplayCard(
    previewState: TaskPreviewState,
    taskDisplaySession: TaskDisplaySession?,
    pointerEvent: TaskPointerEvent?,
    onHide: () -> Unit,
    onContinue: () -> Unit,
    onSurfaceAvailable: (TaskDisplaySession, AndroidSurface) -> Unit,
    onSurfaceDestroyed: (TaskDisplaySession, AndroidSurface, () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val cardShape = liveDisplayCornerShape()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        ClosePillButton(
            onClick = onHide,
            modifier = Modifier.padding(bottom = 4.dp, end = 6.dp),
            label = "Close",
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = colors.composerBackground.copy(
                alpha = if (colors.isDark) 0.98f else 0.97f,
            ),
            border = BorderStroke(
                width = 0.8.dp,
                color = colors.borderColor.copy(alpha = if (colors.isDark) 0.9f else 0.95f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                OverlayVirtualDisplayPreview(
                    previewState = previewState,
                    taskDisplaySession = taskDisplaySession,
                    pointerEvent = pointerEvent,
                    onHide = onHide,
                    onContinue = onContinue,
                    onSurfaceAvailable = onSurfaceAvailable,
                    onSurfaceDestroyed = onSurfaceDestroyed,
                )
            }
        }
    }
}

@Composable
fun OverlayPanel(
    sessionState: StateFlow<SessionState>,
    toolCalls: StateFlow<List<DhdToolCall>>,
    panelMode: StateFlow<OverlayPanelMode>,
    resultMessage: StateFlow<String?>,
    developerStatus: StateFlow<DeveloperModeStatus>,
    companionConnected: StateFlow<Boolean>,
    pointerEvent: StateFlow<TaskPointerEvent?>,
    onExpand: () -> Unit,
    onNewRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onBubbleDragEnd: () -> Unit,
    onStop: () -> Unit,
    onAcknowledgeAttention: () -> Boolean,
    onOpenPhoneAccess: () -> Unit,
    onOpenCompanion: () -> Unit,
    onContinueInDhd: () -> Unit,
    onCollapse: () -> Unit,
    onDismissResult: () -> Unit = {},
    onHorizontalSwipeDismiss: (OverlaySwipeDirection, Int) -> Unit,
    taskPreviewState: StateFlow<TaskPreviewState>,
    taskDisplaySession: StateFlow<TaskDisplaySession?>,
    overlayHidden: StateFlow<Boolean>,
    onKeyboardVisibilityChanged: (Boolean) -> Unit,
    onTextFieldFocusChanged: (Boolean) -> Unit,
    onComposerTapped: () -> Unit,
    onTaskPreviewSurfaceAvailable: (TaskDisplaySession, AndroidSurface) -> Unit,
    onTaskPreviewSurfaceDestroyed: (TaskDisplaySession, AndroidSurface, () -> Unit) -> Unit,
) {
    val state by sessionState.collectAsState()
    val calls by toolCalls.collectAsState()
    val mode by panelMode.collectAsState()
    val result by resultMessage.collectAsState()
    val previewState by taskPreviewState.collectAsState()
    val activeDisplaySession by taskDisplaySession.collectAsState()
    val isOverlayHidden by overlayHidden.collectAsState()
    val currentDeveloperStatus by developerStatus.collectAsState()
    val isCompanionConnected by companionConnected.collectAsState()
    val latestPointerEvent by pointerEvent.collectAsState()
    val active = state is SessionState.Running || state is SessionState.Paused
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(OverlayPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    var previewVisible by rememberSaveable { mutableStateOf(false) }
    var fastMode by rememberSaveable {
        mutableStateOf(preferences.getBoolean(OverlayPreferences.KEY_FAST_MODE, false))
    }
    var reasoningEffortValue by rememberSaveable {
        mutableStateOf(
            ReasoningEffort.fromStorage(
                preferences.getString(
                    OverlayPreferences.KEY_REASONING_EFFORT,
                    ReasoningEffort.default.storageValue,
                ),
            ).storageValue,
        )
    }
    val visibleReasoningEfforts = remember(
        preferences.getString(OverlayPreferences.KEY_VISIBLE_REASONING_EFFORTS, null),
    ) {
        reasoningEffortsFromStorage(
            preferences.getString(OverlayPreferences.KEY_VISIBLE_REASONING_EFFORTS, null),
        )
    }
    val reasoningEffort = ReasoningEffort.fromStorage(reasoningEffortValue)
        .takeIf { it in visibleReasoningEfforts }
        ?: visibleReasoningEfforts.first()
    LaunchedEffect(reasoningEffortValue, reasoningEffort, visibleReasoningEfforts) {
        if (reasoningEffortValue != reasoningEffort.storageValue) {
            reasoningEffortValue = reasoningEffort.storageValue
            preferences.edit()
                .putString(OverlayPreferences.KEY_REASONING_EFFORT, reasoningEffort.storageValue)
                .apply()
        }
    }
    val setFastMode: (Boolean) -> Unit = { enabled ->
        fastMode = enabled
        preferences.edit().putBoolean(OverlayPreferences.KEY_FAST_MODE, enabled).apply()
    }
    val setReasoningEffort: (ReasoningEffort) -> Unit = { effort ->
        if (effort in visibleReasoningEfforts) {
            reasoningEffortValue = effort.storageValue
            preferences.edit().putString(OverlayPreferences.KEY_REASONING_EFFORT, effort.storageValue).apply()
        }
    }

    val recoveryKind = overlayRecoveryKind(
        state = state,
        developerStatus = currentDeveloperStatus,
        companionConnected = isCompanionConnected,
    )
    val hasRecovery = state.needsAttention() || recoveryKind != null

    val effectiveMode = effectiveOverlayPanelMode(mode, active, hasRecovery)

    val focusManager = LocalFocusManager.current
    LaunchedEffect(effectiveMode) {
        if (effectiveMode == OverlayPanelMode.BUBBLE) {
            focusManager.clearFocus(force = true)
        }
    }

    if (effectiveMode == OverlayPanelMode.BUBBLE) {
        BubbleButton(
            onClick = onExpand,
            onDrag = onDrag,
            onDragEnd = onBubbleDragEnd,
            running = state is SessionState.Running || state is SessionState.Paused,
            attention = hasRecovery,
            animated = state is SessionState.Running && !hasRecovery,
        )
        return
    }

    val colors = LocalAssistantColors.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboardVisible = imeBottom > with(density) { 96.dp.toPx() }

    LaunchedEffect(keyboardVisible) {
        onKeyboardVisibilityChanged(keyboardVisible)
    }

    val resultText = result?.takeIf { it.isNotBlank() } ?: terminalMessage(state)
    val hasFloatingResult = effectiveMode == OverlayPanelMode.RESULT && resultText.isNotBlank()
    val hasFloatingRecovery = recoveryKind != null && effectiveMode != OverlayPanelMode.BUBBLE
    // The composer geometry is independent from the optional cards above it.
    // Opening or closing a card must not change the parent padding/width that
    // anchors the composer window.
    val composerCollapsed = !keyboardVisible

    val bottomCapsuleShape = when {
        effectiveMode == OverlayPanelMode.WORKING || effectiveMode == OverlayPanelMode.ATTENTION -> CircleShape
        !keyboardVisible -> CircleShape
        else -> RoundedCornerShape(28.dp)
    }

    val panelDescription = when (effectiveMode) {
        OverlayPanelMode.COMPOSER -> "DHD assistant. Ready for a request."
        OverlayPanelMode.RESULT -> "DHD assistant. Result ready."
        OverlayPanelMode.ATTENTION -> "DHD assistant needs your attention."
        OverlayPanelMode.WORKING -> "DHD assistant is working."
        OverlayPanelMode.BUBBLE -> "DHD assistant."
    }

    // Keep the content lane wide even when the visible composer is the
    // compact 320.dp pill. This is the expanded-composer width used by both
    // floating cards in every keyboard/IME state.
    val horizontalPadding = 14.dp
    val bottomPadding = when {
        composerCollapsed -> 10.dp
        keyboardVisible -> 20.dp
        else -> 16.dp
    }
    var composerOriginX by remember { mutableStateOf(0) }
    val latestComposerOriginX by rememberUpdatedState(composerOriginX)
    val swipeDismissThresholdPx = with(density) { 56.dp.toPx() }
    val swipeDismissModifier = if (effectiveMode != OverlayPanelMode.BUBBLE) {
        Modifier.pointerInput(onCollapse, onHorizontalSwipeDismiss) {
            var totalDragX = 0f
            var totalDragY = 0f
            var lastPointerX = 0f
            detectDragGestures(
                onDragStart = { startOffset ->
                    totalDragX = 0f
                    totalDragY = 0f
                    lastPointerX = startOffset.x
                },
                onDrag = { change, dragAmount ->
                    totalDragX += dragAmount.x
                    totalDragY += dragAmount.y
                    lastPointerX = change.position.x
                    change.consume()
                },
                onDragEnd = {
                    val horizontalSwipe = abs(totalDragX) >= swipeDismissThresholdPx &&
                        abs(totalDragX) > abs(totalDragY)
                    val downwardSwipe = totalDragY >= swipeDismissThresholdPx &&
                        totalDragY > abs(totalDragX)
                    if (horizontalSwipe) {
                        onHorizontalSwipeDismiss(
                            if (totalDragX < 0f) {
                                OverlaySwipeDirection.LEFT
                            } else {
                                OverlaySwipeDirection.RIGHT
                            },
                            latestComposerOriginX + lastPointerX.roundToInt(),
                        )
                    } else if (downwardSwipe) {
                        onCollapse()
                    }
                },
            )
        }
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 10.dp,
                bottom = bottomPadding,
            )
            .semantics { contentDescription = panelDescription },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val cardWidth = maxWidth.coerceAtMost(520.dp)
        val composerWidth = if (composerCollapsed) {
            320.dp.coerceAtMost(maxWidth)
        } else {
            cardWidth
        }
        var composerHeightPx by remember { mutableStateOf(0) }
        var resultCardHeightPx by remember { mutableStateOf(0) }
        var recoveryCardHeightPx by remember { mutableStateOf(0) }
        val fallbackComposerHeightPx = with(density) { 82.dp.roundToPx() }
        val popupBottomOffsetPx = (composerHeightPx.takeIf { it > 0 } ?: fallbackComposerHeightPx) +
            with(density) { bottomPadding.roundToPx() + 4.dp.roundToPx() }
        val popupSpacingPx = with(density) { 10.dp.roundToPx() }

        // Keep these cards in their own popup windows. They are visually
        // anchored above the composer, but do not take part in measuring the
        // composer window, so opening/closing either card cannot move it.
        if (hasFloatingResult) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -popupBottomOffsetPx),
                onDismissRequest = {},
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    clippingEnabled = false,
                ),
            ) {
                FloatingResultCard(
                    message = resultText,
                    onDismiss = onDismissResult,
                    modifier = Modifier
                        .width(cardWidth)
                        .onGloballyPositioned { resultCardHeightPx = it.size.height },
                )
            }
        }

        if (effectiveMode != OverlayPanelMode.BUBBLE) {
            when (val recovery = recoveryKind) {
                null -> Unit
                else -> Popup(
                    alignment = Alignment.BottomCenter,
                    offset = IntOffset(
                        0,
                        -(popupBottomOffsetPx +
                            if (hasFloatingResult) resultCardHeightPx + popupSpacingPx else 0),
                    ),
                    onDismissRequest = {},
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                        clippingEnabled = false,
                    ),
                ) {
                    FloatingRecoveryCard(
                        kind = recovery,
                        state = state,
                        developerStatus = currentDeveloperStatus,
                        onAcknowledgeAttention = onAcknowledgeAttention,
                        onStop = onStop,
                        onOpenCompanion = onOpenCompanion,
                        onOpenPhoneAccess = onOpenPhoneAccess,
                        modifier = Modifier
                            .width(cardWidth)
                            .onGloballyPositioned { recoveryCardHeightPx = it.size.height },
                    )
                }
            }
        }

        // The overlay window can be GONE while its composition survives an
        // Activity handoff. Remove the TextureView while hidden so the
        // session's single decoder target is released; recreating it on
        // reveal receives a fresh surface and cannot show frozen pixels from
        // the inline/fullscreen viewer.
        val displaySession = activeDisplaySession ?: previewState.sessionOrNull()
        if (shouldRenderOverlayPreview(
                previewVisible = previewVisible,
                overlayHidden = isOverlayHidden,
                hasDisplaySession = displaySession != null,
            )) {
            val displayPointerEvent = latestPointerEvent?.takeIf { event ->
                event.sessionId == state.sessionIdOrNull() ||
                    event.sessionId == displaySession?.sessionKey
            }
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(
                    0,
                    -(popupBottomOffsetPx +
                        (if (hasFloatingResult) resultCardHeightPx + popupSpacingPx else 0) +
                        (if (hasFloatingRecovery) recoveryCardHeightPx + popupSpacingPx else 0)),
                ),
                onDismissRequest = {},
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    clippingEnabled = false,
                ),
            ) {
                FloatingVirtualDisplayCard(
                    previewState = previewState,
                    taskDisplaySession = displaySession,
                    pointerEvent = displayPointerEvent,
                    onHide = { previewVisible = false },
                    onContinue = onContinueInDhd,
                    onSurfaceAvailable = onTaskPreviewSurfaceAvailable,
                    onSurfaceDestroyed = onTaskPreviewSurfaceDestroyed,
                    modifier = Modifier.width(cardWidth),
                )
            }
        }

        val containerWidthModifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)

        Column(
            modifier = containerWidthModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.width(composerWidth),
                contentAlignment = Alignment.Center,
            ) {
                if (effectiveMode == OverlayPanelMode.COMPOSER || effectiveMode == OverlayPanelMode.RESULT) {
                    ComposerPerimeterGlow(
                        modifier = Modifier.matchParentSize(),
                        hasRecovery = hasRecovery,
                        capsule = bottomCapsuleShape == CircleShape,
                    )
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(swipeDismissModifier)
                        .onGloballyPositioned { coordinates ->
                            composerOriginX = coordinates.positionInRoot().x.roundToInt()
                            composerHeightPx = coordinates.size.height
                        },
                    shape = bottomCapsuleShape,
                    color = colors.composerBackground.copy(
                        alpha = if (colors.isDark) 0.98f else 0.97f,
                    ),
                    border = BorderStroke(
                        width = 0.8.dp,
                        color = colors.borderColor.copy(alpha = if (colors.isDark) 0.9f else 0.95f),
                    ),
                ) {
                    when (effectiveMode) {
                        OverlayPanelMode.COMPOSER,
                        OverlayPanelMode.RESULT -> Composer(
                            onSubmit = onSubmit,
                            onContinueInDhd = onContinueInDhd,
                            onCollapse = onCollapse,
                            onShowPreview = { previewVisible = true },
                            fastMode = fastMode,
                            onSetFastMode = setFastMode,
                            reasoningEffort = reasoningEffort,
                            visibleReasoningEfforts = visibleReasoningEfforts,
                            onSelectReasoningEffort = setReasoningEffort,
                            onTextFieldFocusChanged = onTextFieldFocusChanged,
                            onComposerTapped = onComposerTapped,
                        )
                        OverlayPanelMode.WORKING,
                        OverlayPanelMode.ATTENTION -> WorkingRow(
                            state = state,
                            calls = calls,
                            recoveryKind = recoveryKind,
                            onStop = onStop,
                            onContinueInDhd = onContinueInDhd,
                            onCollapse = onCollapse,
                            onShowPreview = { previewVisible = true },
                        )
                        OverlayPanelMode.BUBBLE -> Unit
                    }
                }
            }
        }
    }
}

/** Compose the preview only while requested, visible, and backed by a session. */
internal fun shouldRenderOverlayPreview(
    previewVisible: Boolean,
    overlayHidden: Boolean,
    hasDisplaySession: Boolean = true,
): Boolean = previewVisible && !overlayHidden && hasDisplaySession

private fun TaskPreviewState.sessionOrNull(): TaskDisplaySession? = when (this) {
    is TaskPreviewState.Connecting -> session
    is TaskPreviewState.Attached -> session
    is TaskPreviewState.Ended -> null
    is TaskPreviewState.Error -> null
    TaskPreviewState.Detached -> null
}

@Composable
private fun PanelHeader(
    mode: OverlayPanelMode,
    orbWorking: Boolean,
    orbAnimated: Boolean,
    onCollapse: () -> Unit,
    onIdentityClick: () -> Unit,
) {
    val showIdentity = mode != OverlayPanelMode.COMPOSER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (showIdentity) 16.dp else 4.dp,
        end = 4.dp,
                top = 2.dp,
                bottom = 0.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIdentity) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Continue in DHD",
                        onClick = onIdentityClick,
                    )
                    .semantics { contentDescription = "Continue in DHD" },
                contentAlignment = Alignment.Center,
            ) {
                DhdIdentity(
                    modifier = Modifier.fillMaxSize(),
                    working = orbWorking,
                    animated = orbAnimated,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "dhd",
                color = LocalAssistantColors.current.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        GlyphButton(
            label = "Minimize assistant",
            glyph = "down",
            onClick = onCollapse,
        )
    }
}

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
internal fun DhdComposerPreview(
    modifier: Modifier = Modifier,
    onCollapse: () -> Unit,
    onSwipeCollapse: (swipedLeft: Boolean) -> Unit,
    onShowPreview: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val density = LocalDensity.current
    val collapseThreshold = with(density) { 48.dp.toPx() }
    Box(
        modifier = modifier
            // Keep swipe-to-collapse at the container level so it can coexist
            // with the logo's tap and long-press gestures without a full-card
            // clickable indication covering the composer.
            .pointerInput(onCollapse) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        totalDrag += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        if (kotlin.math.abs(totalDrag) >= collapseThreshold) {
                            onSwipeCollapse(totalDrag < 0f)
                        }
                    },
                    onDragCancel = { totalDrag = 0f },
                )
            }
            .semantics {
                contentDescription = "Swipe left to dock the bubble on the left or right to dock it on the right. Long press the DHD logo to show or hide the virtual display preview."
            },
    ) {
        ComposerPerimeterGlow(
            modifier = Modifier.matchParentSize(),
            hasRecovery = false,
            capsule = true,
        )
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = colors.composerBackground.copy(alpha = if (colors.isDark) 0.98f else 0.97f),
            border = BorderStroke(
                width = 0.8.dp,
                color = colors.borderColor.copy(alpha = if (colors.isDark) 0.9f else 0.95f),
            ),
        ) {
            Composer(
                onSubmit = {},
                onContinueInDhd = {},
                onCollapse = onCollapse,
                onShowPreview = onShowPreview,
                onTextFieldFocusChanged = {},
                onComposerTapped = {},
                fastMode = false,
                onSetFastMode = {},
                reasoningEffort = ReasoningEffort.default,
                visibleReasoningEfforts = ReasoningEffort.entries,
                onSelectReasoningEffort = {},
                previewReadOnly = true,
            )
        }
    }
}

@Composable
private fun ComposerPerimeterGlow(
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
    val glowBase = if (colors.isDark) Color.White else Color(0xFF1E293B)
    val glowAccent = if (colors.isDark) Color(0xFF93C5FD) else Color(0xFF3B82F6)
    val chromaticColors = if (hasRecovery) {
        listOf(
            colors.warningAmber,
            Color(0xFFFBBF24),
            colors.warningAmber,
            Color(0xFFFBBF24),
            colors.warningAmber,
            Color(0xFFFBBF24),
        )
    } else {
        listOf(
            Color(0xFF10B981),
            Color(0xFF06B6D4),
            Color(0xFF3B82F6),
            Color(0xFF8B5CF6),
            Color(0xFFEF4444),
            Color(0xFFF59E0B),
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

@Composable
private fun BubbleButton(
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
private fun DhdIdentity(
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

@Composable
private fun Composer(
    onSubmit: (String) -> Unit,
    onContinueInDhd: () -> Unit,
    onCollapse: () -> Unit,
    onShowPreview: () -> Unit,
    onTextFieldFocusChanged: (Boolean) -> Unit,
    onComposerTapped: () -> Unit,
    fastMode: Boolean,
    onSetFastMode: (Boolean) -> Unit,
    reasoningEffort: ReasoningEffort,
    visibleReasoningEfforts: List<ReasoningEffort>,
    onSelectReasoningEffort: (ReasoningEffort) -> Unit,
    hint: String = "Ask DHD",
    previewReadOnly: Boolean = false,
) {
    val colors = LocalAssistantColors.current
    var draft by rememberSaveable { mutableStateOf("") }
    var reasoningSelectorOpen by rememberSaveable { mutableStateOf(false) }
    var focusRequestToken by remember { mutableStateOf(0) }
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current
    val textToolbar = remember(clipboardManager) {
        OverlayTextToolbar(clipboardManager) { clipText ->
            draft = (draft + clipText).take(MAX_DRAFT_LENGTH)
        }
    }
    val imeBottom = WindowInsets.ime.getBottom(density)
    // Do not let the row appear during the IME's first few animation frames.
    // That intermediate layout pass was what made the overlay bounce while the
    // keyboard was opening and closing.
    val keyboardVisible = imeBottom > with(density) { 96.dp.toPx() }

    LaunchedEffect(keyboardVisible) {
        if (!keyboardVisible) {
            reasoningSelectorOpen = false
            textToolbar.hide()
        }
    }

    LaunchedEffect(focusRequestToken) {
        if (focusRequestToken == 0) return@LaunchedEffect
        // The overlay window becomes focusable in response to the same touch
        // that reached this field. Retry across a few frames so the request is
        // made after WindowManager has granted the overlay window focus.
        repeat(6) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }

    fun submit() {
        textToolbar.hide()
        val request = draft.trim()
        if (request.isEmpty()) return
        draft = ""
        focus.clearFocus()
        onSubmit(request)
    }

    CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (keyboardVisible) 14.dp else 12.dp,
                        end = if (keyboardVisible) 14.dp else 12.dp,
                        top = if (keyboardVisible) 14.dp else 12.dp,
                        bottom = if (keyboardVisible) 8.dp else 12.dp,
                    ),
                verticalAlignment = if (keyboardVisible) Alignment.Top else Alignment.CenterVertically,
            ) {
            Box(
                modifier = Modifier
                    .size(if (keyboardVisible) 42.dp else 48.dp)
                    .pointerInput(onContinueInDhd, onCollapse, onShowPreview) {
                        detectTapGestures(
                            onTap = { onContinueInDhd() },
                            onDoubleTap = {
                                focus.clearFocus(force = true)
                                onCollapse()
                            },
                            onLongPress = { onShowPreview() },
                        )
                    }
                    .semantics {
                        contentDescription =
                            "DHD. Tap to continue in DHD, double tap to collapse, long press to show the virtual display."
                    },
                contentAlignment = Alignment.Center,
            ) {
                DhdIdentity(
                    modifier = Modifier.fillMaxSize(),
                    working = false,
                    // Keep the idle composer static. The working perimeter
                    // glow carries the motion; a continuously invalidated
                    // icon competes with the IME and live preview for frames.
                    animated = false,
                )
            }
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(MAX_DRAFT_LENGTH) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .pointerInput(onComposerTapped) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onComposerTapped()
                            focusRequestToken += 1
                        }
                    }
                    .onFocusChanged { onTextFieldFocusChanged(it.isFocused) }
                    .then(
                        if (keyboardVisible) {
                            Modifier.heightIn(min = 38.dp, max = 126.dp)
                        } else {
                            Modifier
                        }
                    )
                .semantics { contentDescription = "Message DHD assistant" },
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
                cursorBrush = Brush.verticalGradient(
                    listOf(colors.accentBlue, colors.accentBlue.copy(alpha = 0.55f)),
                ),
                minLines = 1,
                maxLines = if (keyboardVisible) 4 else 1,
                readOnly = previewReadOnly,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { field ->
                    Box(
                        contentAlignment = if (keyboardVisible) Alignment.TopStart else Alignment.CenterStart,
                        modifier = if (keyboardVisible) Modifier.padding(top = 8.dp) else Modifier,
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                text = hint,
                                color = colors.textSecondary,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                            )
                        }
                        field()
                    }
                },
            )
            if (!keyboardVisible) {
                Spacer(Modifier.width(8.dp))
                GlyphButton(
                    label = "Send request",
                    glyph = "send",
                    onClick = ::submit,
                    enabled = draft.isNotBlank(),
                    filled = true,
                    buttonSize = 48.dp,
                    iconSize = 24.dp,
                )
            }
        }

        if (keyboardVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                FastModeButton(
                    enabled = true,
                    selected = fastMode,
                    onToggle = { onSetFastMode(!fastMode) },
                )
                Spacer(Modifier.width(8.dp))
                ReasoningEffortButton(
                    effort = reasoningEffort,
                    visibleEfforts = visibleReasoningEfforts,
                    enabled = true,
                    expanded = reasoningSelectorOpen,
                    onClick = { reasoningSelectorOpen = !reasoningSelectorOpen },
                )
                Spacer(Modifier.width(10.dp))
                GlyphButton(
                    label = "Send request",
                    glyph = "send",
                    onClick = ::submit,
                    enabled = draft.isNotBlank(),
                    filled = true,
                    buttonSize = 36.dp,
                    iconSize = 18.dp,
                )
            }
            if (reasoningSelectorOpen) {
                // This is deliberately a non-focusable popup. A selector that
                // participates in the composer layout changes the overlay
                // window height and Android responds by dismissing/reopening
                // the IME. Keeping it in its own touchable window preserves
                // both the keyboard and the compact composer geometry.
                Popup(
                    alignment = Alignment.BottomEnd,
                    offset = with(density) {
                        IntOffset(0, -56.dp.roundToPx())
                    },
                    onDismissRequest = { reasoningSelectorOpen = false },
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnBackPress = false,
                        dismissOnClickOutside = true,
                        clippingEnabled = false,
                    ),
                ) {
                    Surface(
                        modifier = Modifier.width(286.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = colors.composerBackground,
                        border = androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            colors.borderColor,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                start = 14.dp,
                                top = 12.dp,
                                end = 14.dp,
                                bottom = 12.dp,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${reasoningEffort.label} reasoning",
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.weight(1f))
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = "Close reasoning selector",
                                            onClick = { reasoningSelectorOpen = false },
                                        )
                                        .semantics { contentDescription = "Close reasoning selector" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "×",
                                        color = colors.textSecondary,
                                        fontSize = 18.sp,
                                        lineHeight = 18.sp,
                                    )
                                }
                            }
                            ReasoningEffortTrack(
                                selectedEffort = reasoningEffort,
                                visibleEfforts = visibleReasoningEfforts,
                                onSelect = {
                                    onSelectReasoningEffort(it)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    textToolbar.currentMenu?.let { menu ->
            TextToolbarPopup(menu = menu, onDismiss = { textToolbar.hide() })
        }
    }
}

internal data class TextToolbarMenu(
    val rect: Rect,
    val onCopy: (() -> Unit)?,
    val onPaste: (() -> Unit)?,
    val onCut: (() -> Unit)?,
    val onSelectAll: (() -> Unit)?,
)

internal class OverlayTextToolbar(
    private val clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    private val onFallbackPaste: (String) -> Unit,
) : TextToolbar {
    var currentMenu by mutableStateOf<TextToolbarMenu?>(null)
        private set

    override val status: TextToolbarStatus
        get() = if (currentMenu != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val hasClip = clipboardManager.hasText()
        val pasteAction = onPasteRequested ?: if (hasClip) {
            {
                val text = clipboardManager.getText()?.text
                if (!text.isNullOrEmpty()) {
                    onFallbackPaste(text)
                }
            }
        } else null

        currentMenu = TextToolbarMenu(
            rect = rect,
            onCopy = onCopyRequested,
            onPaste = pasteAction,
            onCut = onCutRequested,
            onSelectAll = onSelectAllRequested,
        )
    }

    override fun hide() {
        currentMenu = null
    }
}

@Composable
private fun TextToolbarPopup(
    menu: TextToolbarMenu,
    onDismiss: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val density = LocalDensity.current

    val hasActions = menu.onCut != null || menu.onCopy != null || menu.onPaste != null || menu.onSelectAll != null
    if (!hasActions) return

    val toolbarHeight = with(density) { 48.dp.roundToPx() }
    val spacingPx = with(density) { 8.dp.roundToPx() }
    val yOffset = if (menu.rect.top > toolbarHeight + spacingPx) {
        (menu.rect.top - toolbarHeight - spacingPx).roundToInt()
    } else {
        (menu.rect.bottom + spacingPx).roundToInt()
    }.coerceAtLeast(with(density) { 6.dp.roundToPx() })
    val xOffset = (menu.rect.left - with(density) { 12.dp.roundToPx() }).roundToInt()
        .coerceAtLeast(with(density) { 10.dp.roundToPx() })

    Popup(
        offset = IntOffset(xOffset, yOffset),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.composerBackground,
            border = androidx.compose.foundation.BorderStroke(0.8.dp, colors.borderColor),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var needsDivider = false
                if (menu.onCut != null) {
                    TextToolbarItem("Cut") {
                        menu.onCut.invoke()
                        onDismiss()
                    }
                    needsDivider = true
                }
                if (menu.onCopy != null) {
                    if (needsDivider) ToolbarDivider()
                    TextToolbarItem("Copy") {
                        menu.onCopy.invoke()
                        onDismiss()
                    }
                    needsDivider = true
                }
                if (menu.onPaste != null) {
                    if (needsDivider) ToolbarDivider()
                    TextToolbarItem("Paste") {
                        menu.onPaste.invoke()
                        onDismiss()
                    }
                    needsDivider = true
                }
                if (menu.onSelectAll != null) {
                    if (needsDivider) ToolbarDivider()
                    TextToolbarItem("Select all") {
                        menu.onSelectAll.invoke()
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarDivider() {
    val colors = LocalAssistantColors.current
    Box(
        modifier = Modifier
            .width(0.8.dp)
            .height(16.dp)
            .background(colors.borderColor.copy(alpha = 0.7f)),
    )
}

@Composable
private fun TextToolbarItem(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun OverlayVirtualDisplayPreview(
    previewState: TaskPreviewState,
    taskDisplaySession: TaskDisplaySession?,
    pointerEvent: TaskPointerEvent?,
    onHide: () -> Unit,
    onContinue: () -> Unit,
    onSurfaceAvailable: (TaskDisplaySession, AndroidSurface) -> Unit,
    onSurfaceDestroyed: (TaskDisplaySession, AndroidSurface, () -> Unit) -> Unit,
) {
    val colors = LocalAssistantColors.current
    // The backend keeps the display session alive while its decoder Surface is
    // detached during an Activity/overlay handoff. Use that session as the
    // identity for composition; previewState only describes playback. If the
    // state is Detached, composing a connecting preview is what creates the
    // replacement TextureView and lets the surface callback reattach it.
    val session = taskDisplaySession ?: previewState.sessionOrNull()
    val livePreview = session?.let { displaySession ->
        when (previewState) {
            is TaskPreviewState.Error,
            is TaskPreviewState.Ended,
            -> null
            is TaskPreviewState.Attached -> LiveDisplayPreviewState.live(
                appLabel = displaySession.packageName.takeIf(String::isNotBlank),
                aspectRatio = displaySession.geometry.width.toFloat() /
                    displaySession.geometry.height.toFloat(),
                sessionKey = displaySession.sessionKey,
            )
            is TaskPreviewState.Connecting,
            TaskPreviewState.Detached,
            -> LiveDisplayPreviewState.connecting(
                appLabel = displaySession.packageName.takeIf(String::isNotBlank),
                aspectRatio = displaySession.geometry.width.toFloat() /
                    displaySession.geometry.height.toFloat(),
                sessionKey = displaySession.sessionKey,
            )
        }?.let { preview ->
            displaySession to preview.copy(pointerEvent = pointerEvent)
        }
    }
    val statusMessage = when (previewState) {
        TaskPreviewState.Detached -> "The virtual display will appear when a task opens one."
        is TaskPreviewState.Ended -> previewState.message
        is TaskPreviewState.Error -> previewState.message
        else -> null
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Live view",
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (livePreview != null) {
            val (displaySession, live) = livePreview
            LiveDisplayPreview(
                state = live,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 248.dp),
                onSurfaceAvailable = { surface -> onSurfaceAvailable(displaySession, surface) },
                onSurfaceDestroyed = { surface, release ->
                    onSurfaceDestroyed(displaySession, surface, release)
                },
                onExpand = onContinue,
                showCardChrome = true,
            )
        } else {
            Text(
                text = statusMessage ?: "No virtual display is attached yet.",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun BoxScope.GeminiHorizonGlow(attention: Boolean) {
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

@Composable
private fun rememberPreToolStatus(enabled: Boolean): String {
    var index by remember { mutableIntStateOf(Random.nextInt(THINKING_WORDS.size)) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            return@LaunchedEffect
        }
        while (true) {
            delay(4_000L)
            index = nextPreToolStatusIndex(index)
        }
    }

    return THINKING_WORDS[index]
}

private fun nextPreToolStatusIndex(previous: Int): Int {
    if (THINKING_WORDS.size < 2) return 0
    var next: Int
    do {
        next = Random.nextInt(THINKING_WORDS.size)
    } while (next == previous)
    return next
}

@Composable
private fun WorkingRow(
    state: SessionState,
    calls: List<DhdToolCall>,
    recoveryKind: OverlayRecoveryKind? = null,
    onStop: () -> Unit,
    onContinueInDhd: () -> Unit,
    onCollapse: () -> Unit,
    onShowPreview: (() -> Unit)? = null,
) {
    val colors = LocalAssistantColors.current
    val sessionCalls = workingRowSessionCalls(state, calls)
    val attention = state.needsAttention() || recoveryKind != null
    val preToolStatus = rememberPreToolStatus(
        enabled = sessionCalls.isEmpty() && !attention && state is SessionState.Running,
    )
    val activeTask = workingRowTask(state, sessionCalls, recoveryKind, preToolStatus)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        GeminiHorizonGlow(attention = attention)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pointerInput(onContinueInDhd, onCollapse, onShowPreview) {
                        detectTapGestures(
                            onTap = { onContinueInDhd() },
                            onDoubleTap = { onCollapse() },
                            onLongPress = { onShowPreview?.invoke() },
                        )
                    }
                .semantics {
                    contentDescription = if (attention) {
                        "DHD is waiting for recovery. Tap to continue in DHD, double tap to collapse."
                    } else {
                        "DHD is working. Tap to continue in DHD, double tap to collapse."
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                DhdIdentity(
                    modifier = Modifier.fillMaxSize(),
                    working = state is SessionState.Running && !attention,
                    attention = attention,
                    animated = state !is SessionState.Paused && !attention,
                )
            }

            Spacer(Modifier.width(10.dp))

            AnimatedContent(
                targetState = activeTask,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = tween(220)) { it / 3 })
                        .togetherWith(fadeOut(animationSpec = tween(140)) + slideOutVertically(animationSpec = tween(140)) { -it / 3 })
                },
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Continue in DHD",
                        onClick = onContinueInDhd,
                    )
                    .semantics { contentDescription = "Active task: $activeTask" },
                contentAlignment = Alignment.CenterStart,
                label = "working-task-progress",
            ) { text ->
                Text(
                    text = text,
                    color = if (attention) colors.warningAmber else Color.White,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Clip,
                )
            }

            Spacer(Modifier.width(10.dp))

            GlyphButton(
                label = "Stop assistant",
                glyph = "stop",
                onClick = onStop,
                filled = true,
                buttonSize = 44.dp,
                iconSize = 25.dp,
            )
        }
    }
}

@Composable
private fun WorkingContent(
    state: SessionState,
    calls: List<DhdToolCall>,
    onStop: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val sessionCalls = calls.filter {
        it.sessionId == state.sessionIdOrNull() &&
            !it.toolName.equals("dhd_close_display", ignoreCase = true) &&
            !it.toolName.equals("close_display", ignoreCase = true)
    }
    val attention = state.needsAttention()
    val paused = state is SessionState.Paused
    val current = sessionCalls.lastOrNull { it.status == DhdToolCallStatus.RUNNING }
        ?: sessionCalls.lastOrNull {
            attention && it.status == DhdToolCallStatus.ATTENTION
        }
    val preToolStatus = rememberPreToolStatus(
        enabled = sessionCalls.isEmpty() && !attention && !paused,
    )
    val purpose = current?.purpose
        ?: if (paused) "Paused" else sessionCalls.lastOrNull()?.purpose ?: preToolStatus
    val attentionReason = state.attentionReasonOrNull()
    val recent = sessionCalls
        .filter { it.id != current?.id && it.status != DhdToolCallStatus.RUNNING }
        .takeLast(3)
    val headline = when {
        attention -> "A moment for you"
        paused -> "Ready when you are"
        else -> purpose
    }
    val secondary = when {
        attention -> attentionReason ?: purpose
        paused -> purpose
        else -> null
    }
    val headlineColor = if (attention) colors.warningAmber else colors.textPrimary

    Column(
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 13.dp),
    ) {
        AnimatedContent(
            targetState = headline,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { 8 })
                    .togetherWith(fadeOut(tween(110)) + slideOutVertically(tween(110)) { -8 })
            },
            label = "live-purpose",
        ) { text ->
            Text(
                text = text,
                color = headlineColor,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.45).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, bottom = if (secondary == null) 8.dp else 5.dp),
            )
        }

        if (secondary != null) {
            Text(
                text = secondary,
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }

        if (recent.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                recent.forEach { call ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusMark(call.status, callTint(call.status, colors))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = call.purpose,
                            color = colors.textSecondary.copy(alpha = 0.82f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            GlyphButton(label = "Stop assistant", glyph = "stop", onClick = onStop)
        }
    }
}



@Composable
private fun StatusMark(status: DhdToolCallStatus, color: Color) {
    Canvas(Modifier.size(12.dp)) {
        when (status) {
            DhdToolCallStatus.COMPLETED -> {
                val path = Path().apply {
                    moveTo(size.width * 0.12f, size.height * 0.5f)
                    lineTo(size.width * 0.4f, size.height * 0.76f)
                    lineTo(size.width * 0.88f, size.height * 0.22f)
                }
                drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            }
            DhdToolCallStatus.FAILED -> {
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.25f, size.height * 0.25f),
                    end = Offset(size.width * 0.75f, size.height * 0.75f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.75f, size.height * 0.25f),
                    end = Offset(size.width * 0.25f, size.height * 0.75f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            DhdToolCallStatus.ATTENTION -> drawCircle(color, 2.8.dp.toPx())
            DhdToolCallStatus.RUNNING -> {
                drawCircle(color.copy(alpha = 0.28f), 4.2.dp.toPx())
                drawCircle(color, 2.1.dp.toPx())
            }
        }
    }
}

private fun callTint(status: DhdToolCallStatus, colors: AssistantColorScheme): Color = when (status) {
    DhdToolCallStatus.COMPLETED -> colors.accentGreen
    DhdToolCallStatus.FAILED -> colors.warningAmber
    DhdToolCallStatus.ATTENTION -> colors.warningAmber
    DhdToolCallStatus.RUNNING -> colors.accentBlue
}

private fun reasoningEffortsFromStorage(value: String?): List<ReasoningEffort> {
    val stored = value
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        .orEmpty()
    return ReasoningEffort.entries
        .filter { it.storageValue in stored }
        .ifEmpty { ReasoningEffort.entries }
}

@Composable
private fun GlyphButton(
    label: String,
    glyph: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false,
    buttonSize: androidx.compose.ui.unit.Dp = 48.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val colors = LocalAssistantColors.current
    val tint = when {
        glyph == "stop" -> Color(0xFF1E2B45).copy(alpha = 0.88f)
        filled && enabled -> colors.sendButtonActiveBg
        filled -> colors.sendButtonInactiveBg
        enabled -> colors.surfaceCard.copy(alpha = 0.78f)
        else -> colors.surfaceCard.copy(alpha = 0.45f)
    }
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(tint)
            .then(
                if (glyph == "stop") {
                    Modifier.border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f), CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (glyph == "send") {
            Icon(
                painter = painterResource(com.phonecontrol.assistant.R.drawable.ic_send_arrow),
                contentDescription = null,
                tint = if (enabled) colors.sendButtonActiveIcon else colors.sendButtonInactiveIcon,
                modifier = Modifier.size(iconSize),
            )
        } else Canvas(Modifier.size(iconSize)) {
            val color = when {
                !enabled -> colors.sendButtonInactiveIcon
                filled -> colors.sendButtonActiveIcon
                glyph == "stop" -> colors.textPrimary.copy(alpha = 0.78f)
                else -> colors.textSecondary
            }
            val path = Path()
            when (glyph) {
                "down" -> {
                    path.moveTo(size.width * 0.25f, size.height * 0.4f)
                    path.lineTo(size.width * 0.5f, size.height * 0.65f)
                    path.lineTo(size.width * 0.75f, size.height * 0.4f)
                    drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
                }
                "stop" -> drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    topLeft = Offset(size.width * 0.24f, size.height * 0.24f),
                    size = Size(size.width * 0.52f, size.height * 0.52f),
                    cornerRadius = CornerRadius(3.5.dp.toPx()),
                    style = Stroke(1.8.dp.toPx()),
                )
                else -> Unit
            }
        }
    }
}

internal fun effectiveOverlayPanelMode(
    mode: OverlayPanelMode,
    active: Boolean,
    hasRecovery: Boolean,
): OverlayPanelMode =
    // An explicit collapse request wins over the session state. The perimeter glow is separate.
    when {
        mode == OverlayPanelMode.BUBBLE -> OverlayPanelMode.BUBBLE
        active && hasRecovery -> OverlayPanelMode.ATTENTION
        active -> OverlayPanelMode.WORKING
        else -> mode
    }

internal fun workingRowSessionCalls(state: SessionState, calls: List<DhdToolCall>): List<DhdToolCall> =
    calls.filter {
        it.sessionId == state.sessionIdOrNull() &&
            !it.toolName.equals("dhd_close_display", ignoreCase = true) &&
            !it.toolName.equals("close_display", ignoreCase = true)
    }

internal fun workingRowTask(
    state: SessionState,
    sessionCalls: List<DhdToolCall>,
    recoveryKind: OverlayRecoveryKind?,
    preToolStatus: String,
): String {
    val runningCall = sessionCalls.lastOrNull { it.status == DhdToolCallStatus.RUNNING }
    val attentionReason = state.attentionReasonOrNull()
    val rawTask = when {
        state is SessionState.Paused -> "Paused"
        runningCall != null -> runningCall.purpose
        sessionCalls.lastOrNull() != null -> sessionCalls.last().purpose
        else -> preToolStatus
    }

    return when {
        state.needsAttention() -> attentionReason ?: "Needs your attention"
        recoveryKind == OverlayRecoveryKind.COMPANION -> "Desktop companion not connected"
        recoveryKind == OverlayRecoveryKind.DEVELOPER -> "Phone access needed"
        else -> rawTask
    }
}

private fun SessionState.sessionIdOrNull(): String? = when (this) {
    SessionState.Idle -> null
    is SessionState.Running -> sessionId
    is SessionState.Paused -> sessionId
    is SessionState.Stopped -> sessionId
    is SessionState.Completed -> sessionId
}

private fun SessionState.currentPurposeOrNull(): String? = when (this) {
    is SessionState.Running -> currentPurpose
    is SessionState.Paused -> currentPurpose
    else -> null
}

private fun SessionState.attentionReasonOrNull(): String? = when (this) {
    is SessionState.Running -> attentionReason?.takeIf { it.isNotBlank() }
    is SessionState.Paused -> attentionReason?.takeIf { it.isNotBlank() }
    else -> null
}

private fun SessionState.attentionActionLabelOrNull(): String? = when (this) {
    is SessionState.Running -> attentionActionLabel?.takeIf { it.isNotBlank() }
    is SessionState.Paused -> attentionActionLabel?.takeIf { it.isNotBlank() }
    else -> null
}

private fun SessionState.needsAttention(): Boolean =
    currentPurposeOrNull()?.equals("Needs your attention", ignoreCase = true) == true

internal fun overlayRecoveryKind(
    state: SessionState,
    developerStatus: DeveloperModeStatus,
    companionConnected: Boolean,
): OverlayRecoveryKind? {
    if (state.needsAttention()) return OverlayRecoveryKind.ATTENTION

    val developerConnectionNeedsAction = developerStatus.requiresUserAction
    if (developerConnectionNeedsAction) return OverlayRecoveryKind.DEVELOPER

    if (state !is SessionState.Running && state !is SessionState.Paused) return null

    // A Codex retry/release can leave the companion connected. Only show the
    // disconnected recovery overlay when the phone-side heartbeat lease is
    // actually absent.
    if (!companionConnected) {
        return OverlayRecoveryKind.COMPANION
    }
    return null
}

private fun terminalMessage(state: SessionState): String = when (state) {
    is SessionState.Completed -> state.message
    is SessionState.Stopped -> ""
    else -> "Ready for your next request."
}
