package com.phonecontrol.assistant.ui.chat.status

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.ui.components.THINKING_WORDS
import com.phonecontrol.assistant.ui.components.nextThinkingWordIndex
import com.phonecontrol.assistant.ui.recovery.AttentionRecoveryCard
import com.phonecontrol.assistant.ui.recovery.CompanionRecoveryCard
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
internal fun RunningStatusIndicator(
    currentPurpose: String,
    attentionReason: String?,
    attentionActionLabel: String?,
    startedAtEpochMs: Long,
    elapsedBeforeStartMs: Long,
    phoneAccessTitle: String,
    phoneAccessDetail: String,
    companionConnected: Boolean,
    phoneAccessRecoveryShownAtTop: Boolean,
    companionRecoveryShownAtTop: Boolean,
    onOpenPhoneAccess: () -> Unit,
    onOpenCompanion: () -> Unit,
    onStopSession: () -> Unit,
    onAcknowledgeAttention: () -> Boolean,
) {
    val elapsedSeconds = rememberElapsedSeconds(startedAtEpochMs, elapsedBeforeStartMs)
    // A pending attention request owns the next step. Keep Done visible even
    // if the companion or developer-status poll changes while the user is
    // completing a biometric/PIN prompt.
    if (currentPurpose.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true)) {
        val phoneAccessInstructionsAtTop = phoneAccessRecoveryShownAtTop &&
                attentionActionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true)
        if (!phoneAccessInstructionsAtTop) {
            AttentionRecoveryCard(
                reason = attentionReason,
                actionLabel = attentionActionLabel,
                phoneAccessTitle = phoneAccessTitle,
                phoneAccessDetail = phoneAccessDetail,
                onAcknowledgeAttention = onAcknowledgeAttention,
                onOpenPhoneAccess = onOpenPhoneAccess,
                onStopSession = onStopSession,
            )
        }
        return
    }

    // A slow Codex startup or a released request does not mean that the LAN
    // companion is disconnected. The phone-side heartbeat lease is the source
    // of truth for this recovery card.
    val waitingForCompanion = !companionConnected
    if (waitingForCompanion) {
        if (!companionRecoveryShownAtTop) {
            CompanionRecoveryCard(
                elapsedSeconds = elapsedSeconds,
                onOpenCompanion = onOpenCompanion,
            )
        }
        return
    }

    ShimmerThinkingIndicator(
        startedAtEpochMs = startedAtEpochMs,
        elapsedBeforeStartMs = elapsedBeforeStartMs,
        elapsedSeconds = elapsedSeconds,
    )
}

@Composable
private fun ShimmerThinkingIndicator(
    startedAtEpochMs: Long,
    elapsedBeforeStartMs: Long,
    elapsedSeconds: Long,
) {
    val colors = LocalAssistantColors.current
    var wordIndex by rememberSaveable(startedAtEpochMs) {
        mutableStateOf(Random.nextInt(THINKING_WORDS.size))
    }
    LaunchedEffect(startedAtEpochMs) {
        while (true) {
            delay(THINKING_WORD_INTERVAL_MS)
            wordIndex = nextThinkingWordIndex(wordIndex)
        }
    }
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_shimmer")

    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = -150f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            colors.accentBlue.copy(alpha = 0.35f),
            colors.accentBlue,
            colors.textPrimary,
            colors.accentBlue,
            colors.accentBlue.copy(alpha = 0.35f),
        ),
        start = Offset(shimmerTranslate, 0f),
        end = Offset(shimmerTranslate + 160f, 0f),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_bot),
            contentDescription = "Thinking",
            tint = colors.accentBlue.copy(alpha = pulseAlpha),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = THINKING_WORDS[wordIndex],
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(brush = shimmerBrush),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "·",
            fontSize = 14.sp,
            color = colors.textSecondary.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${elapsedSeconds}s",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Normal,
            color = colors.textSecondary,
        )
    }
}

@Composable
internal fun PausedStatusIndicator(currentPurpose: String) {
    val colors = LocalAssistantColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_stop),
            contentDescription = "Paused",
            tint = colors.warningAmber,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(
                text = "Paused",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.warningAmber,
            )
            Text(
                text = thinkingDetail(currentPurpose, 0L),
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun rememberElapsedSeconds(
    startedAtEpochMs: Long,
    elapsedBeforeStartMs: Long = 0L,
): Long {
    val normalizedElapsedBeforeStartMs = elapsedBeforeStartMs.coerceAtLeast(0L)
    var elapsedSeconds by remember(startedAtEpochMs, normalizedElapsedBeforeStartMs) {
        mutableStateOf(
            accumulatedElapsedSeconds(
                startedAtEpochMs,
                normalizedElapsedBeforeStartMs,
                System.currentTimeMillis(),
            ),
        )
    }
    LaunchedEffect(startedAtEpochMs, normalizedElapsedBeforeStartMs) {
        while (true) {
            elapsedSeconds = accumulatedElapsedSeconds(
                startedAtEpochMs,
                normalizedElapsedBeforeStartMs,
                System.currentTimeMillis(),
            )
            delay(1_000L)
        }
    }
    return elapsedSeconds
}

internal fun accumulatedElapsedSeconds(
    startedAtEpochMs: Long,
    elapsedBeforeStartMs: Long,
    nowEpochMs: Long,
): Long = (
        elapsedBeforeStartMs.coerceAtLeast(0L) +
                (nowEpochMs - startedAtEpochMs).coerceAtLeast(0L)
        ) / 1_000L

private fun thinkingDetail(currentPurpose: String, elapsedSeconds: Long): String = when {
    currentPurpose.equals(CoordinatorCopy.PREPARING_REQUEST, ignoreCase = true) && elapsedSeconds >= COMPANION_WAIT_CALLOUT_SECONDS ->
        "Waiting for the desktop companion"

    currentPurpose.equals(CoordinatorCopy.PREPARING_REQUEST, ignoreCase = true) -> "Connecting to the desktop companion"
    currentPurpose.equals(CoordinatorCopy.CODEX_PLANNING, ignoreCase = true) || currentPurpose.equals(
        CoordinatorCopy.DHD_PLANNING,
        ignoreCase = true
    ) -> "Thinking…"

    else -> currentPurpose.ifBlank { "Preparing the next step" }
}

private const val COMPANION_WAIT_CALLOUT_SECONDS = 15L
private const val THINKING_WORD_INTERVAL_MS = 4_000L
