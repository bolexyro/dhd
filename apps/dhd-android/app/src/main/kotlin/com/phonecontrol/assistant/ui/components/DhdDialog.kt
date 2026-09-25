package com.phonecontrol.assistant.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
internal fun DhdDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    val colors = LocalAssistantColors.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = colors.surfaceCard,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = RoundedCornerShape(20.dp),
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

@Composable
internal fun DhdConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    confirmColor: Color = LocalAssistantColors.current.accentBlue,
) {
    val colors = LocalAssistantColors.current
    DhdDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                message,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = confirmColor, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, color = colors.textSecondary)
            }
        },
    )
}

@Composable
internal fun FullAccessConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DhdConfirmDialog(
        title = "Enable Full Access?",
        message = "Full Access allows DHD to open, inspect, and operate any application installed on this device.\n\n" +
            "This bypasses the per-app allowlist and lets DHD carry out tasks across all your apps.",
        confirmLabel = "Enable",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
