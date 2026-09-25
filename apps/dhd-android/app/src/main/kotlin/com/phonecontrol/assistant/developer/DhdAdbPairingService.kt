package com.phonecontrol.assistant.developer

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.adb.DeveloperConnectionState
import com.phonecontrol.assistant.adb.DhdAdbPairingNotification
import com.phonecontrol.assistant.adb.PhoneAccessController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun pairingServiceStartMode(action: String?): Int =
    if (action == DhdAdbPairingService.ACTION_SUBMIT_CODE) Service.START_NOT_STICKY else Service.START_REDELIVER_INTENT

/** Keeps the one-time Wireless Debugging pairing flow alive while Settings is foreground. */
class DhdAdbPairingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null
    private var pairingActive = false
    private var pairingServiceFound = false

    private val controller: PhoneAccessController
        get() = (application as PhoneControlApplication).container.phoneAccessController

    override fun onCreate() {
        super.onCreate()
        DhdAdbPairingNotification.createChannel(this)
        // A foreground service must have a notification immediately. The
        // first notification deliberately has no input action: it tells the
        // user that DHD is listening for Android's pairing service.
        startForegroundCompat(
            DhdAdbPairingNotification.searchingNotification(this),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                pairingActive = true
                pairingServiceFound = false
                observeController()
                controller.preparePairing()
                startForegroundCompat(
                    DhdAdbPairingNotification.searchingNotification(this),
                )
            }

            ACTION_SUBMIT_CODE -> {
                pairingActive = true
                observeController()
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(DhdAdbPairingNotification.REMOTE_INPUT_RESULT_KEY)
                    ?.toString()
                    ?.trim()
                if (code.isNullOrEmpty()) {
                    pairingServiceFound = true
                    updateNotification(
                        DhdAdbPairingNotification.pairingServiceFoundNotification(
                            this,
                            "Enter the six-digit code shown by Android.",
                        ),
                    )
                } else {
                    updateNotification(DhdAdbPairingNotification.workingNotification(this))
                    controller.pair(code)
                }
            }

            ACTION_STOP -> {
                pairingActive = false
                controller.cancelPairing()
                stopPairingService()
            }
        }
        return pairingServiceStartMode(intent?.action)
    }

    override fun onDestroy() {
        statusJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeController() {
        if (statusJob != null) return
        statusJob = serviceScope.launch {
            controller.status.collectLatest { status ->
                if (!pairingActive) return@collectLatest
                when (status.state) {
                    DeveloperConnectionState.READY -> {
                        DhdAdbPairingNotification.showResult(
                            this@DhdAdbPairingService,
                        )
                        pairingActive = false
                        stopPairingService()
                    }

                    DeveloperConnectionState.CONNECTING,
                    DeveloperConnectionState.CHECKING,
                    -> updateNotification(DhdAdbPairingNotification.workingNotification(this@DhdAdbPairingService))

                    DeveloperConnectionState.PAIRING_REQUIRED,
                    DeveloperConnectionState.PAIRING_SEARCHING,
                    -> {
                        pairingServiceFound = false
                        updateNotification(DhdAdbPairingNotification.searchingNotification(this@DhdAdbPairingService))
                    }

                    DeveloperConnectionState.PAIRING_SERVICE_FOUND ->
                        promoteToFoundNotification()

                    DeveloperConnectionState.WIRELESS_DEBUGGING_OFF -> {
                        pairingServiceFound = false
                        updateNotification(DhdAdbPairingNotification.searchingNotification(this@DhdAdbPairingService))
                    }

                    DeveloperConnectionState.ERROR ->
                        if (pairingServiceFound) {
                            updateNotification(
                                DhdAdbPairingNotification.pairingServiceFoundNotification(
                                    this@DhdAdbPairingService,
                                    status.message,
                                ),
                            )
                        } else {
                            updateNotification(DhdAdbPairingNotification.searchingNotification(this@DhdAdbPairingService))
                        }

                    DeveloperConnectionState.UNSUPPORTED -> {
                        pairingActive = false
                        stopPairingService()
                    }
                }
            }
        }
    }

    private fun updateNotification(notification: Notification) {
        NotificationManagerCompat.from(this).notify(
            DhdAdbPairingNotification.NOTIFICATION_ID,
            notification,
        )
    }

    private fun promoteToFoundNotification() {
        val alertOnTransition = !pairingServiceFound
        pairingServiceFound = true
        startForegroundCompat(
            DhdAdbPairingNotification.pairingServiceFoundNotification(
                context = this,
                alertOnTransition = alertOnTransition,
            ),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        DhdAdbPairingNotification.cancelLegacyForegroundNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                DhdAdbPairingNotification.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(DhdAdbPairingNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun stopPairingService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        DhdAdbPairingNotification.cancelAll(this)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.phonecontrol.assistant.action.START_ADB_PAIRING"
        const val ACTION_SUBMIT_CODE = "com.phonecontrol.assistant.action.SUBMIT_ADB_PAIRING_CODE"
        const val ACTION_STOP = "com.phonecontrol.assistant.action.STOP_ADB_PAIRING"

        fun startIntent(context: Context): Intent =
            Intent(context, DhdAdbPairingService::class.java).setAction(ACTION_START)
    }
}
