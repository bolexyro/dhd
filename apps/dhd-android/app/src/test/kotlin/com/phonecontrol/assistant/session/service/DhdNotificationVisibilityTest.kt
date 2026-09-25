package com.phonecontrol.assistant.session.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DhdNotificationVisibilityTest {
    @Test
    fun `completion is suppressed only while the main conversation is visible`() {
        val visibility = DhdNotificationVisibility()

        visibility.setActivityVisible(true)
        visibility.updateUi(mainConversationVisible = false, attentionVisible = false)
        assertFalse(visibility.shouldSuppressCompletionNotification())

        visibility.updateUi(mainConversationVisible = true, attentionVisible = false)
        assertTrue(visibility.shouldSuppressCompletionNotification())
    }

    @Test
    fun `attention is suppressed only when its in-app recovery UI is visible`() {
        val visibility = DhdNotificationVisibility()
        visibility.setActivityVisible(true)
        visibility.updateUi(mainConversationVisible = true, attentionVisible = false)
        assertFalse(visibility.shouldSuppressAttentionNotification())

        visibility.updateUi(mainConversationVisible = true, attentionVisible = true)
        assertTrue(visibility.shouldSuppressAttentionNotification())
    }

    @Test
    fun `stopping the Activity clears all suppression`() {
        val visibility = DhdNotificationVisibility()
        visibility.setActivityVisible(true)
        visibility.updateUi(mainConversationVisible = true, attentionVisible = true)

        visibility.setActivityVisible(false)

        assertFalse(visibility.shouldSuppressCompletionNotification())
        assertFalse(visibility.shouldSuppressAttentionNotification())
    }
}
