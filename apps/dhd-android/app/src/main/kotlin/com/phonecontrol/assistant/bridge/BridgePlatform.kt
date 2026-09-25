package com.phonecontrol.assistant.bridge

import android.content.Context
import android.content.Intent
import com.phonecontrol.assistant.apps.InstalledAppsRepository
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.execution.TaskDisplayLayoutPreferences
import com.phonecontrol.assistant.session.AssistantForegroundService

internal const val BRIDGE_LOG_TAG = "PhoneControlBridge"

internal interface BridgePlatform {
    fun storedString(key: String): String?
    fun storeString(key: String, value: String)
    fun launchableApps(): List<InstalledUserApp>
    fun applicationLabel(packageName: String): String
    fun isFullSizeLayoutEnabled(packageName: String): Boolean
    fun setFullSizeLayoutEnabled(packageName: String, enabled: Boolean)
    fun startSessionService(
        request: String,
        reasoningEffort: String,
        fastMode: Boolean,
        conversationId: String?,
    )
    fun showAttentionNotification(reason: String, conversationId: String?)
    fun showCompletionNotification(message: String, conversationId: String?)
    fun removeAttentionNotification()
    fun reconcileServiceLifetime()
    fun logWarning(tag: String, message: String, error: Throwable)
    fun logError(tag: String, message: String, error: Throwable)
}

internal fun BridgePlatform.appLabel(packageName: String): String = runCatching {
    applicationLabel(packageName)
}.getOrDefault(packageName)

internal class AndroidBridgePlatform(
    private val context: Context,
    preferencesName: String,
    private val installedAppsRepository: InstalledAppsRepository,
    private val taskDisplayLayoutPreferences: TaskDisplayLayoutPreferences,
) : BridgePlatform {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun storedString(key: String): String? = preferences.getString(key, null)

    override fun storeString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun launchableApps(): List<InstalledUserApp> = installedAppsRepository.listLaunchableApps()

    override fun applicationLabel(packageName: String): String =
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(packageName, 0),
        ).toString()

    override fun isFullSizeLayoutEnabled(packageName: String): Boolean =
        taskDisplayLayoutPreferences.isFullSizeLayoutEnabled(packageName)

    override fun setFullSizeLayoutEnabled(packageName: String, enabled: Boolean) {
        taskDisplayLayoutPreferences.setFullSizeLayoutEnabled(packageName, enabled)
    }

    override fun startSessionService(
        request: String,
        reasoningEffort: String,
        fastMode: Boolean,
        conversationId: String?,
    ) {
        val serviceIntent = Intent(context, AssistantForegroundService::class.java)
            .setAction(AssistantForegroundService.ACTION_START)
            .putExtra(AssistantForegroundService.EXTRA_REQUEST, request)
            .putExtra(AssistantForegroundService.EXTRA_REASONING_EFFORT, reasoningEffort)
            .putExtra(AssistantForegroundService.EXTRA_FAST_MODE, fastMode)
        if (!conversationId.isNullOrBlank()) {
            serviceIntent.putExtra(AssistantForegroundService.EXTRA_CONVERSATION_ID, conversationId)
        }
        androidx.core.content.ContextCompat.startForegroundService(
            context,
            serviceIntent,
        )
    }

    override fun showAttentionNotification(reason: String, conversationId: String?) {
        AssistantForegroundService.showAttentionNotification(context, reason, conversationId)
    }

    override fun showCompletionNotification(message: String, conversationId: String?) {
        AssistantForegroundService.showCompletionNotification(context, message, conversationId)
    }

    override fun removeAttentionNotification() {
        AssistantForegroundService.removeAttentionNotification(context)
    }

    override fun reconcileServiceLifetime() {
        AssistantForegroundService.reconcileLifetime(context)
    }

    override fun logWarning(tag: String, message: String, error: Throwable) {
        android.util.Log.w(tag, message, error)
    }

    override fun logError(tag: String, message: String, error: Throwable) {
        android.util.Log.e(tag, message, error)
    }
}
