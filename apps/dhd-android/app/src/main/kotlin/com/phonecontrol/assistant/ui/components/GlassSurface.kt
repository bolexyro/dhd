package com.phonecontrol.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.ui.theme.AssistantColorScheme
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

internal fun glassSurfaceColor(colors: AssistantColorScheme): Color =
    colors.composerBackground.copy(alpha = if (colors.isDark) 0.98f else 0.97f)

internal fun glassSurfaceBorder(colors: AssistantColorScheme): BorderStroke = BorderStroke(
    width = 0.8.dp,
    color = colors.borderColor.copy(alpha = if (colors.isDark) 0.9f else 0.95f),
)

@Composable
internal fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        modifier = modifier,
        shape = shape,
        color = glassSurfaceColor(colors),
        border = glassSurfaceBorder(colors),
        content = content,
    )
}
