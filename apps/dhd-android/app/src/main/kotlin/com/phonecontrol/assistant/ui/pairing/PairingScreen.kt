@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui.pairing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.adb.DeveloperConnectionState
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.ui.components.CircleIconButton
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
fun PairingScreen(
    status: DeveloperModeStatus,
    onStartPairingNotification: () -> Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    var notificationUnavailable by rememberSaveable { mutableStateOf(false) }
    var automaticStartAttempted by rememberSaveable { mutableStateOf(false) }

    fun startPairingNotification() {
        notificationUnavailable = !onStartPairingNotification()
    }

    val pairingInProgress = status.state in setOf(
        DeveloperConnectionState.PAIRING_SEARCHING,
        DeveloperConnectionState.PAIRING_SERVICE_FOUND,
    )
    val screenTitle = when {
        status.state == DeveloperConnectionState.READY -> "Phone access"
        status.paired -> "Reconnect phone"
        else -> "Connect phone"
    }

    LaunchedEffect(status.state, status.paired) {
        val needsPairing = !status.paired && status.state in setOf(
            DeveloperConnectionState.PAIRING_REQUIRED,
            DeveloperConnectionState.ERROR,
        )
        if (!automaticStartAttempted && needsPairing) {
            automaticStartAttempted = true
            startPairingNotification()
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textPrimary,
                    actionIconContentColor = colors.textPrimary,
                ),
                title = { Text(screenTitle, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    CircleIconButton(
                        icon = R.drawable.ic_arrow_back,
                        contentDescription = "Back",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        ) {
            item {
                PairingStatusCard(status = status)
            }

            if (notificationUnavailable && !status.paired) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surfaceCard,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                text = "DHD needs permission to show the pairing code from Android. Allow notifications, then try again.",
                                color = colors.textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                            )
                            Button(
                                onClick = { startPairingNotification() },
                                modifier = Modifier.padding(top = 8.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentBlue,
                                    contentColor = Color.White,
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Retry pairing notification")
                            }
                        }
                    }
                }
            }

            if (status.phoneAccessInterrupted) {
                item {
                    PairingInstruction(
                        number = 1,
                        text = "Turn on Wi-Fi and connect to a network.",
                    )
                }
                item {
                    PairingInstruction(
                        number = 2,
                        text = "Turn on Wireless debugging in Android Settings → Developer options.",
                        actionLabel = "Open Android settings",
                        onAction = onOpenDeveloperOptions,
                    )
                }
                item {
                    PairingInstruction(
                        number = 3,
                        text = "Return to DHD. We'll reconnect automatically.",
                    )
                }
            } else if (!status.paired || pairingInProgress) {
                item {
                    PairingInstruction(
                        number = 1,
                        text = "Open Android Settings → Developer options → Wireless debugging. Choose Pair device with pairing code.",
                        actionLabel = "Open Android settings",
                        onAction = onOpenDeveloperOptions,
                    )
                }
                item {
                    PairingInstruction(
                        number = 2,
                        text = "Enter the six-digit code Android shows in the DHD notification.",
                    )
                }
                item {
                    PairingInstruction(
                        number = 3,
                        text = "Return to DHD. We'll finish connecting your phone automatically.",
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingStatusCard(status: DeveloperModeStatus) {
    val colors = LocalAssistantColors.current
    val statusColor = when (status.state) {
        DeveloperConnectionState.READY -> colors.accentGreen
        DeveloperConnectionState.PAIRING_SEARCHING,
        DeveloperConnectionState.PAIRING_SERVICE_FOUND,
        DeveloperConnectionState.CONNECTING,
        DeveloperConnectionState.CHECKING -> colors.accentBlue

        DeveloperConnectionState.PAIRING_REQUIRED -> if (status.paired) {
            colors.warningAmber
        } else {
            colors.accentBlue
        }

        DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
        DeveloperConnectionState.ERROR -> colors.warningAmber

        DeveloperConnectionState.UNSUPPORTED -> colors.textSecondary
    }
    val statusTitle = when (status.state) {
        DeveloperConnectionState.READY -> "Phone access is active"
        DeveloperConnectionState.PAIRING_SEARCHING -> "Connecting your phone"
        DeveloperConnectionState.PAIRING_SERVICE_FOUND -> "Pairing code ready"
        DeveloperConnectionState.PAIRING_REQUIRED -> if (status.paired) {
            "Phone access needed"
        } else {
            "Ready to connect your phone"
        }

        DeveloperConnectionState.CONNECTING -> "Reconnecting to your phone"
        DeveloperConnectionState.CHECKING -> "Checking phone access"
        DeveloperConnectionState.WIRELESS_DEBUGGING_OFF -> "Phone access needed"
        DeveloperConnectionState.ERROR -> status.recoveryTitle
        DeveloperConnectionState.UNSUPPORTED -> status.recoveryTitle
    }
    val statusDetail = when (status.state) {
        DeveloperConnectionState.READY -> "DHD can use phone controls."
        DeveloperConnectionState.PAIRING_SEARCHING -> "Follow the connection steps in the DHD notification."
        DeveloperConnectionState.PAIRING_SERVICE_FOUND -> "Enter the six-digit code shown by Android in the DHD notification."
        DeveloperConnectionState.CONNECTING -> "DHD is reconnecting automatically."
        DeveloperConnectionState.CHECKING -> "DHD is checking whether phone access is available."
        else -> status.recoveryDetail
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = statusColor.copy(alpha = if (colors.isDark) 0.18f else 0.10f),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .background(statusColor, CircleShape),
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = statusTitle,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = statusDetail,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
internal fun PairingInstruction(
    number: Int,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalAssistantColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = colors.accentBlue.copy(alpha = 0.18f),
            modifier = Modifier.size(30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    color = colors.accentBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                text = text,
                color = colors.textPrimary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 10.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentBlue,
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}
