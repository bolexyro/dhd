@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui.displays

import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.ui.components.DhdConfirmDialog
import com.phonecontrol.assistant.ui.components.DhdDialog
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlinx.coroutines.delay

/**
 * Lists every display record currently known to the task-display registry.
 * The composable intentionally receives immutable UI records and callbacks so
 * the lifecycle/daemon implementation can remain outside the UI package.
 */
@Composable
fun TaskDisplaysSheet(
    records: List<TaskDisplayUiRecord>,
    onView: (TaskDisplayUiRecord) -> Unit,
    onEnd: (TaskDisplayUiRecord) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    var endCandidate by remember { mutableStateOf<TaskDisplayUiRecord?>(null) }
    var actionCandidate by remember { mutableStateOf<TaskDisplayUiRecord?>(null) }
    var nowEpochMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowEpochMs = System.currentTimeMillis()
            delay(TASK_DISPLAY_MANAGER_REFRESH_MS)
        }
    }

    val visibleRecords = remember(records) { inspectableTaskDisplayRecords(records) }
    val sortedRecords = remember(visibleRecords) { sortTaskDisplayRecords(visibleRecords) }

    ModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        // Keep the sheet visibly separate from the conversation in both
        // themes, especially against the near-black dark-mode background.
        containerColor = colors.surfaceCard,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The empty state should size to its content so the sheet's
                // partially-expanded anchor never cuts the message off. A
                // populated manager only needs a compact icon strip.
                .then(if (sortedRecords.isEmpty()) Modifier else Modifier.fillMaxHeight(0.52f))
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.common_task_displays),
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
            }

            if (sortedRecords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_laptop),
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(42.dp),
                        )
                        Text(
                            text = stringResource(R.string.displays_no_task_displays),
                            color = colors.textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            text = stringResource(R.string.displays_displays_created_by_an_agent_will),
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                ) {
                    Text(
                        text = stringResource(R.string.displays_tap_an_app_to_view_it),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        items(sortedRecords, key = { it.sessionKey }) { record ->
                            TaskDisplayIconTile(
                                record = record,
                                onView = { onView(record) },
                                onLongPress = { actionCandidate = record },
                            )
                        }
                    }
                }
            }
        }
    }

    actionCandidate?.let { record ->
        TaskDisplayActionsDialog(
            record = record,
            nowEpochMs = nowEpochMs,
            onView = {
                actionCandidate = null
                onView(record)
            },
            onEnd = {
                actionCandidate = null
                endCandidate = record
            },
            onDismiss = { actionCandidate = null },
        )
    }

    endCandidate?.let { record ->
        DhdConfirmDialog(
            title = stringResource(R.string.displays_end_task_display),
            message = "This closes the ${record.appLabel ?: record.packageName ?: "app"} display. " +
                    "An active task will be stopped before the display is released.",
            confirmLabel = stringResource(R.string.displays_end_display),
            confirmColor = colors.errorRed,
            onConfirm = {
                endCandidate = null
                onEnd(record)
            },
            onDismiss = { endCandidate = null },
        )
    }
}

@Composable
private fun TaskDisplayIconTile(
    record: TaskDisplayUiRecord,
    onView: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val appLabel = record.appLabel ?: record.packageName ?: "Task display"
    val canView = record.lifecycle != TaskDisplayLifecycle.ENDED &&
            record.lifecycle != TaskDisplayLifecycle.EXPIRED
    val statusColor = when (record.lifecycle) {
        TaskDisplayLifecycle.RUNNING -> colors.accentGreen
        TaskDisplayLifecycle.PAUSED -> colors.accentBlue
        TaskDisplayLifecycle.COMPLETED,
        TaskDisplayLifecycle.STOPPED -> colors.textSecondary

        TaskDisplayLifecycle.FAILED,
        TaskDisplayLifecycle.UNAVAILABLE -> colors.warningAmber

        TaskDisplayLifecycle.ENDED,
        TaskDisplayLifecycle.EXPIRED -> colors.textSecondary.copy(alpha = 0.7f)
    }
    val description = "$appLabel task display, ${record.lifecycle.displayLabel()}. " +
            "Tap to view. Long press for actions."

    val tileShape = RoundedCornerShape(18.dp)
    Surface(
        shape = tileShape,
        color = colors.settingsCard,
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .size(78.dp)
            .clip(tileShape)
            .combinedClickable(
                enabled = canView,
                onClick = onView,
                onLongClick = onLongPress,
            )
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            TaskDisplayAppIcon(
                packageName = record.packageName,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(9.dp),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(12.dp)
                    .background(statusColor, CircleShape)
                    .border(2.dp, colors.settingsCard, CircleShape),
            )
        }
    }
}

