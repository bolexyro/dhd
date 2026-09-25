package com.phonecontrol.assistant.ui.theme

import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun assistantSwitchColors(colors: AssistantColorScheme) = SwitchDefaults.colors(
    checkedThumbColor = if (colors.isDark) Color.Black else Color.White,
    checkedTrackColor = if (colors.isDark) Color.White else Color.Black,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = if (colors.isDark) Color(0xFF8E8E93) else Color(0xFF9CA3AF),
    uncheckedTrackColor = if (colors.isDark) Color(0xFF212124) else Color(0xFFE5E7EB),
    uncheckedBorderColor = Color.Transparent,
    disabledCheckedThumbColor = if (colors.isDark) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f),
    disabledCheckedTrackColor = if (colors.isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f),
    disabledUncheckedThumbColor = if (colors.isDark) Color(0xFF8E8E93).copy(alpha = 0.4f) else Color(0xFF9CA3AF).copy(
        alpha = 0.4f
    ),
    disabledUncheckedTrackColor = if (colors.isDark) Color(0xFF212124).copy(alpha = 0.4f) else Color(0xFFE5E7EB).copy(
        alpha = 0.4f
    ),
)
