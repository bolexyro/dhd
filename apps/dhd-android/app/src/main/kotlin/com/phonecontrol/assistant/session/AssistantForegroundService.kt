package com.phonecontrol.assistant.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phonecontrol.assistant.MainActivity
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.core.conversationIdOrNull
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.overlay.OverlayPreferences
import com.phonecontrol.assistant.overlay.OverlayWindowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AssistantForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator: SessionCoordinator
        get() = (application as PhoneControlApplication).container.sessionCoordinator
    private lateinit var overlayWindowController: OverlayWindowController
    private var foregroundNotificationActive = false
    private val overlayVisibilityGate
        get() = (application as PhoneControlApplication).container.overlayVisibilityGate

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val container = (application as PhoneControlApplication).container
        overlayWindowController = OverlayWindowController(
            context = this,
            coordinator = coordinator,
            visibilityGate = overlayVisibilityGate,
            developerStatus = container.phoneAccessController.status,
            companionConnected = container.companionBridgeServer.companionConnected,
            taskPreviewState = container.taskDisplayBackend.previewState,
            taskDisplaySession = container.taskDisplayBackend.activeSession,
            onTaskPreviewSurfaceAvailable = { session, surface ->
                container.attachTaskPreview(session, surface)
            },
            onTaskPreviewSurfaceDestroyed = { _, surface, release ->
                container.detachTaskPreview(surface, release)
            },
        )
        serviceScope.launch {
            coordinator.state.collectLatest { state ->
                overlayWindowController.onSessionState(state)
                syncForegroundNotification(state)
            }
        }
        serviceScope.launch {
            coordinator.toolCalls.collectLatest {
                syncForegroundNotification(coordinator.state.value)
            }
        }
        serviceScope.launch {
            overlayVisibilityGate.hidden.collectLatest { hidden ->
                overlayWindowController.setHidden(hidden)
            }
        }
        if (overlayEnabledAndPermitted()) {
            overlayWindowController.show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE_OVERLAY -> {
                if (overlayEnabledAndPermitted()) {
                    overlayWindowController.show()
                }
                syncForegroundNotification(coordinator.state.value)
            }

            ACTION_DISABLE_OVERLAY -> {
                overlayWindowController.hide()
                if (!coordinator.state.value.isActive) {
                    stopForegroundIfNeeded()
                    stopSelfResult(startId)
                }
            }

            ACTION_REFRESH -> {
                if (overlayEnabledAndPermitted()) {
                    overlayWindowController.show()
                } else if (!coordinator.state.value.isActive) {
                    overlayWindowController.hide()
                    stopForegroundIfNeeded()
                    stopSelfResult(startId)
                } else {
                    syncForegroundNotification(coordinator.state.value)
                }
            }

            ACTION_SESSION_ENDED -> {
                if (!overlayEnabledAndPermitted() && !coordinator.state.value.isActive) {
                    overlayWindowController.hide()
                    stopForegroundIfNeeded()
                    stopSelfResult(startId)
                } else {
                    syncForegroundNotification(coordinator.state.value)
                }
            }

            ACTION_CONTINUE -> {
                if (coordinator.state.value is SessionState.Stopped) {
                    startForegroundCompat(buildNotification(coordinator.state.value, starting = true))
                }
                if (!coordinator.continueStopped()) {
                    stopForegroundIfNeeded()
                    stopSelfResult(startId)
                }
            }
            ACTION_STOP -> stopSession("Stopped from the notification.", startId)
            ACTION_STOP_USER -> stopSession("Stopped by the user.", startId)
            ACTION_START_FRESH -> {
                coordinator.reset()
                removeAttentionNotification(this)
                removeCompletionNotification(this)
                if (!overlayEnabledAndPermitted()) {
                    stopForegroundIfNeeded()
                    stopSelfResult(startId)
                } else {
                    syncForegroundNotification(coordinator.state.value)
                }
            }

            ACTION_START, null -> {
                if (overlayEnabledAndPermitted()) {
                    overlayWindowController.show()
                }
                val request = intent?.getStringExtra(EXTRA_REQUEST)
                    ?.takeIf(String::isNotBlank)
                if (request != null) {
                    startForegroundCompat(buildNotification(coordinator.state.value, starting = true))
                    val started = coordinator.start(
                        request = request,
                        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                        reasoningEffort = intent.getStringExtra(EXTRA_REASONING_EFFORT)
                            ?: ReasoningEffort.default.codexValue,
                        fastMode = intent.getBooleanExtra(EXTRA_FAST_MODE, false),
                    )
                    if (!started) syncForegroundNotification(coordinator.state.value)
                } else {
                    syncForegroundNotification(coordinator.state.value)
                }
            }
        }
        return if (overlayEnabledAndPermitted()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::overlayWindowController.isInitialized) {
            overlayWindowController.destroy()
        }
        serviceScope.cancel()
        stopForegroundIfNeeded()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSession(reason: String, startId: Int) {
        coordinator.stop(reason)
        removeAttentionNotification(this)
        if (!overlayEnabledAndPermitted()) {
            stopForegroundIfNeeded()
            stopSelfResult(startId)
        } else {
            syncForegroundNotification(coordinator.state.value)
        }
    }

    private fun syncForegroundNotification(state: SessionState) {
        if (state.isActive) {
            val notification = buildNotification(state)
            if (foregroundNotificationActive) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification)
            } else {
                startForegroundCompat(notification)
            }
        } else {
            stopForegroundIfNeeded()
        }
    }

    private fun stopForegroundIfNeeded() {
        if (!foregroundNotificationActive) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundNotificationActive = false
    }

    private fun buildNotification(state: SessionState, starting: Boolean = false): Notification {
        val isActive = state.isActive || starting
        val status = if (starting) "Starting DHD…" else {
            state.foregroundNotificationStatus(coordinator.toolCalls.value)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, state.conversationIdOrNull)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AssistantForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(contentIntent)
            .setOngoing(isActive)
            .setOnlyAlertOnce(true)
        if (isActive) {
            builder.addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundNotificationActive = true
    }

    private fun createNotificationChannels() {
        createNotificationChannels(this)
    }

    private fun overlayEnabledAndPermitted(): Boolean =
        OverlayPreferences.isEnabled(this) && Settings.canDrawOverlays(this)

    private fun pendingIntentImmutableFlag(): Int =
        pendingIntentFlags()

    companion object {
        const val ACTION_START = "com.phonecontrol.assistant.action.START"
        const val ACTION_ENABLE_OVERLAY = "com.phonecontrol.assistant.action.ENABLE_OVERLAY"
        const val ACTION_DISABLE_OVERLAY = "com.phonecontrol.assistant.action.DISABLE_OVERLAY"
        const val ACTION_REFRESH = "com.phonecontrol.assistant.action.REFRESH"
        const val ACTION_SESSION_ENDED = "com.phonecontrol.assistant.action.SESSION_ENDED"
        const val ACTION_CONTINUE = "com.phonecontrol.assistant.action.CONTINUE"
        const val ACTION_STOP = "com.phonecontrol.assistant.action.STOP"
        const val ACTION_STOP_USER = "com.phonecontrol.assistant.action.STOP_USER"
        const val ACTION_START_FRESH = "com.phonecontrol.assistant.action.START_FRESH"
        const val EXTRA_REQUEST = "com.phonecontrol.assistant.extra.REQUEST"
        const val EXTRA_CONVERSATION_ID = "com.phonecontrol.assistant.extra.CONVERSATION_ID"
        const val EXTRA_REASONING_EFFORT = "com.phonecontrol.assistant.extra.REASONING_EFFORT"
        const val EXTRA_FAST_MODE = "com.phonecontrol.assistant.extra.FAST_MODE"

        internal const val CHANNEL_ID = "assistant_sessions"
        internal const val RESULT_CHANNEL_ID = "assistant_results"
        internal const val ATTENTION_CHANNEL_ID = "assistant_attention"
        internal const val NOTIFICATION_ID = 4201
        internal const val REQUEST_OPEN_APP = 4202
        internal const val REQUEST_STOP = 4204
        internal const val COMPLETION_NOTIFICATION_ID = 4205
        internal const val ATTENTION_NOTIFICATION_ID = 4206
        private const val MAX_NOTIFICATION_TEXT_CHARS = 240

        /** Remove the in-progress notification when a bridge-owned run ends. */
        fun removeSessionNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(NOTIFICATION_ID)
        }

        /** Keep the foreground service only for an active task. The idle overlay host stays started normally. */
        fun reconcileLifetime(context: Context) {
            val appContext = context.applicationContext
            val container = (appContext as? PhoneControlApplication)?.containerOrNull ?: return
            val active = container.sessionCoordinator.state.value.isActive
            val overlayAvailable = OverlayPreferences.isEnabled(appContext) && Settings.canDrawOverlays(appContext)
            if (!overlayAvailable && !active) {
                appContext.stopService(Intent(appContext, AssistantForegroundService::class.java))
            } else if (active) {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, AssistantForegroundService::class.java)
                        .setAction(ACTION_SESSION_ENDED),
                )
            }
        }

        /** Post a result notification without bringing the assistant to the foreground. */
        fun showCompletionNotification(context: Context, message: String, conversationId: String? = null) {
            val container = (context.applicationContext as? PhoneControlApplication)?.containerOrNull
            if (container?.notificationVisibility?.shouldSuppressCompletionNotification() == true) return
            createNotificationChannels(context)
            val preview = completionNotificationPreview(message)
            val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notification_completion_title))
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setContentIntent(openAssistantIntent(context, REQUEST_OPEN_APP + 1, conversationId))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(COMPLETION_NOTIFICATION_ID, notification)
        }

        /** Notify the user without launching an Activity or interrupting Watch mode. */
        fun showAttentionNotification(context: Context, reason: String, conversationId: String? = null) {
            val container = (context.applicationContext as? PhoneControlApplication)?.containerOrNull
            if (container?.notificationVisibility?.shouldSuppressAttentionNotification() == true) return
            createNotificationChannels(context)
            val safeReason = reason.trim()
                .ifBlank { "The phone assistant needs your attention." }
                .take(MAX_NOTIFICATION_TEXT_CHARS)
            val notification = NotificationCompat.Builder(context, ATTENTION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("DHD needs your attention")
                .setContentText(safeReason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(safeReason))
                .setContentIntent(openAssistantIntent(context, REQUEST_OPEN_APP + 2, conversationId))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(ATTENTION_NOTIFICATION_ID, notification)
        }

        fun removeAttentionNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(ATTENTION_NOTIFICATION_ID)
        }

        fun removeCompletionNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(COMPLETION_NOTIFICATION_ID)
        }

        private fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.notification_channel_description)
                    },
                    NotificationChannel(
                        RESULT_CHANNEL_ID,
                        context.getString(R.string.notification_result_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.notification_result_channel_description)
                    },
                    NotificationChannel(
                        ATTENTION_CHANNEL_ID,
                        context.getString(R.string.notification_attention_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = context.getString(R.string.notification_attention_channel_description)
                    },
                ),
            )
        }

        private fun openAssistantIntent(context: Context, requestCode: Int, conversationId: String? = null): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    if (!conversationId.isNullOrBlank()) {
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentFlags(),
            )

        private fun pendingIntentFlags(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}

