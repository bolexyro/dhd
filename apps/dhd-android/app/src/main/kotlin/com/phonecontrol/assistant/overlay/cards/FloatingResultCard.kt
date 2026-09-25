package com.phonecontrol.assistant.overlay.cards

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.ui.components.GlassSurface
import com.phonecontrol.assistant.ui.components.MarkdownContent
import com.phonecontrol.assistant.ui.components.glassSurfaceColor
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
internal fun FloatingResultCard(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val cardShape = RoundedCornerShape(28.dp)
    val compactResult = remember(message) {
        val trimmed = message.trim()
        trimmed.isNotEmpty() && !trimmed.contains('\n') && trimmed.length <= 120
    }
    val resultScrollState = rememberScrollState()
    val showResultTopFade by remember {
        derivedStateOf { resultScrollState.value > 0 }
    }
    val showResultBottomFade by remember {
        derivedStateOf { resultScrollState.value < resultScrollState.maxValue }
    }
    val resultSurfaceColor = glassSurfaceColor(colors)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        ClosePillButton(
            onClick = onDismiss,
            modifier = Modifier.padding(bottom = 4.dp, end = 6.dp),
            label = stringResource(R.string.common_close),
        )

        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 360.dp),
                ) {
                    // The scroll container is deliberately separate from the
                    // fade layer. A draw modifier on verticalScroll can be
                    // clipped by the scroll viewport before its gradient is
                    // composited, leaving the old squared-off crop.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 360.dp)
                            .verticalScroll(resultScrollState),
                        contentAlignment = if (compactResult) Alignment.Center else Alignment.TopStart,
                    ) {
                        MarkdownContent(
                            markdown = message,
                            modifier = Modifier.fillMaxWidth(),
                            baseTextStyle = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 16.sp,
                                lineHeight = 23.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                        )
                    }

                    if (showResultTopFade) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            resultSurfaceColor,
                                            resultSurfaceColor.copy(alpha = 0.84f),
                                            Color.Transparent,
                                        ),
                                    ),
                                ),
                        )
                    }
                    if (showResultBottomFade) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            resultSurfaceColor.copy(alpha = 0.84f),
                                            resultSurfaceColor,
                                        ),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
    }
}
