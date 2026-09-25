package com.phonecontrol.assistant.session.service

/**
 * Describes which DHD surface is currently visible to the user.
 *
 * The service can finish a run while the Activity is still in the foreground.
 * In that case the conversation already receives the result, so a duplicate
 * completion or attention notification is unnecessary. Pairing and the live
 * foreground-service notification intentionally do not use this gate.
 */
class DhdNotificationVisibility {
    @Volatile
    private var activityVisible = false

    @Volatile
    private var mainConversationVisible = false

    @Volatile
    private var attentionVisible = false

    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
        if (!visible) {
            mainConversationVisible = false
            attentionVisible = false
        }
    }

    fun updateUi(mainConversationVisible: Boolean, attentionVisible: Boolean) {
        this.mainConversationVisible = mainConversationVisible
        this.attentionVisible = attentionVisible && mainConversationVisible
    }

    fun shouldSuppressCompletionNotification(): Boolean =
        activityVisible && mainConversationVisible

    fun shouldSuppressAttentionNotification(): Boolean =
        activityVisible && attentionVisible
}
