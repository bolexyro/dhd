package com.phonecontrol.assistant.ui

import com.phonecontrol.assistant.adb.DeveloperConnectionState
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.recovery.shouldCombineRecoveryBanners
import com.phonecontrol.assistant.ui.recovery.shouldShowTopRecoveryBanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryBannerPolicyTest {
    private val running = SessionState.Running(
        sessionId = "run-1",
        request = "Open an app",
        currentPurpose = "DHD is planning",
        startedAtEpochMs = 1L,
    )

    @Test
    fun `combines phone and companion recovery before or during a run`() {
        val phoneAccessNeeded = DeveloperModeStatus(
            state = DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
            paired = true,
        )

        assertTrue(shouldCombineRecoveryBanners(running, phoneAccessNeeded, companionConnected = false))
        assertTrue(
            shouldCombineRecoveryBanners(SessionState.Idle, phoneAccessNeeded, companionConnected = false),
        )
    }

    @Test
    fun `does not combine when either recovery is absent`() {
        val phoneAccessNeeded = DeveloperModeStatus(
            state = DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
            paired = true,
        )
        assertFalse(
            shouldCombineRecoveryBanners(
                state = running,
                developerStatus = DeveloperModeStatus(DeveloperConnectionState.READY, paired = true),
                companionConnected = false,
            ),
        )
        assertFalse(
            shouldCombineRecoveryBanners(running, phoneAccessNeeded, companionConnected = true),
        )
    }

    @Test
    fun `combines companion recovery with phone access attention`() {
        val phoneAccessNeeded = DeveloperModeStatus(
            state = DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
            paired = true,
        )
        val phoneAccessAttention = running.copy(
            currentPurpose = "Needs your attention",
            attentionReason = "DHD paused this task because it needs phone access.",
            attentionActionLabel = "View instructions",
        )
        val pausedPhoneAccessAttention = SessionState.Paused(
            sessionId = running.sessionId,
            request = running.request,
            currentPurpose = "Needs your attention",
            startedAtEpochMs = running.startedAtEpochMs,
            attentionReason = phoneAccessAttention.attentionReason,
            attentionActionLabel = phoneAccessAttention.attentionActionLabel,
        )

        assertTrue(
            shouldCombineRecoveryBanners(
                phoneAccessAttention,
                phoneAccessNeeded,
                companionConnected = false,
            ),
        )
        assertTrue(shouldShowTopRecoveryBanner(phoneAccessAttention, phoneAccessNeeded, companionConnected = false))
        assertTrue(
            shouldCombineRecoveryBanners(
                pausedPhoneAccessAttention,
                phoneAccessNeeded,
                companionConnected = false,
            ),
        )
        assertTrue(shouldShowTopRecoveryBanner(phoneAccessAttention, phoneAccessNeeded, companionConnected = true))
    }

    @Test
    fun `shows the companion recovery at the top even before a run starts`() {
        val phoneAccessReady = DeveloperModeStatus(
            state = DeveloperConnectionState.READY,
            paired = true,
        )

        assertTrue(shouldShowTopRecoveryBanner(SessionState.Idle, phoneAccessReady, companionConnected = false))
        assertTrue(shouldShowTopRecoveryBanner(running, phoneAccessReady, companionConnected = false))
        assertFalse(shouldShowTopRecoveryBanner(SessionState.Idle, phoneAccessReady, companionConnected = true))
    }
}
