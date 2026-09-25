@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui.pairing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.bridge.CompanionBridgeServer
import com.phonecontrol.assistant.bridge.pairing.PendingCompanionPairing
import com.phonecontrol.assistant.ui.settings.SettingsSectionFooter
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
fun CompanionInstructionsScreen(
    onBack: () -> Unit,
) {
    val colors = LocalAssistantColors.current

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textPrimary,
                ),
                title = { Text("Connect desktop companion", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    Surface(
                        shape = CircleShape,
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Back",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        ) {
            item {
                PairingInstruction(
                    number = 1,
                    text = "Open DHD Companion on your computer and go to Connection.",
                )
            }
            item {
                PairingInstruction(
                    number = 2,
                    text = "Keep your phone and computer on the same Wi-Fi network.",
                )
            }
            item {
                PairingInstruction(
                    number = 3,
                    text = "On the computer, tap Find my phone, then tap Connect next to this phone.",
                )
            }
            item {
                PairingInstruction(
                    number = 4,
                    text = "When this phone asks, tap Approve. You're connected—there's no code to enter.",
                )
            }
            item {
                SettingsSectionFooter(
                    "If the phone is not listed, check the local network and the computer's firewall, then use Refresh phones again.",
                )
            }
        }
    }
}

@Composable
fun CompanionPairingApprovalDialog(
    pending: PendingCompanionPairing?,
    bridgeServer: CompanionBridgeServer,
) {
    val colors = LocalAssistantColors.current
    pending ?: return
    AlertDialog(
        onDismissRequest = { bridgeServer.rejectPendingCompanionPairing() },
        containerColor = colors.surfaceCard,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = RoundedCornerShape(20.dp),
        title = { Text("Allow desktop companion?", fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                text = "${pending.desktopName} wants to connect to DHD on this local network. Approve only if you recognize this computer.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = { bridgeServer.approvePendingCompanionPairing() }) {
                Text("Approve", color = colors.accentBlue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = { bridgeServer.rejectPendingCompanionPairing() }) {
                Text("Reject", color = colors.textSecondary)
            }
        },
    )
}
