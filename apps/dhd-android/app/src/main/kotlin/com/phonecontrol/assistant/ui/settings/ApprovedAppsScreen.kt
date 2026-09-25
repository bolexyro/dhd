@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.ui.components.FullAccessConfirmDialog
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.assistantSwitchColors

@Composable
fun ApprovedAppsScreen(
    apps: List<InstalledUserApp>,
    permissions: AppPermissionRepository,
    onBack: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    var isFullAccess by remember { mutableStateOf(permissions.isFullAccessEnabled()) }
    var showFullAccessConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var enabledPackages by remember { mutableStateOf(permissions.enabledPackages()) }
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = isSearchOpen) {
        isSearchOpen = false
        searchQuery = ""
    }

    LaunchedEffect(isSearchOpen) {
        if (isSearchOpen) {
            focusRequester.requestFocus()
        }
    }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            AnimatedContent(
                targetState = isSearchOpen,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(140))
                },
                label = "search_header_transition",
            ) { searchActive ->
                if (searchActive) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = colors.background,
                        ),
                        title = {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = colors.composerBackground,
                                border = BorderStroke(1.dp, colors.borderColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_search),
                                        contentDescription = "Search",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        textStyle = TextStyle(
                                            color = colors.textPrimary,
                                            fontSize = 15.sp,
                                        ),
                                        cursorBrush = SolidColor(colors.accentBlue),
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(focusRequester),
                                        singleLine = true,
                                        decorationBox = { innerTextField ->
                                            if (searchQuery.isEmpty()) {
                                                Text(
                                                    text = "Search apps…",
                                                    color = colors.textSecondary,
                                                    fontSize = 15.sp,
                                                )
                                            }
                                            innerTextField()
                                        },
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Transparent,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .clickable { searchQuery = "" },
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_close),
                                                    contentDescription = "Clear search",
                                                    tint = colors.textSecondary,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    isSearchOpen = false
                                    searchQuery = ""
                                },
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                Text(
                                    text = "Cancel",
                                    color = colors.accentBlue,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        },
                    )
                } else {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = colors.background,
                            titleContentColor = colors.textPrimary,
                            actionIconContentColor = colors.textPrimary,
                            navigationIconContentColor = colors.textPrimary,
                        ),
                        title = { Text("Approved Apps", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
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
                        actions = {
                            Surface(
                                shape = CircleShape,
                                color = colors.composerBackground,
                                border = BorderStroke(1.dp, colors.borderColor),
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { isSearchOpen = true },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_search),
                                        contentDescription = "Search",
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
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
            // Full Access Section
            item {
                SettingsSectionHeader("Global access")
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
                            painter = painterResource(R.drawable.ic_apps),
                            contentDescription = "Apps",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 14.dp),
                        ) {
                            Text(
                                text = "Full access",
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Allow access to all installed apps",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = isFullAccess,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    showFullAccessConfirmDialog = true
                                } else {
                                    permissions.setFullAccessEnabled(false)
                                    isFullAccess = false
                                }
                            },
                            colors = assistantSwitchColors(colors),
                        )
                    }
                }
            }

            // Per-App List Section
            item {
                SettingsSectionHeader("Allowed apps")
                if (filteredApps.isEmpty()) {
                    Text(
                        text = if (searchQuery.isBlank()) "No launchable user apps found." else "No matching apps found.",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    )
                } else {
                    // Continuous joined card container for all apps with black dividers
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = colors.settingsCard,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            filteredApps.forEachIndexed { index, app ->
                                val enabled = if (isFullAccess) true else (app.packageName in enabledPackages)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = app.label,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Switch(
                                        checked = enabled,
                                        enabled = !isFullAccess,
                                        onCheckedChange = { checked ->
                                            permissions.setEnabled(app.packageName, checked)
                                            enabledPackages = permissions.enabledPackages()
                                        },
                                        colors = assistantSwitchColors(colors),
                                    )
                                }
                                if (index < filteredApps.lastIndex) {
                                    HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFullAccessConfirmDialog) {
        FullAccessConfirmDialog(
            onConfirm = {
                showFullAccessConfirmDialog = false
                permissions.setFullAccessEnabled(true)
                isFullAccess = true
            },
            onDismiss = { showFullAccessConfirmDialog = false },
        )
    }
}
