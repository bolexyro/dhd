package com.phonecontrol.assistant.ui.theme

import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun assistantSwitchColors(colors: AssistantColorScheme) = SwitchDefaults.colors(
    checkedThumbColor = if (colors.isDark) Color.Black else Color.White,
    checkedTrackColor = if (colors.isDark) Color.White else Color.Black,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = if (colors.isDark) DhdPalette.SystemGray else DhdPalette.Gray400,
    uncheckedTrackColor = if (colors.isDark) DhdPalette.SwitchTrackDark else DhdPalette.Gray200,
    uncheckedBorderColor = Color.Transparent,
    disabledCheckedThumbColor = if (colors.isDark) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f),
    disabledCheckedTrackColor = if (colors.isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f),
    disabledUncheckedThumbColor = if (colors.isDark) DhdPalette.SystemGray.copy(alpha = 0.4f) else DhdPalette.Gray400.copy(
        alpha = 0.4f
    ),
    disabledUncheckedTrackColor = if (colors.isDark) DhdPalette.SwitchTrackDark.copy(alpha = 0.4f) else DhdPalette.Gray200.copy(
        alpha = 0.4f
    ),
)
