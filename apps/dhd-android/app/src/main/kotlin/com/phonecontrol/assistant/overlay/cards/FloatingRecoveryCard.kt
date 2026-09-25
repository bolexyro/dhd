package com.phonecontrol.assistant.overlay.cards

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.overlay.OverlayRecoveryKind
import com.phonecontrol.assistant.overlay.attentionActionLabelOrNull
import com.phonecontrol.assistant.overlay.attentionReasonOrNull
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
internal fun FloatingRecoveryCard(
    kind: OverlayRecoveryKind,
    state: SessionState,
    developerStatus: DeveloperModeStatus,
    onAcknowledgeAttention: () -> Boolean,
    onStop: () -> Unit,
    onOpenCompanion: () -> Unit,
    onOpenPhoneAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    val cardShape = RoundedCornerShape(28.dp)
    val title: String
    val detail: String
    val icon: Int
    val primaryLabel: String
    val primaryAction: () -> Unit
    val secondaryLabel: String?
    val secondaryAction: (() -> Unit)?

    when (kind) {
        OverlayRecoveryKind.ATTENTION -> {
            val attentionActionLabel = state.attentionActionLabelOrNull()
            val phoneAccessRecovery = attentionActionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true)
            title = if (phoneAccessRecovery) {
                developerStatus.recoveryTitle
            } else {
                "DHD needs your attention"
            }
            detail = if (phoneAccessRecovery) {
                developerStatus.recoveryDetail
            } else {
                state.attentionReasonOrNull()
                    ?: "Review the phone and complete the requested step before continuing."
            }
            icon = com.phonecontrol.assistant.R.drawable.ic_info
            primaryLabel = if (phoneAccessRecovery) "View instructions" else attentionActionLabel ?: "Done"
            primaryAction = if (phoneAccessRecovery) onOpenPhoneAccess else { { onAcknowledgeAttention() } }
            secondaryLabel = if (phoneAccessRecovery) null else "Stop"
            secondaryAction = if (phoneAccessRecovery) null else onStop
        }

        OverlayRecoveryKind.COMPANION -> {
            title = "Desktop companion not connected"
            detail = "DHD is waiting for the desktop companion. Connect this phone on your local network."
            icon = com.phonecontrol.assistant.R.drawable.ic_laptop
            primaryLabel = "View connection instructions"
            primaryAction = onOpenCompanion
            secondaryLabel = null
            secondaryAction = null
        }

        OverlayRecoveryKind.DEVELOPER -> {
            title = developerStatus.recoveryTitle
            detail = developerStatus.recoveryDetail
            icon = com.phonecontrol.assistant.R.drawable.ic_shield
            primaryLabel = "View instructions"
            primaryAction = onOpenPhoneAccess
            secondaryLabel = null
            secondaryAction = null
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        color = colors.composerBackground.copy(
            alpha = if (colors.isDark) 0.98f else 0.97f,
        ),
        border = BorderStroke(
            width = 0.8.dp,
            color = colors.borderColor.copy(alpha = if (colors.isDark) 0.9f else 0.95f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = colors.warningAmber,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = detail,
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 30.dp, top = 6.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 30.dp, top = 12.dp),
            ) {
                RecoveryActionButton(
                    label = primaryLabel,
                    onClick = primaryAction,
                    primary = true,
                )
                if (secondaryLabel != null && secondaryAction != null) {
                    RecoveryActionButton(
                        label = secondaryLabel,
                        onClick = secondaryAction,
                        primary = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecoveryActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    val colors = LocalAssistantColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (primary) colors.warningAmber else Color.Transparent,
        border = if (primary) null else BorderStroke(1.dp, colors.borderColor),
    ) {
        Text(
            text = label,
            color = if (primary) Color.White else colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
        )
    }
}
