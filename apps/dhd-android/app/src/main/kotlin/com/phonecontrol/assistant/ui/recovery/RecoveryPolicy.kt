package com.phonecontrol.assistant.ui.recovery

import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.session.SessionState

internal fun shouldCombineRecoveryBanners(
    state: SessionState,
    developerStatus: DeveloperModeStatus,
    companionConnected: Boolean,
): Boolean =
    (developerStatus.requiresUserAction || state.showsPhoneAccessRecovery()) && !companionConnected

internal fun shouldShowTopRecoveryBanner(
    state: SessionState,
    developerStatus: DeveloperModeStatus,
    companionConnected: Boolean,
): Boolean = developerStatus.requiresUserAction ||
        state.showsPhoneAccessRecovery() ||
        !companionConnected

internal fun SessionState.showsPhoneAccessRecovery(): Boolean = when (this) {
    is SessionState.Running -> currentPurpose.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true) &&
            attentionActionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true)

    is SessionState.Paused -> attentionReason != null &&
            attentionActionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true)

    else -> false
}
