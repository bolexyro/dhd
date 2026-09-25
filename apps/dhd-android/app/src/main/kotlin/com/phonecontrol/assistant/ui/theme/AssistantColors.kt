package com.phonecontrol.assistant.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AssistantColorScheme(
    val isDark: Boolean,
    val background: Color,
    val surfaceCard: Color,
    val settingsCard: Color,
    val cardDivider: Color,
    val composerBackground: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val accentBlue: Color,
    val accentCyan: Color,
    val accentGreen: Color,
    val accentPurple: Color,
    val accentGold: Color,
    val accentOrange: Color,
    val accentPink: Color,
    val accentMagenta: Color,
    val errorRed: Color,
    val warningAmber: Color,
    val sendButtonActiveBg: Color,
    val sendButtonActiveIcon: Color,
    val sendButtonInactiveBg: Color,
    val sendButtonInactiveIcon: Color,
)

// OpenAI ChatGPT Dark Theme with user-specified cobalt blue #2C67C5
val DarkAssistantColors = AssistantColorScheme(
    isDark = true,
    background = Color(0xFF000000),
    surfaceCard = Color(0xFF1C1C1E),
    settingsCard = Color(0xFF484848),
    cardDivider = Color(0xFF000000),
    composerBackground = Color(0xFF212121),
    borderColor = Color(0xFF2C2C2E),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFF8E8E93),
    userBubble = Color(0xFF1B2D4B),
    userBubbleText = Color.White,
    accentBlue = Color(0xFF2C67C5), // Specified #2C67C5
    accentCyan = Color(0xFF22D3EE),
    accentGreen = Color(0xFF10A37F),
    accentPurple = Color(0xFFB38CFF),
    accentGold = Color(0xFFFACC15),
    accentOrange = Color(0xFFFB923C),
    accentPink = Color(0xFFF472B6),
    accentMagenta = Color(0xFFD946EF),
    errorRed = Color(0xFFEF4444),
    warningAmber = Color(0xFFF59E0B),
    sendButtonActiveBg = Color(0xFF2C67C5), // App blue
    sendButtonActiveIcon = Color.White,
    sendButtonInactiveBg = Color(0xFF333333),
    sendButtonInactiveIcon = Color(0xFF8E8E93),
)

// OpenAI ChatGPT Light Theme
val LightAssistantColors = AssistantColorScheme(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surfaceCard = Color(0xFFF4F4F5),
    settingsCard = Color(0xFFF4F4F5),
    cardDivider = Color(0xFFE5E7EB),
    composerBackground = Color(0xFFF4F4F5),
    borderColor = Color(0xFFE5E7EB),
    textPrimary = Color(0xFF0D0D0D),
    textSecondary = Color(0xFF6B7280),
    userBubble = Color(0xFFE5E7EB),
    userBubbleText = Color(0xFF0D0D0D),
    accentBlue = Color(0xFF2C67C5),
    accentCyan = Color(0xFF0891B2),
    accentGreen = Color(0xFF10A37F),
    accentPurple = Color(0xFF7C3AED),
    accentGold = Color(0xFFA16207),
    accentOrange = Color(0xFFC2410C),
    accentPink = Color(0xFFBE185D),
    accentMagenta = Color(0xFFA21CAF),
    errorRed = Color(0xFFDC2626),
    warningAmber = Color(0xFFD97706),
    sendButtonActiveBg = Color(0xFF2C67C5), // App blue
    sendButtonActiveIcon = Color.White,
    sendButtonInactiveBg = Color(0xFFE5E7EB),
    sendButtonInactiveIcon = Color(0xFF9CA3AF),
)

val LocalAssistantColors = staticCompositionLocalOf { DarkAssistantColors }
