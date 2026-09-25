package com.phonecontrol.assistant.adb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.phonecontrol.assistant.MainActivity
import com.phonecontrol.assistant.developer.DhdAdbPairingService

/** Builds the Shizuku-style notification used only for the one-time pairing step. */
internal object DhdAdbPairingNotification {
    const val REMOTE_INPUT_RESULT_KEY = "dhd_adb_pairing_code"
    const val SEARCHING_NOTIFICATION_ID = 4207
    const val NOTIFICATION_ID = SEARCHING_NOTIFICATION_ID

    internal const val CHANNEL_ID = "dhd_adb_pairing"
    internal const val LEGACY_FOUND_NOTIFICATION_ID = 4211
    internal const val RESULT_NOTIFICATION_ID = 4208
    internal const val REQUEST_SUBMIT_CODE = 4209
    internal const val REQUEST_OPEN_APP = 4210
    internal const val REQUEST_STOP_SEARCHING = 4212

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "DHD Wireless Debugging pairing",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                description = "One-time pairing input for DHD phone access"
            },
        )
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun searchingNotification(context: Context): Notification {
        createChannel(context)
        return baseBuilder(
            context = context,
            title = "Searching for pairing service",
            message = null,
        )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop searching",
                    stopSearchingIntent(context),
                ).build(),
            )
            .build()
    }

    fun pairingServiceFoundNotification(
        context: Context,
        message: String = DEFAULT_FOUND_MESSAGE,
        alertOnTransition: Boolean = false,
    ): Notification {
        createChannel(context)
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY)
            .setLabel("Six-digit pairing code")
            .build()
        val submitIntent = PendingIntent.getForegroundService(
            context,
            REQUEST_SUBMIT_CODE,
            Intent(context, DhdAdbPairingService::class.java).setAction(
                DhdAdbPairingService.ACTION_SUBMIT_CODE,
            ),
            mutablePendingIntentFlags(),
        )
        return baseBuilder(
            context = context,
            title = "Pairing service found",
            message = message,
            alertOnUpdate = alertOnTransition,
        )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_dialog_info,
                    "Enter pairing code",
                    submitIntent,
                )
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
            .build()
    }

    fun workingNotification(context: Context): Notification {
        createChannel(context)
        return baseBuilder(
            context = context,
            title = "Pairing DHD with Wireless Debugging",
            message = null,
        )
            .setOngoing(true)
            .build()
    }

    fun showResult(context: Context) {
        createChannel(context)
        NotificationManagerCompat.from(context).notify(
            RESULT_NOTIFICATION_ID,
            baseBuilder(
                context = context,
                title = "DHD pairing complete",
                message = null,
            )
                .setOngoing(false)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancel(context: Context) {
        cancelAll(context)
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).apply {
            cancel(NOTIFICATION_ID)
            cancel(LEGACY_FOUND_NOTIFICATION_ID)
        }
    }

    fun cancelLegacyForegroundNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(LEGACY_FOUND_NOTIFICATION_ID)
    }

    fun showSearching(context: Context) {
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            searchingNotification(context),
        )
    }

    private fun baseBuilder(
        context: Context,
        title: String,
        message: String?,
        alertOnUpdate: Boolean = false,
    ): NotificationCompat.Builder {
        val openAppIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java),
            immutablePendingIntentFlags(),
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(!alertOnUpdate)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .also { builder ->
                if (!message.isNullOrBlank()) {
                    builder
                        .setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                }
            }
    }

    private fun stopSearchingIntent(context: Context): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            REQUEST_STOP_SEARCHING,
            Intent(context, DhdAdbPairingService::class.java).setAction(
                DhdAdbPairingService.ACTION_STOP,
            ),
            immutablePendingIntentFlags(),
        )

    private fun mutablePendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

    private fun immutablePendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

    private const val DEFAULT_FOUND_MESSAGE =
        "Enter the pairing code shown by Android."
}
