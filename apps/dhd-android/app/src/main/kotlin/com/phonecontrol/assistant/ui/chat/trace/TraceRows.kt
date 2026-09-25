package com.phonecontrol.assistant.ui.chat.trace

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.ui.theme.AssistantColorScheme
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.toolActivityColor
import kotlinx.coroutines.delay

private const val TRACE_NEW_ROW_HANDOFF_MS = 220L
private const val TRACE_ROW_EXIT_MS = 180L

private data class AnimatedTraceRow(
    val activity: TimelineItem.Activity,
    val visible: Boolean = true,
)

@Composable
internal fun AnimatedCollapsedTrace(
    activities: List<TimelineItem.Activity>,
    earlierActivities: List<TimelineItem.Activity>,
    earlierCount: Int,
    earlierExpanded: Boolean,
    currentActivityId: String?,
    syntheticCurrent: DhdToolCall?,
    onToggleEarlier: () -> Unit,
) {
    var rows by remember { mutableStateOf(activities.map { AnimatedTraceRow(it) }) }
    var displayedEarlierCount by remember { mutableStateOf(earlierCount) }
    var displayedSyntheticCurrent by remember { mutableStateOf(syntheticCurrent) }
    var syntheticVisible by remember { mutableStateOf(syntheticCurrent != null) }
    val activityIds = activities.map { it.id }
    val activityById = remember(activities) { activities.associateBy { it.id } }

    LaunchedEffect(activityIds, syntheticCurrent?.id, earlierCount) {
        val desiredIds = activityIds.toSet()
        val previousRows = rows
        val previousIds = previousRows.map { it.activity.id }.toSet()
        val incomingRows = activities.filter { it.id !in previousIds }
        val removedIds = previousRows
            .filter { it.activity.id !in desiredIds }
            .map { it.activity.id }
            .toSet()
        val previousSynthetic = displayedSyntheticCurrent
        val syntheticAdded = syntheticCurrent != null && previousSynthetic?.id != syntheticCurrent.id
        val syntheticRemoved = syntheticCurrent == null && previousSynthetic != null
        val syntheticReplacedByPersisted = syntheticRemoved && previousSynthetic?.let { previous ->
            activities.any { it.matchesLiveTool(previous) }
        } == true

        if (syntheticCurrent != null) {
            displayedSyntheticCurrent = syntheticCurrent
            syntheticVisible = true
        }
        if (syntheticReplacedByPersisted) {
            // The persisted lifecycle row is the same tool call, not a new
            // action. Swap it in place instead of running the add/remove
            // handoff that is reserved for genuinely new calls.
            displayedSyntheticCurrent = null
            syntheticVisible = false
        }
        if (incomingRows.isNotEmpty()) {
            rows = previousRows.map { it.copy(visible = true) } +
                    incomingRows.map { AnimatedTraceRow(it) }
        }

        // Let the new tool call arrive before the displaced row is moved into
        // the earlier-actions bucket.
        if ((incomingRows.isNotEmpty() || syntheticAdded) && !syntheticReplacedByPersisted) {
            delay(TRACE_NEW_ROW_HANDOFF_MS)
        }

        if (removedIds.isNotEmpty()) {
            rows = rows.map { row ->
                if (row.activity.id in removedIds) row.copy(visible = false) else row
            }
        }
        if (syntheticRemoved && !syntheticReplacedByPersisted) {
            syntheticVisible = false
        }
        displayedEarlierCount = earlierCount

        val syntheticNeedsExit = syntheticRemoved && !syntheticReplacedByPersisted
        if (removedIds.isNotEmpty() || syntheticNeedsExit) {
            delay(TRACE_ROW_EXIT_MS)
            rows = rows.filter { it.activity.id in desiredIds }
            if (syntheticNeedsExit) {
                displayedSyntheticCurrent = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EarlierActionsRow(
            count = displayedEarlierCount,
            expanded = earlierExpanded,
            onToggle = onToggleEarlier,
        )
        earlierActivities.forEach { activity ->
            key("earlier-${activity.id}") {
                AnimatedVisibility(
                    visible = earlierExpanded,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
                ) {
                    TraceStepRow(activity = activity)
                }
            }
        }
        rows.forEach { row ->
            key(row.activity.id) {
                AnimatedVisibility(
                    visible = row.visible,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
                ) {
                    TraceStepRow(
                        activity = activityById[row.activity.id] ?: row.activity,
                        isCurrent = row.activity.id == currentActivityId,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = syntheticVisible,
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            displayedSyntheticCurrent?.let { CurrentToolTraceRow(it) }
        }
    }
}

@Composable
internal fun WorkedTraceSection(
    durationSeconds: Long,
    activities: List<TimelineItem.Activity>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onToggleExpand() },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggleExpand() }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Worked for ${durationSeconds}s",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
            )
            Icon(
                painter = painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                contentDescription = if (expanded) "Collapse trace" else "Expand trace",
                tint = colors.textSecondary,
                modifier = Modifier.size(13.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { onToggleExpand() },
                        )
                    }
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                activities.forEach { activity ->
                    TraceStepRow(activity = activity)
                }
            }
        }
    }
}

