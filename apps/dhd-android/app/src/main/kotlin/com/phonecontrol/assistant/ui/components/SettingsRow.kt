package com.phonecontrol.assistant.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.settingsCard,
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitleColor: Color = LocalAssistantColors.current.textSecondary,
    subtitleMaxLines: Int = 1,
    subtitleLineHeight: TextUnit = TextUnit.Unspecified,
    trailing: @Composable () -> Unit = {},
) {
    val colors = LocalAssistantColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = subtitleColor,
                maxLines = subtitleMaxLines,
                lineHeight = subtitleLineHeight,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
internal fun SettingsRowIcon(
    @DrawableRes icon: Int,
    contentDescription: String,
) {
    val colors = LocalAssistantColors.current
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        tint = colors.textPrimary,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
internal fun SettingsChevron(contentDescription: String) {
    val colors = LocalAssistantColors.current
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = contentDescription,
        tint = colors.textSecondary,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
internal fun SettingsSetUpAction(contentDescription: String) {
    val colors = LocalAssistantColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Set up",
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 4.dp),
        )
        SettingsChevron(contentDescription)
    }
}
