package com.phonecontrol.assistant.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.core.needsAttention
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.overlay.bubble.DhdIdentity
import com.phonecontrol.assistant.overlay.composer.GlyphButton
import com.phonecontrol.assistant.overlay.effects.GeminiHorizonGlow
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.components.THINKING_WORDS
import com.phonecontrol.assistant.ui.components.THINKING_WORD_INTERVAL_MS
import com.phonecontrol.assistant.ui.components.nextThinkingWordIndex
import com.phonecontrol.assistant.ui.theme.AssistantColorScheme
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
private fun rememberPreToolStatus(enabled: Boolean): String {
    var index by remember { mutableIntStateOf(Random.nextInt(THINKING_WORDS.size)) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            return@LaunchedEffect
        }
        while (true) {
            delay(THINKING_WORD_INTERVAL_MS)
            index = nextThinkingWordIndex(index)
        }
    }

    return THINKING_WORDS[index]
}

@Composable
internal fun WorkingRow(
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
    val attention = state.needsAttention || recoveryKind != null
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
        it.sessionId == state.sessionIdOrNull &&
            !ToolNames.isCloseDisplay(it.toolName)
    }
    val attention = state.needsAttention
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

internal fun workingRowSessionCalls(state: SessionState, calls: List<DhdToolCall>): List<DhdToolCall> =
    calls.filter {
        it.sessionId == state.sessionIdOrNull &&
            !ToolNames.isCloseDisplay(it.toolName)
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
        state.needsAttention -> attentionReason ?: "Needs your attention"
        recoveryKind == OverlayRecoveryKind.COMPANION -> "Desktop companion not connected"
        recoveryKind == OverlayRecoveryKind.DEVELOPER -> "Phone access needed"
        else -> rawTask
    }
}
