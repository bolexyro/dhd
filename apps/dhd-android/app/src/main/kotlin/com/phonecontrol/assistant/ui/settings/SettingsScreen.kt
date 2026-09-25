@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.adb.DeveloperConnectionState
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.ui.components.CircleIconButton
import com.phonecontrol.assistant.ui.components.SettingsCard
import com.phonecontrol.assistant.ui.components.SettingsChevron
import com.phonecontrol.assistant.ui.components.SettingsRow
import com.phonecontrol.assistant.ui.components.SettingsRowIcon
import com.phonecontrol.assistant.ui.components.SettingsSetUpAction
import com.phonecontrol.assistant.ui.components.reasoning.ReasoningMeterIcon
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    apps: List<InstalledUserApp>,
    permissions: AppPermissionRepository,
    developerStatus: DeveloperModeStatus,
    companionConnected: Boolean,
    themeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit,
    visibleReasoningEfforts: List<ReasoningEffort>,
    onSetReasoningEffortVisibility: (ReasoningEffort, Boolean) -> Unit,
    onOpenPairing: () -> Unit,
    onOpenApprovedApps: () -> Unit,
    onOpenCompanion: () -> Unit,
    overlayEnabled: Boolean,
    overlayPermissionGranted: Boolean,
    onSetOverlayEnabled: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val isFullAccess = remember(permissions.isFullAccessEnabled()) { permissions.isFullAccessEnabled() }
    val enabledCount = remember(permissions.enabledPackages()) { permissions.enabledPackages().size }
    var isAppearanceMenuOpen by remember { mutableStateOf(false) }
    var isReasoningMenuOpen by remember { mutableStateOf(false) }
    val phoneAccessSettingVisible = developerStatus.state !in setOf(
        DeveloperConnectionState.READY,
        DeveloperConnectionState.CONNECTING,
        DeveloperConnectionState.CHECKING,
    )

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
                title = { Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
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
            // Preferences Section
            item {
                SettingsSectionHeader("Preferences")
                SettingsCard {
                    Column {
                        // Appearance Selector Row (with DropdownMenu anchored to right side)
                        SettingsRow(
                            title = "Appearance",
                            subtitle = themeMode.label,
                            leading = { SettingsRowIcon(R.drawable.ic_sun, "Appearance") },
                            modifier = Modifier
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                                .clickable { isAppearanceMenuOpen = true },
                        ) {
                            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                                Icon(
                                    painter = painterResource(if (isAppearanceMenuOpen) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                                    contentDescription = "Select appearance",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )

                                DropdownMenu(
                                    expanded = isAppearanceMenuOpen,
                                    onDismissRequest = { isAppearanceMenuOpen = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = if (colors.isDark) Color(0xFF262628) else Color(0xFFFFFFFF),
                                    border = BorderStroke(
                                        1.dp,
                                        if (colors.isDark) Color(0xFF38383B) else Color(0xFFE5E7EB)
                                    ),
                                    modifier = Modifier.width(220.dp),
                                ) {
                                    ThemeMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = mode.label,
                                                    color = colors.textPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = if (themeMode == mode) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            },
                                            trailingIcon = {
                                                if (themeMode == mode) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_check),
                                                        contentDescription = "Selected",
                                                        tint = colors.textPrimary,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            },
                                            onClick = {
                                                onSelectThemeMode(mode)
                                                isAppearanceMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)

                        // Reasoning Levels Selector Row (with multi-select DropdownMenu anchored to right side)
                        SettingsRow(
                            title = "Reasoning levels",
                            subtitle = "${visibleReasoningEfforts.size} of ${ReasoningEffort.entries.size} enabled",
                            leading = {
                                ReasoningMeterIcon(
                                    effort = ReasoningEffort.default,
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                            modifier = Modifier.clickable { isReasoningMenuOpen = true },
                        ) {
                            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                                Icon(
                                    painter = painterResource(if (isReasoningMenuOpen) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                                    contentDescription = "Select reasoning levels",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )

                                DropdownMenu(
                                    expanded = isReasoningMenuOpen,
                                    onDismissRequest = { isReasoningMenuOpen = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = if (colors.isDark) Color(0xFF262628) else Color(0xFFFFFFFF),
                                    border = BorderStroke(
                                        1.dp,
                                        if (colors.isDark) Color(0xFF38383B) else Color(0xFFE5E7EB)
                                    ),
                                    modifier = Modifier.width(220.dp),
                                ) {
                                    ReasoningEffort.entries.forEach { effort ->
                                        val isVisible = effort in visibleReasoningEfforts
                                        val canToggle = !isVisible || visibleReasoningEfforts.size > 1
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = effort.label,
                                                    color = if (canToggle || isVisible) colors.textPrimary else colors.textSecondary.copy(
                                                        alpha = 0.5f
                                                    ),
                                                    fontSize = 15.sp,
                                                    fontWeight = if (isVisible) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            },
                                            trailingIcon = {
                                                if (isVisible) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_check),
                                                        contentDescription = "Selected",
                                                        tint = colors.textPrimary,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            },
                                            onClick = {
                                                if (canToggle) {
                                                    onSetReasoningEffortVisibility(effort, !isVisible)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)

                        // Approved Apps Row
                        SettingsRow(
                            title = "Approved apps",
                            subtitle = if (isFullAccess) "Full access enabled" else "$enabledCount of ${apps.size} enabled",
                            leading = { SettingsRowIcon(R.drawable.ic_apps, "Approved Apps") },
                            modifier = Modifier
                                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                                .clickable { onOpenApprovedApps() },
                        ) {
                            SettingsChevron("Open Approved Apps")
                        }
                    }
                }
            }

            // Integrations & System Section
            item {
                SettingsSectionHeader("Integrations")
                SettingsCard {
                    Column {
                        // Display-over-other-apps overlay
                        SettingsRow(
                            title = "Display over other apps",
                            subtitle = if (overlayPermissionGranted) {
                                "Floating DHD bubble is ${if (overlayEnabled) "available" else "off"}"
                            } else {
                                "Permission required"
                            },
                            leading = { SettingsRowIcon(R.drawable.ic_bot, "Display over other apps") },
                            modifier = Modifier.clickable { onSetOverlayEnabled(!overlayEnabled) },
                            subtitleColor = if (!overlayPermissionGranted) colors.accentBlue else colors.textSecondary,
                        ) {
                            Switch(
                                checked = overlayEnabled,
                                onCheckedChange = onSetOverlayEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = colors.accentBlue,
                                ),
                            )
                        }

                        // Connection instructions are only useful while the companion is offline.
                        if (!companionConnected) {
                            HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)
                            SettingsRow(
                                title = "Connect desktop companion",
                                subtitle = "View connection instructions",
                                leading = {
                                    SettingsRowIcon(R.drawable.ic_laptop, "Desktop companion connection instructions")
                                },
                                modifier = Modifier.clickable { onOpenCompanion() },
                            ) {
                                SettingsSetUpAction("Open connection instructions")
                            }
                        }

                        if (phoneAccessSettingVisible) {
                            HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)

                            // DHD local phone connection row
                            SettingsRow(
                                title = "DHD phone access",
                                subtitle = when (developerStatus.state) {
                                    DeveloperConnectionState.READY -> "Phone access is active"
                                    DeveloperConnectionState.CONNECTING,
                                    DeveloperConnectionState.CHECKING -> "Connecting phone access automatically…"

                                    DeveloperConnectionState.PAIRING_REQUIRED -> if (developerStatus.paired) {
                                        "Phone access needed; view the steps to reconnect"
                                    } else {
                                        "Set up phone access once"
                                    }

                                    DeveloperConnectionState.PAIRING_SEARCHING -> "Listening for the pairing service…"
                                    DeveloperConnectionState.PAIRING_SERVICE_FOUND -> "Check the DHD notification"
                                    DeveloperConnectionState.WIRELESS_DEBUGGING_OFF -> "Phone access needed; view the steps to reconnect"
                                    DeveloperConnectionState.UNSUPPORTED -> "DHD needs Android 11+ for phone access"
                                    DeveloperConnectionState.ERROR -> if (developerStatus.paired) {
                                        "Phone access needed; view the steps to reconnect"
                                    } else {
                                        "Set up phone access once"
                                    }
                                },
                                leading = { SettingsRowIcon(R.drawable.ic_terminal, "Wireless Debugging") },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                                    .clickable(enabled = developerStatus.state != DeveloperConnectionState.UNSUPPORTED) {
                                        onOpenPairing()
                                    },
                                subtitleMaxLines = 2,
                                subtitleLineHeight = 17.sp,
                            ) {
                                SettingsSetUpAction("Open phone access instructions")
                            }
                        }
                    }
                }
            }

            // About Section
            item {
                SettingsSectionHeader("About")
                SettingsCard {
                    SettingsRow(
                        title = "Version",
                        subtitle = "0.1.0 • Android SDK 35",
                        leading = { SettingsRowIcon(R.drawable.ic_info, "Version") },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsSectionHeader(title: String) {
    val colors = LocalAssistantColors.current
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SettingsSectionFooter(text: String) {
    val colors = LocalAssistantColors.current
    Text(
        text = text,
        fontSize = 12.sp,
        color = colors.textSecondary,
        lineHeight = 16.sp,
        modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp),
    )
}
