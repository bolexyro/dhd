package com.phonecontrol.assistant.session

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class SessionCommands(private val context: Context) {
    fun start(
        request: String,
        conversationId: String? = null,
        reasoningEffort: String? = null,
        fastMode: Boolean = false,
    ) {
        val intent = serviceIntent(AssistantForegroundService.ACTION_START)
            .putExtra(AssistantForegroundService.EXTRA_REQUEST, request)
        if (!conversationId.isNullOrBlank()) {
            intent.putExtra(AssistantForegroundService.EXTRA_CONVERSATION_ID, conversationId)
        }
        if (!reasoningEffort.isNullOrBlank()) {
            intent.putExtra(AssistantForegroundService.EXTRA_REASONING_EFFORT, reasoningEffort)
        }
        intent.putExtra(AssistantForegroundService.EXTRA_FAST_MODE, fastMode)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop() {
        context.startService(serviceIntent(AssistantForegroundService.ACTION_STOP_USER))
    }

    fun startFresh() {
        context.startService(serviceIntent(AssistantForegroundService.ACTION_START_FRESH))
    }

    fun continueStopped() {
        ContextCompat.startForegroundService(context, serviceIntent(AssistantForegroundService.ACTION_CONTINUE))
    }

    fun enableOverlay() {
        context.startService(serviceIntent(AssistantForegroundService.ACTION_ENABLE_OVERLAY))
    }

    fun disableOverlay() {
        context.startService(serviceIntent(AssistantForegroundService.ACTION_DISABLE_OVERLAY))
    }

    private fun serviceIntent(action: String): Intent =
        Intent(context, AssistantForegroundService::class.java).setAction(action)
}