@Composable
private fun TaskDisplayAppIcon(
    packageName: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        packageName
            ?.takeIf(String::isNotBlank)
            ?.let { name -> runCatching { context.packageManager.getApplicationIcon(name) }.getOrNull() }
    }
    if (icon == null) {
        Icon(
            painter = painterResource(R.drawable.ic_apps),
            contentDescription = contentDescription,
            tint = LocalAssistantColors.current.textSecondary,
            modifier = modifier,
        )
    } else {
        AndroidView(
            modifier = modifier,
            factory = { viewContext ->
                ImageView(viewContext).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    this.contentDescription = contentDescription
                }
            },
            update = { imageView ->
                imageView.setImageDrawable(icon)
                imageView.contentDescription = contentDescription
            },
        )
    }
}

@Composable
private fun TaskDisplayActionsDialog(
    record: TaskDisplayUiRecord,
    nowEpochMs: Long,
    onView: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val packageName = record.packageName?.takeIf(String::isNotBlank)
    val appLabel = record.appLabel ?: packageName ?: "this app"
    val canView = record.lifecycle != TaskDisplayLifecycle.ENDED &&
            record.lifecycle != TaskDisplayLifecycle.EXPIRED
    val canEnd = canView

    DhdDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TaskDisplayAppIcon(
                    packageName = packageName,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
                Text(
                    text = appLabel,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        },
        text = {
            Column {
                Text(
                    text = record.lifecycle.displayLabel(),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                record.expiresAtEpochMs?.let { expiry ->
                    Text(
                        text = "Auto-removes ${taskDisplayRemainingLabel(expiry, nowEpochMs)}",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                record.error?.takeIf(String::isNotBlank)?.let { error ->
                    Text(
                        text = error,
                        color = colors.warningAmber,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onView, enabled = canView) {
                Text(
                    text = stringResource(R.string.displays_view),
                    color = if (canView) colors.accentBlue else colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onEnd, enabled = canEnd) {
                Text(
                    text = stringResource(R.string.displays_end),
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

private const val TASK_DISPLAY_MANAGER_REFRESH_MS = 30_000L

internal fun inspectableTaskDisplayRecords(records: List<TaskDisplayUiRecord>): List<TaskDisplayUiRecord> =
    // Ended records remain in the backend for lifecycle/history purposes, but
    // the manager is for displays the user can still inspect or retain.
    records.filter {
        it.lifecycle != TaskDisplayLifecycle.ENDED &&
                it.lifecycle != TaskDisplayLifecycle.EXPIRED
    }

internal fun sortTaskDisplayRecords(records: List<TaskDisplayUiRecord>): List<TaskDisplayUiRecord> =
    records.sortedWith(
        compareByDescending<TaskDisplayUiRecord> { it.lifecycle.isLive() }
            .thenByDescending { it.createdAtEpochMs },
    )

private fun TaskDisplayLifecycle.isLive(): Boolean = when (this) {
    TaskDisplayLifecycle.RUNNING,
    TaskDisplayLifecycle.PAUSED -> true

    else -> false
}

private fun taskDisplayRemainingLabel(expiryEpochMs: Long, nowEpochMs: Long): String {
    val seconds = ((expiryEpochMs - nowEpochMs).coerceAtLeast(0L)) / 1000L
    return when {
        seconds < 60L -> "<1m left"
        seconds < 3600L -> "${seconds / 60L}m left"
        else -> "${seconds / 3600L}h left"
    }
}
