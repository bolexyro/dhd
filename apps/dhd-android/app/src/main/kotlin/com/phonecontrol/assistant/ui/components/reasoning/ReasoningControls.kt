package com.phonecontrol.assistant.ui.components.reasoning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun FastModeButton(
    enabled: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val shape = RoundedCornerShape(18.dp)
    Surface(
        shape = shape,
        color = if (selected) colors.accentBlue else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            .semantics {
                contentDescription = "Fast mode: ${if (selected) "On" else "Off"}"
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_fast_mode),
                contentDescription = null,
                tint = if (selected) Color.White else if (enabled) colors.textPrimary else colors.textSecondary.copy(
                    alpha = 0.45f
                ),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun ReasoningEffortButton(
    effort: ReasoningEffort,
    visibleEfforts: List<ReasoningEffort>,
    enabled: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = CircleShape,
        color = if (expanded) colors.sendButtonInactiveBg else Color.Transparent,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "Reasoning effort: ${effort.label}"
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            ReasoningMeterIcon(
                effort = effort,
                visibleEfforts = visibleEfforts,
                tint = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.45f),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
internal fun ReasoningMeterIcon(
    effort: ReasoningEffort,
    visibleEfforts: List<ReasoningEffort> = ReasoningEffort.entries,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f + size.minDimension * 0.08f)
        val arcRadius = size.minDimension * 0.38f
        val strokeWidth = size.minDimension * 0.1f
        val startAngle = 180f
        val sweepAngle = 180f
        val orderedEfforts = visibleEfforts.distinct().sortedBy(ReasoningEffort::ordinal)
            .ifEmpty { ReasoningEffort.entries }
        val selectedIndex = orderedEfforts.indexOf(effort).coerceAtLeast(0)
        val position = if (orderedEfforts.size == 1) {
            1.0f
        } else {
            selectedIndex.toFloat() / orderedEfforts.lastIndex.toFloat()
        }
        val needleAngle = Math.toRadians((startAngle + sweepAngle * position).toDouble())
        val needleEnd = Offset(
            x = center.x + cos(needleAngle).toFloat() * arcRadius * 0.78f,
            y = center.y + sin(needleAngle).toFloat() * arcRadius * 0.78f,
        )
        val blueSweepAngle = sweepAngle * position

        // Background grey semicircle arc
        drawArc(
            color = tint.copy(alpha = 0.32f),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        // Active blue arc
        if (blueSweepAngle > 0f) {
            drawArc(
                color = colors.accentBlue,
                startAngle = startAngle,
                sweepAngle = blueSweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        // Needle line
        drawLine(
            color = tint,
            start = center,
            end = needleEnd,
            strokeWidth = strokeWidth * 0.9f,
            cap = StrokeCap.Round,
        )
        drawCircle(color = tint, radius = strokeWidth * 1.05f, center = center)
    }
}

@Composable
internal fun ReasoningEffortOverlay(
    selectedEffort: ReasoningEffort,
    visibleEfforts: List<ReasoningEffort>,
    onSelect: (ReasoningEffort) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val availableEfforts = visibleEfforts
        .distinct()
        .sortedBy(ReasoningEffort::ordinal)
        .ifEmpty { listOf(ReasoningEffort.default) }
    val effectiveSelectedEffort = selectedEffort.takeIf { it in availableEfforts }
        ?: availableEfforts.first()

    BackHandler(enabled = true, onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = effectiveSelectedEffort.label,
                    color = colors.accentBlue,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = " effort",
                    color = colors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))
            ReasoningEffortTrack(
                selectedEffort = effectiveSelectedEffort,
                visibleEfforts = availableEfforts,
                onSelect = onSelect,
            )
        }
    }
}
