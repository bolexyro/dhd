@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.adb.DeveloperConnectionState
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.domain.ReasoningEffort
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
    onOpenTaskDisplays: () -> Unit = {},
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
            // Preferences Section
            item {
                SettingsSectionHeader("Preferences")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.settingsCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        // Appearance Selector Row (with DropdownMenu anchored to right side)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                                .clickable { isAppearanceMenuOpen = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_sun),
                                contentDescription = "Appearance",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Appearance",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = themeMode.label,
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isReasoningMenuOpen = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReasoningMeterIcon(
                                effort = ReasoningEffort.default,
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Reasoning levels",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${visibleReasoningEfforts.size} of ${ReasoningEffort.entries.size} enabled",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                                .clickable { onOpenApprovedApps() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_apps),
                                contentDescription = "Approved Apps",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Approved apps",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (isFullAccess) "Full access enabled" else "$enabledCount of ${apps.size} enabled",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right),
                                contentDescription = "Open Approved Apps",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Integrations & System Section
            item {
                SettingsSectionHeader("Integrations")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.settingsCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        // Display-over-other-apps overlay
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSetOverlayEnabled(!overlayEnabled) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bot),
                                contentDescription = "Display over other apps",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(
                                    text = "Display over other apps",
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (overlayPermissionGranted) {
                                        "Floating DHD bubble is ${if (overlayEnabled) "available" else "off"}"
                                    } else {
                                        "Permission required"
                                    },
                                    fontSize = 12.sp,
                                    color = if (!overlayPermissionGranted) colors.accentBlue else colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenCompanion() }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_laptop),
                                    contentDescription = "Desktop companion connection instructions",
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 14.dp),
                                ) {
                                    Text(
                                        text = "Connect desktop companion",
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "View connection instructions",
                                        fontSize = 12.sp,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Set up",
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron_right),
                                        contentDescription = "Open connection instructions",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }

                        if (phoneAccessSettingVisible) {
                            HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)

                            // DHD local phone connection row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                                    .clickable(enabled = developerStatus.state != DeveloperConnectionState.UNSUPPORTED) {
                                        onOpenPairing()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_terminal),
                                    contentDescription = "Wireless Debugging",
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 14.dp),
                                ) {
                                    Text(
                                        text = "DHD phone access",
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = when (developerStatus.state) {
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
                                        fontSize = 12.sp,
                                        color = colors.textSecondary,
                                        maxLines = 2,
                                        lineHeight = 17.sp,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Set up",
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron_right),
                                        contentDescription = "Open phone access instructions",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // About Section
            item {
                SettingsSectionHeader("About")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.settingsCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = "Version",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 14.dp),
                        ) {
                            Text(
                                text = "Version",
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "0.1.0 • Android SDK 35",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