@Composable
private fun EarlierActionsRow(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    if (count <= 0) return
    val colors = LocalAssistantColors.current
    val label = if (expanded) "Hide earlier actions" else earlierActionsLabel(count)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "earlier_actions_chevron",
    )
    Row(
        modifier = Modifier
            .padding(start = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse earlier actions" else "Show earlier actions",
                onClick = onToggle,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
            label = "earlier_actions_label",
        ) { animatedLabel ->
            Text(
                text = animatedLabel,
                fontSize = 12.5.sp,
                color = colors.textSecondary.copy(alpha = 0.78f),
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = colors.textSecondary.copy(alpha = 0.78f),
            modifier = Modifier
                .size(13.dp)
                .graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

@Composable
private fun TraceStepRow(
    activity: TimelineItem.Activity,
    isCurrent: Boolean = false,
) {
    TraceStepRowContent(
        toolName = activity.toolName,
        actionType = activity.actionType,
        label = activityLabel(activity),
        status = activity.status,
        isCurrent = isCurrent,
    )
}

@Composable
private fun CurrentToolTraceRow(toolCall: DhdToolCall) {
    TraceStepRowContent(
        toolName = toolCall.toolName,
        label = toolCall.purpose,
        status = "running",
        isCurrent = true,
    )
}

private data class ToolActivityShimmer(
    val brush: Brush,
    val pulseAlpha: Float,
)

@Composable
private fun rememberToolActivityShimmer(
    colors: AssistantColorScheme,
    shimmerColor: Color,
): ToolActivityShimmer {
    val infiniteTransition = rememberInfiniteTransition(label = "tool_activity_shimmer")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = -150f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tool_activity_shimmer_translate",
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tool_activity_pulse_alpha",
    )
    return ToolActivityShimmer(
        brush = Brush.linearGradient(
            colors = listOf(
                shimmerColor.copy(alpha = 0.35f),
                shimmerColor,
                colors.textPrimary,
                shimmerColor,
                shimmerColor.copy(alpha = 0.35f),
            ),
            start = Offset(shimmerTranslate, 0f),
            end = Offset(shimmerTranslate + 160f, 0f),
        ),
        pulseAlpha = pulseAlpha,
    )
}

@Composable
private fun TraceStepRowContent(
    toolName: String?,
    actionType: String? = null,
    label: String,
    status: String,
    isCurrent: Boolean,
) {
    val colors = LocalAssistantColors.current

    val normalizedStatus = status.lowercase()
    val statusColor = when (normalizedStatus) {
        "completed" -> colors.accentGreen
        "failed" -> colors.errorRed
        "attention" -> colors.warningAmber
        else -> colors.textSecondary
    }
    val iconColor = when (normalizedStatus) {
        "failed" -> colors.errorRed
        "attention" -> colors.warningAmber
        else -> toolActivityColor(toolName, colors, statusColor, actionType)
    }
    val shimmer = if (isCurrent) {
        rememberToolActivityShimmer(colors, iconColor)
    } else {
        null
    }
    val labelStyle = TextStyle(
        brush = shimmer?.brush,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Normal,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 3.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_connected_nodes),
            contentDescription = "${toolName ?: "Tool"} call${if (isCurrent) " in progress" else ""}",
            tint = shimmer?.let { iconColor.copy(alpha = it.pulseAlpha) } ?: iconColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = if (shimmer == null) colors.textPrimary else Color.Unspecified,
            style = labelStyle,
            modifier = Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
