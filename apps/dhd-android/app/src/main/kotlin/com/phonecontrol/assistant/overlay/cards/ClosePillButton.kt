package com.phonecontrol.assistant.overlay.cards

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
internal fun ClosePillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Close",
) {
    val colors = LocalAssistantColors.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.composerBackground.copy(
            alpha = if (colors.isDark) 0.95f else 0.92f,
        ),
        border = BorderStroke(
            0.8.dp,
            colors.borderColor.copy(alpha = if (colors.isDark) 0.85f else 0.9f),
        ),
        modifier = modifier.semantics { contentDescription = "$label assistant card" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(com.phonecontrol.assistant.R.drawable.ic_close),
                contentDescription = null,
                tint = colors.textPrimary.copy(alpha = 0.85f),
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            )
        }
    }
}