internal fun SessionState.foregroundNotificationStatus(toolCalls: List<DhdToolCall>): String = when (this) {
    SessionState.Idle -> "Ready"
    is SessionState.Running -> notificationPurpose(
        preferredNotificationPurpose(toolCalls) ?: currentPurpose,
    )
    is SessionState.Paused -> "Paused · ${notificationPurpose(preferredNotificationPurpose(toolCalls) ?: currentPurpose)}"
    is SessionState.Stopped -> "Stopped"
    is SessionState.Completed -> "Completed"
}

private fun SessionState.preferredNotificationPurpose(toolCalls: List<DhdToolCall>): String? {
    val currentPurpose = when (this) {
        is SessionState.Running -> currentPurpose
        is SessionState.Paused -> currentPurpose
        else -> return null
    }
    if (currentPurpose.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true)) return null

    val metadataPurpose = when (this) {
        is SessionState.Running -> currentToolMetadataPurpose
        is SessionState.Paused -> currentToolMetadataPurpose
        else -> null
    }?.trim()?.takeIf(String::isNotBlank)
    return metadataPurpose ?: activeToolPurpose(toolCalls)
}

internal fun notificationPurpose(purpose: String): String = when {
    purpose.equals(CoordinatorCopy.PREPARING_REQUEST, ignoreCase = true) -> "Connecting to Codex…"
    purpose.equals(CoordinatorCopy.CODEX_PLANNING, ignoreCase = true) || purpose.equals(CoordinatorCopy.DHD_PLANNING, ignoreCase = true) -> "DHD-ing…"
    purpose.equals(CoordinatorCopy.WAITING_FOR_COMPANION, ignoreCase = true) -> "Companion not connected"
    purpose.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true) -> "DHD needs your attention"
    else -> purpose
}

internal fun SessionState.activeToolPurpose(toolCalls: List<DhdToolCall>): String? {
    val activeSessionId = when (this) {
        is SessionState.Running -> sessionId
        is SessionState.Paused -> sessionId
        else -> return null
    }
    return toolCalls.lastOrNull {
        it.sessionId == activeSessionId && it.status == DhdToolCallStatus.RUNNING
    }?.purpose
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_NOTIFICATION_PURPOSE_CHARS)
        ?.trimEnd()
        ?.takeIf(String::isNotBlank)
}

private const val MAX_NOTIFICATION_PURPOSE_CHARS = 160
