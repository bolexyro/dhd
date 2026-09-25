package com.phonecontrol.assistant.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlinx.coroutines.delay

private const val STALE_CONVERSATION_COUNTDOWN_SECONDS = 5

@Composable
internal fun ConversationExpiryDialog(
    onKeep: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    var secondsRemaining by remember {
        mutableStateOf(STALE_CONVERSATION_COUNTDOWN_SECONDS)
    }

    LaunchedEffect(Unit) {
        for (remaining in STALE_CONVERSATION_COUNTDOWN_SECONDS downTo 1) {
            secondsRemaining = remaining
            delay(1_000L)
        }
        onClear()
    }

    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = colors.surfaceCard,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = RoundedCornerShape(20.dp),
        title = { Text("This conversation is stale", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier.size(112.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = {
                            secondsRemaining.toFloat() / STALE_CONVERSATION_COUNTDOWN_SECONDS
                        },
                        modifier = Modifier.fillMaxSize(),
                        color = colors.accentBlue,
                        trackColor = colors.composerBackground,
                        strokeWidth = 5.dp,
                    )
                    Text(
                        text = secondsRemaining.toString(),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Light,
                        color = colors.textPrimary,
                    )
                }
                Text(
                    "This chat has been inactive for 3 hours and will clear automatically. " +
                            "Keep it to continue this conversation.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) {
                Text("Clear now", color = colors.accentBlue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("Keep conversation", color = colors.textSecondary)
            }
        },
    )
}
