package com.phonecontrol.assistant.overlay.composer

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

internal enum class Glyph { SEND, STOP }

@Composable
internal fun GlyphButton(
    label: String,
    glyph: Glyph,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false,
    buttonSize: androidx.compose.ui.unit.Dp = 48.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val colors = LocalAssistantColors.current
    val tint = when {
        glyph == Glyph.STOP -> Color(0xFF1E2B45).copy(alpha = 0.88f)
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
                if (glyph == Glyph.STOP) {
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
        if (glyph == Glyph.SEND) {
            Icon(
                painter = painterResource(com.phonecontrol.assistant.R.drawable.ic_send_arrow),
                contentDescription = null,
                tint = if (enabled) colors.sendButtonActiveIcon else colors.sendButtonInactiveIcon,
                modifier = Modifier.size(iconSize),
            )
        } else Canvas(Modifier.size(iconSize)) {
            when (glyph) {
                Glyph.STOP -> drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    topLeft = Offset(size.width * 0.24f, size.height * 0.24f),
                    size = Size(size.width * 0.52f, size.height * 0.52f),
                    cornerRadius = CornerRadius(3.5.dp.toPx()),
                    style = Stroke(1.8.dp.toPx()),
                )
                Glyph.SEND -> Unit
            }
        }
    }
}
