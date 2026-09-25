package com.phonecontrol.assistant.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.math.roundToInt

@Composable
internal fun EmptyChat(
    modifier: Modifier = Modifier,
    topReservedSpacePx: Int = 0,
    onSelectPrompt: (String) -> Unit = {},
) {
    SubcomposeLayout(
        modifier = modifier.padding(horizontal = 24.dp),
    ) { constraints ->
        val layoutWidth = constraints.maxWidth
        val hasBoundedHeight = constraints.maxHeight != Constraints.Infinity
        val layoutHeight = if (hasBoundedHeight) constraints.maxHeight else 0
        val content = subcompose("centered-content") {
            EmptyChatContent(
                modifier = Modifier.widthIn(max = 480.dp),
                onSelectPrompt = onSelectPrompt,
            )
        }.single().measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )

        if (!hasBoundedHeight) {
            layout(layoutWidth, content.height) {
                content.placeRelative(
                    x = ((layoutWidth - content.width) / 2).coerceAtLeast(0),
                    y = 0,
                )
            }
        } else if (content.height <= layoutHeight) {
            val reserved = topReservedSpacePx.coerceAtMost(layoutHeight)
            val idealTop = ((reserved + layoutHeight) / 2f - reserved - content.height / 2f)
                .roundToInt()
            val top = idealTop.coerceIn(0, (layoutHeight - content.height).coerceAtLeast(0))
            layout(layoutWidth, layoutHeight) {
                content.placeRelative(
                    x = ((layoutWidth - content.width) / 2).coerceAtLeast(0),
                    y = top,
                )
            }
        } else {
            val scrollableContent = subcompose("scrollable-content") {
                EmptyChatContent(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    onSelectPrompt = onSelectPrompt,
                )
            }.single().measure(
                constraints.copy(minWidth = 0, minHeight = 0),
            )
            val height = if (hasBoundedHeight) layoutHeight else scrollableContent.height
            layout(layoutWidth, height) {
                scrollableContent.placeRelative(
                    x = ((layoutWidth - scrollableContent.width) / 2).coerceAtLeast(0),
                    y = 0,
                )
            }
        }
    }
}

@Composable
private fun EmptyChatContent(
    modifier: Modifier = Modifier,
    onSelectPrompt: (String) -> Unit,
) {
    val colors = LocalAssistantColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_what_can_i_do_on_your),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.chat_ask_dhd_to_operate_apps_on),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PromptSuggestionChip(
                text = "Get me jollof rice and chicken under 6k",
                onClick = { onSelectPrompt("Get me jollof rice and chicken under 6k") },
            )
            PromptSuggestionChip(
                text = "Play me \"Jesus be the name\" on Spotify",
                onClick = { onSelectPrompt("Play me \"Jesus be the name\" on Spotify") },
            )
        }
    }
}

@Composable
private fun PromptSuggestionChip(
    text: String,
    onClick: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
