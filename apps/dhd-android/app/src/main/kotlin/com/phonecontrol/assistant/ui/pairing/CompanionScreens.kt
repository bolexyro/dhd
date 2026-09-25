@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.bridge.CompanionBridgeServer
import com.phonecontrol.assistant.bridge.pairing.PendingCompanionPairing
import com.phonecontrol.assistant.ui.components.CircleIconButton
import com.phonecontrol.assistant.ui.components.DhdConfirmDialog
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
    pending ?: return
    DhdConfirmDialog(
        title = "Allow desktop companion?",
        message = "${pending.desktopName} wants to connect to DHD on this local network. Approve only if you recognize this computer.",
        confirmLabel = "Approve",
        dismissLabel = "Reject",
        onConfirm = { bridgeServer.approvePendingCompanionPairing() },
        onDismiss = { bridgeServer.rejectPendingCompanionPairing() },
    )
}
