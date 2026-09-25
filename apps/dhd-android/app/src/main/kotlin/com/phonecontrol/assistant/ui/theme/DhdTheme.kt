package com.phonecontrol.assistant.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun DhdTheme(
    isDarkMode: Boolean,
    content: @Composable () -> Unit,
) {
    val assistantColors = if (isDarkMode) DarkAssistantColors else LightAssistantColors
    val materialColors = if (isDarkMode) {
        darkColorScheme(
            primary = assistantColors.accentBlue,
            onPrimary = Color.White,
            secondary = assistantColors.accentGreen,
            background = assistantColors.background,
            surface = assistantColors.surfaceCard,
            onBackground = assistantColors.textPrimary,
            onSurface = assistantColors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = assistantColors.accentBlue,
            onPrimary = Color.White,
            secondary = assistantColors.accentGreen,
            background = assistantColors.background,
            surface = assistantColors.surfaceCard,
            onBackground = assistantColors.textPrimary,
            onSurface = assistantColors.textPrimary,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDarkMode
            insetsController.isAppearanceLightNavigationBars = !isDarkMode
        }
    }

    CompositionLocalProvider(LocalAssistantColors provides assistantColors) {
        MaterialTheme(colorScheme = materialColors, content = content)
    }
}
