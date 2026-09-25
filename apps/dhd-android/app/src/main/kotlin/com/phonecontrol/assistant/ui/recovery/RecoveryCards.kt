package com.phonecontrol.assistant.ui.recovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
internal fun CompanionRecoveryCard(
    elapsedSeconds: Long?,
    onOpenCompanion: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    if (compact) {
        CompactRecoveryStatusCard(
            icon = R.drawable.ic_laptop,
            title = stringResource(R.string.recovery_desktop_companion_not_connected),
            detail = stringResource(R.string.recovery_dhd_is_waiting_for_the_desktop),
            accent = colors.warningAmber,
            actionLabel = stringResource(R.string.recovery_instructions),
            onAction = onOpenCompanion,
            modifier = modifier,
        )
        return
    }
    RecoveryCard(
        icon = R.drawable.ic_laptop,
        title = stringResource(R.string.recovery_desktop_companion_not_connected),
        detail = stringResource(R.string.recovery_dhd_is_waiting_for_the_desktop_2),
        accent = colors.warningAmber,
        actionLabel = stringResource(R.string.common_view_connection_instructions),
        onAction = onOpenCompanion,
        modifier = modifier,
        trailing = elapsedSeconds?.let { "Waiting ${it}s" },
    )
}

@Composable
internal fun DeveloperConnectionRecoveryCard(
    status: DeveloperModeStatus,
    onOpenPhoneAccess: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    if (compact) {
        CompactRecoveryStatusCard(
            icon = R.drawable.ic_shield,
            title = status.recoveryTitle,
            detail = status.recoveryDetail,
            accent = colors.warningAmber,
            actionLabel = stringResource(R.string.recovery_instructions),
            onAction = onOpenPhoneAccess,
            modifier = modifier,
        )
        return
    }
    RecoveryCard(
        icon = R.drawable.ic_shield,
        title = status.recoveryTitle,
        detail = status.recoveryDetail,
        accent = colors.warningAmber,
        actionLabel = stringResource(R.string.recovery_view_instructions),
        onAction = onOpenPhoneAccess,
        modifier = modifier,
    )
}

@Composable
private fun CompactRecoveryStatusCard(
    icon: Int,
    title: String,
    detail: String,
    accent: Color,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = colors.warningAmber),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(actionLabel, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun CombinedRecoveryCard(
    phoneStatus: DeveloperModeStatus,
    companionWaitSeconds: Long?,
    onOpenPhoneAccess: () -> Unit,
    onOpenCompanion: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 13.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            CombinedRecoverySection(
                icon = R.drawable.ic_shield,
                title = if (compact) "Phone access needed" else phoneStatus.recoveryTitle,
                detail = phoneStatus.recoveryDetail,
                actionLabel = stringResource(R.string.recovery_view_instructions),
                onAction = onOpenPhoneAccess,
                compact = compact,
                compactActionLabel = stringResource(R.string.recovery_instructions),
            )
            HorizontalDivider(color = colors.borderColor.copy(alpha = 0.8f))
            CombinedRecoverySection(
                icon = R.drawable.ic_laptop,
                title = if (compact) "Companion not connected" else "Desktop companion not connected",
                detail = stringResource(R.string.recovery_dhd_is_waiting_for_the_desktop_2),
                trailing = companionWaitSeconds?.let { "Waiting ${it}s" },
                actionLabel = stringResource(R.string.recovery_view_instructions),
                onAction = onOpenCompanion,
                compact = compact,
                compactActionLabel = stringResource(R.string.recovery_instructions),
            )
        }
    }
}

@Composable
private fun CombinedRecoverySection(
    icon: Int,
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
    trailing: String? = null,
    compact: Boolean = false,
    compactActionLabel: String = actionLabel,
) {
    val colors = LocalAssistantColors.current
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = colors.warningAmber,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = colors.warningAmber),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(compactActionLabel, fontSize = 11.sp, maxLines = 1)
            }
        }
    } else {
        Column {
            RecoveryDetails(
                icon = icon,
                title = title,
                detail = detail,
                accent = colors.warningAmber,
                actionLabel = actionLabel,
                onAction = onAction,
                trailing = trailing,
            )
        }
    }
}

@Composable
internal fun AttentionRecoveryCard(
    reason: String?,
    actionLabel: String?,
    phoneAccessTitle: String,
    phoneAccessDetail: String,
    onAcknowledgeAttention: () -> Boolean,
    onOpenPhoneAccess: (() -> Unit)? = null,
    onStopSession: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val usesPhoneAccessInstructions = actionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true) &&
            onOpenPhoneAccess != null
    if (usesPhoneAccessInstructions) {
        PhoneAccessPausedCard(
            title = phoneAccessTitle,
            detail = phoneAccessDetail,
            onOpenPhoneAccess = onOpenPhoneAccess!!,
        )
        return
    }
    RecoveryCard(
        icon = R.drawable.ic_info,
        title = stringResource(R.string.recovery_dhd_needs_your_attention),
        detail = reason?.takeIf(String::isNotBlank)
            ?: "Review the phone and complete the requested step before continuing.",
        accent = colors.warningAmber,
        actionLabel = actionLabel?.takeIf(String::isNotBlank) ?: "Done",
        onAction = {
            onAcknowledgeAttention()
        },
        secondaryActionLabel = stringResource(R.string.common_stop),
        onSecondaryAction = onStopSession,
    )
}

@Composable
private fun PhoneAccessPausedCard(
    title: String,
    detail: String,
    onOpenPhoneAccess: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    RecoveryCard(
        icon = R.drawable.ic_info,
        title = title,
        detail = detail,
        accent = colors.warningAmber,
        actionLabel = stringResource(R.string.recovery_view_instructions),
        onAction = onOpenPhoneAccess,
        containerColor = Color.Transparent,
        border = BorderStroke(1.dp, colors.warningAmber.copy(alpha = 0.55f)),
        verticalPadding = 12.dp,
    )
}

@Composable
private fun RecoveryCard(
    icon: Int,
    title: String,
    detail: String,
    accent: Color,
    actionLabel: String,
    onAction: () -> Unit,
    trailing: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    containerColor: Color = LocalAssistantColors.current.surfaceCard,
    border: BorderStroke = BorderStroke(1.dp, LocalAssistantColors.current.borderColor),
    verticalPadding: Dp = 13.dp,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = border,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = verticalPadding)) {
            RecoveryDetails(
                icon = icon,
                title = title,
                detail = detail,
                accent = accent,
                actionLabel = actionLabel,
                onAction = onAction,
                trailing = trailing,
                secondaryActionLabel = secondaryActionLabel,
                onSecondaryAction = onSecondaryAction,
            )
        }
    }
}

@Composable
private fun RecoveryDetails(
    icon: Int,
    title: String,
    detail: String,
    accent: Color,
    actionLabel: String,
    onAction: () -> Unit,
    trailing: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = LocalAssistantColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(text = it, color = colors.textSecondary, fontSize = 11.sp)
        }
    }
    Text(
        text = detail,
        color = colors.textSecondary,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(start = 27.dp, top = 5.dp),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 27.dp, top = 9.dp),
    ) {
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(actionLabel, fontSize = 12.sp)
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            OutlinedButton(
                onClick = onSecondaryAction,
                border = BorderStroke(1.dp, colors.borderColor),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(secondaryActionLabel, color = colors.textSecondary, fontSize = 12.sp)
            }
        }
    }
}
