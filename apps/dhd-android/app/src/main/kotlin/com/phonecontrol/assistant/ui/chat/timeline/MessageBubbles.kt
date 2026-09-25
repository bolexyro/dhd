package com.phonecontrol.assistant.ui.chat.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.ui.components.MarkdownContent
import com.phonecontrol.assistant.ui.components.parseInlineMarkdown
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
internal fun MessageBubble(message: TimelineItem.Message) {
    val colors = LocalAssistantColors.current
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Surface(
                color = colors.userBubble,
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 6.dp,
                ),
                modifier = Modifier.padding(start = 48.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = parseInlineMarkdown(message.text, colors),
                        color = colors.userBubbleText,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 24.dp, top = 2.dp),
            ) {
                MarkdownContent(
                    markdown = message.text,
                    baseTextStyle = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = colors.textPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun SteerMessageBubble(message: TimelineItem.Message) {
    val colors = LocalAssistantColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = colors.surfaceCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colors.accentBlue.copy(alpha = 0.65f)),
            modifier = Modifier.padding(start = 64.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(R.string.chat_steer),
                    color = colors.accentBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message.text,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
