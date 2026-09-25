package com.phonecontrol.assistant.overlay

import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.adb.DeveloperConnectionState
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayVisibilityGateTest {
    @Test
    fun `visibility remains hidden until every owner releases`() {
        val gate = OverlayVisibilityGate()
        val observation = gate.acquire(OverlayHideReason.OBSERVATION)
        val activity = gate.acquire(OverlayHideReason.DHD_ACTIVITY)

        assertTrue(gate.hidden.value)
        observation.close()
        assertTrue(gate.hidden.value)
        activity.close()
        assertFalse(gate.hidden.value)
        activity.close()
        assertFalse(gate.hidden.value)
    }

    @Test
    fun `bubble position is clamped inside display and bottom inset`() {
        assertEquals(
            BubblePosition(x = 12, y = 12),
            clampBubblePosition(
                x = -100,
                y = -100,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
                bottomInset = 120,
            ),
        )
        assertEquals(
            BubblePosition(x = 12, y = 112),
            clampBubblePosition(
                x = 0,
                y = 0,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
                topInset = 100,
                bottomInset = 120,
            ),
        )
        assertEquals(
            BubblePosition(x = 1004, y = 2204),
            clampBubblePosition(
                x = 5000,
                y = 5000,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
                bottomInset = 120,
            ),
        )
    }

    @Test
    fun `active updates preserve an explicit bubble collapse`() {
        val first = runningState("Opening the app")
        val next = first.copy(currentPurpose = "Tapping the search field")

        assertEquals(
            OverlayPanelMode.BUBBLE,
            nextOverlayPanelMode(OverlayPanelMode.BUBBLE, first, next),
        )
    }

    @Test
    fun `attention expands once but later updates preserve collapse`() {
        val working = runningState("Opening the app")
        val attention = working.copy(
            currentPurpose = "Needs your attention",
            attentionReason = "Please confirm the visible prompt.",
        )
        val attentionUpdate = attention.copy(attentionReason = "The prompt is still waiting.")

        assertEquals(
            OverlayPanelMode.ATTENTION,
            nextOverlayPanelMode(OverlayPanelMode.WORKING, working, attention),
        )
        assertEquals(
            OverlayPanelMode.BUBBLE,
            nextOverlayPanelMode(OverlayPanelMode.BUBBLE, attention, attentionUpdate),
        )
    }

    @Test
    fun `bubble expansion opens active work and never an idle composer`() {
        assertEquals(
            OverlayPanelMode.WORKING,
            overlayPanelModeForUserExpand(runningState("Working")),
        )
        assertEquals(
            OverlayPanelMode.ATTENTION,
            overlayPanelModeForUserExpand(runningState("Needs your attention")),
        )
        assertEquals(
            OverlayPanelMode.COMPOSER,
            overlayPanelModeForUserExpand(SessionState.Idle),
        )
    }

    @Test
    fun `full screen glow is hidden as soon as composer leaves idle state`() {
        assertTrue(shouldShowOverlayGlow(OverlayPanelMode.COMPOSER, SessionState.Idle, hidden = false))
        assertFalse(shouldShowOverlayGlow(OverlayPanelMode.BUBBLE, SessionState.Idle, hidden = false))
        assertFalse(shouldShowOverlayGlow(OverlayPanelMode.COMPOSER, SessionState.Idle, hidden = true))
        assertFalse(shouldShowOverlayGlow(OverlayPanelMode.COMPOSER, runningState("Working"), hidden = false))
    }

    @Test
    fun `overlay preview releases its decoder target while the activity hides the overlay`() {
        assertTrue(shouldRenderOverlayPreview(previewVisible = true, overlayHidden = false))
        assertFalse(shouldRenderOverlayPreview(previewVisible = true, overlayHidden = true))
        assertFalse(shouldRenderOverlayPreview(previewVisible = false, overlayHidden = false))
    }

    @Test
    fun `overlay preview remains renderable when its session is detached`() {
        assertTrue(
            shouldRenderOverlayPreview(
                previewVisible = true,
                overlayHidden = false,
                hasDisplaySession = true,
            ),
        )
        assertFalse(
            shouldRenderOverlayPreview(
                previewVisible = true,
                overlayHidden = false,
                hasDisplaySession = false,
            ),
        )
    }

    @Test
    fun `overlay recovery prioritizes attention over connection recovery`() {
        val attention = runningState("Needs your attention").copy(
            attentionReason = "Confirm the prompt.",
        )

        assertEquals(
            OverlayRecoveryKind.ATTENTION,
            overlayRecoveryKind(
                state = attention,
                developerStatus = DeveloperModeStatus(DeveloperConnectionState.WIRELESS_DEBUGGING_OFF),
                companionConnected = false,
            ),
        )
    }

    @Test
    fun `overlay recovery exposes developer and companion states`() {
        assertEquals(
            OverlayRecoveryKind.DEVELOPER,
            overlayRecoveryKind(
                state = runningState("Opening an app"),
                developerStatus = DeveloperModeStatus(DeveloperConnectionState.WIRELESS_DEBUGGING_OFF),
                companionConnected = true,
            ),
        )
        assertEquals(
            OverlayRecoveryKind.COMPANION,
            overlayRecoveryKind(
                state = runningState("Waiting for desktop Codex bridge"),
                developerStatus = DeveloperModeStatus(DeveloperConnectionState.READY),
                companionConnected = false,
            ),
        )
        assertEquals(
            null,
            overlayRecoveryKind(
                state = runningState("Waiting for desktop Codex bridge"),
                developerStatus = DeveloperModeStatus(DeveloperConnectionState.READY),
                companionConnected = true,
            ),
        )
    }

    @Test
    fun `overlay keeps phone access recovery visible after a task ends`() {
        assertEquals(
            OverlayRecoveryKind.DEVELOPER,
            overlayRecoveryKind(
                state = SessionState.Completed(
                    sessionId = "completed-session",
                    message = "Done",
                ),
                developerStatus = DeveloperModeStatus(DeveloperConnectionState.WIRELESS_DEBUGGING_OFF),
                companionConnected = true,
            ),
        )
    }

    @Test
    fun `horizontal swipe places bubble on the matching display edge`() {
        val current = BubblePosition(x = 420, y = 600)

        assertEquals(
            BubblePosition(x = 12, y = 600),
            bubblePositionForHorizontalSwipe(
                direction = OverlaySwipeDirection.LEFT,
                currentPosition = current,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
            ),
        )
        assertEquals(
            BubblePosition(x = 1004, y = 600),
            bubblePositionForHorizontalSwipe(
                direction = OverlaySwipeDirection.RIGHT,
                currentPosition = current,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
            ),
        )
    }

    @Test
    fun `bubble release snaps to the nearest display edge`() {
        assertEquals(
            BubblePosition(x = 12, y = 600),
            bubblePositionOnNearestEdge(
                currentPosition = BubblePosition(x = 420, y = 600),
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
            ),
        )
        assertEquals(
            BubblePosition(x = 1004, y = 600),
            bubblePositionOnNearestEdge(
                currentPosition = BubblePosition(x = 700, y = 600),
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
            ),
        )
    }

    @Test
    fun `stopping an active run returns to the quiet bubble when collapsed`() {
        val running = runningState("Working")
        val stopped = SessionState.Stopped(
            sessionId = running.sessionId,
            reason = "Stopped from the notification.",
        )

        assertEquals(
            OverlayPanelMode.BUBBLE,
            nextOverlayPanelMode(OverlayPanelMode.BUBBLE, running, stopped),
        )
    }

    @Test
    fun `stopping an expanded active run returns to the composer without a result`() {
        val running = runningState("Working")
        val stopped = SessionState.Stopped(
            sessionId = running.sessionId,
            reason = "Stopped from the notification.",
        )

        assertEquals(
            OverlayPanelMode.COMPOSER,
            nextOverlayPanelMode(OverlayPanelMode.WORKING, running, stopped),
        )
    }

    private fun runningState(purpose: String): SessionState.Running =
        SessionState.Running(
            sessionId = "overlay-test-session",
            request = "Test request",
            currentPurpose = purpose,
            startedAtEpochMs = 0L,
        )
}
