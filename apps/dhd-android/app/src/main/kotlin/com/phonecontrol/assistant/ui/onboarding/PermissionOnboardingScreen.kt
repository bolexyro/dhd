package com.phonecontrol.assistant.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.PermissionSetupStep
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.overlay.DhdBubblePreview
import com.phonecontrol.assistant.overlay.DhdComposerPreview
import com.phonecontrol.assistant.ui.displays.TaskPointerOverlay
import com.phonecontrol.assistant.ui.displays.liveDisplayCornerShape
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.assistantSwitchColors
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun PermissionOnboardingScreen(
    step: PermissionSetupStep,
    apps: List<InstalledUserApp>,
    permissions: AppPermissionRepository,
    onPrimaryAction: () -> Unit,
    onShowOverlayStep: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val colors = LocalAssistantColors.current
    val isComplete = step == PermissionSetupStep.COMPLETE
    val isAppAccess = step == PermissionSetupStep.APP_ACCESS
    val pageForStep = if (step == PermissionSetupStep.OVERLAY) 1 else 0
    val pagerState = rememberPagerState(initialPage = pageForStep) {
        if (isComplete || isAppAccess) 1 else 2
    }
    val currentStep by rememberUpdatedState(step)
    val latestOnShowOverlayStep by rememberUpdatedState(onShowOverlayStep)
    val latestOnBack by rememberUpdatedState(onBack)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(step) {
        if (isAppAccess) {
            pagerState.scrollToPage(0)
        } else if (!isComplete && pagerState.currentPage != pageForStep) {
            pagerState.animateScrollToPage(pageForStep)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            when {
                page == 1 && currentStep == PermissionSetupStep.NOTIFICATIONS -> latestOnShowOverlayStep()
                page == 0 && currentStep == PermissionSetupStep.OVERLAY -> latestOnBack()
            }
        }
    }

    val pageSwipeProgress = if (isComplete || isAppAccess) {
        0f
    } else {
        (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
    }

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            Button(
                onClick = onPrimaryAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(top = 8.dp, bottom = 16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentBlue,
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                if (isComplete) {
                    Text(
                        text = "Done",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else if (isAppAccess) {
                    Text(
                        text = "Finish setup",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Allow notifications",
                            modifier = Modifier.graphicsLayer {
                                alpha = 1f - pageSwipeProgress
                                translationX = -10.dp.toPx() * pageSwipeProgress
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Open settings",
                            modifier = Modifier.graphicsLayer {
                                alpha = pageSwipeProgress
                                translationX = 10.dp.toPx() * (1f - pageSwipeProgress)
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(vertical = 18.dp),
        ) {
            Spacer(Modifier.height(60.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = !isComplete && !isAppAccess,
            ) { page ->
                when {
                    isAppAccess -> AllowedAppsOnboardingPage(
                        apps = apps,
                        permissions = permissions,
                    )
                    isComplete -> PermissionPage(
                        title = "You're all set",
                        description = "You can change these permissions any time in Settings.",
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    page == 0 -> PermissionPage(
                        title = "Allow notifications",
                        description = "Notifications let you see what DHD is doing and stop a task without opening the app.",
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    else -> OverlayPermissionPage()
                }
            }

            if (!isComplete && !isAppAccess) {
                OnboardingPageIndicator(
                    selectedPage = pagerState.currentPage,
                    onPageSelected = { page ->
                        coroutineScope.launch { pagerState.animateScrollToPage(page) }
                    },
                )
            }
        }
    }
}

@Composable
private fun AllowedAppsOnboardingPage(
    apps: List<InstalledUserApp>,
    permissions: AppPermissionRepository,
) {
    val colors = LocalAssistantColors.current
    var isFullAccess by remember(permissions) { mutableStateOf(permissions.isFullAccessEnabled()) }
    var enabledPackages by remember(permissions) { mutableStateOf(permissions.enabledPackages()) }
    var showFullAccessConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    ) {
        Text(
            text = "Choose allowed apps",
            color = colors.textPrimary,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
        )
        Text(
            text = "Choose which apps DHD can open and use. You can change this later in Settings.",
            color = colors.textSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Top,
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.settingsCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_apps),
                            contentDescription = "Apps",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                            Text(
                                text = "Full access",
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                            )
                            Text(
                                text = "Allow DHD to use every installed app",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
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
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isFullAccess) "Apps (Full access is on)" else "Allowed apps",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = "Search apps",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                                cursorBrush = SolidColor(colors.accentBlue),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text("Search apps", color = colors.textSecondary, fontSize = 14.sp)
                                    }
                                    innerTextField()
                                },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            if (filteredApps.isEmpty()) {
                item {
                    Text(
                        text = if (searchQuery.isBlank()) "No launchable apps found." else "No matching apps found.",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            } else {
                itemsIndexed(
                    items = filteredApps,
                    key = { _, app -> app.packageName },
                    contentType = { _, _ -> "app" },
                ) { index, app ->
                    val isFirst = index == 0
                    val isLast = index == filteredApps.lastIndex
                    val rowShape = RoundedCornerShape(
                        topStart = if (isFirst) 20.dp else 0.dp,
                        topEnd = if (isFirst) 20.dp else 0.dp,
                        bottomEnd = if (isLast) 20.dp else 0.dp,
                        bottomStart = if (isLast) 20.dp else 0.dp,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.settingsCard, rowShape),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = app.label,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                            Switch(
                                checked = isFullAccess || app.packageName in enabledPackages,
                                enabled = !isFullAccess,
                                onCheckedChange = { checked ->
                                    permissions.setEnabled(app.packageName, checked)
                                    enabledPackages = permissions.enabledPackages()
                                },
                                colors = assistantSwitchColors(colors),
                            )
                        }
                        if (!isLast) {
                            HorizontalDivider(thickness = 2.dp, color = colors.cardDivider)
                        }
                    }
                }
            }
        }
    }

    if (showFullAccessConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showFullAccessConfirmDialog = false },
            containerColor = colors.surfaceCard,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Enable Full Access?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Full Access allows DHD to open, inspect, and operate any application installed on this device.\n\n" +
                        "This bypasses the per-app allowlist and lets DHD carry out tasks across all your apps.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFullAccessConfirmDialog = false
                        permissions.setFullAccessEnabled(true)
                        isFullAccess = true
                    },
                ) {
                    Text("Enable", color = colors.accentBlue, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullAccessConfirmDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun PermissionPage(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    Column(
        modifier = Modifier.fillMaxSize().then(modifier),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
        )
        Text(
            text = description,
            color = colors.textSecondary,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

@Composable
private fun OverlayPermissionPage() {
    val colors = LocalAssistantColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Enable the floating button",
            modifier = Modifier.padding(horizontal = 24.dp),
            color = colors.textPrimary,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
        )
        Text(
            text = "This lets the DHD bubble stay at the edge of your screen, so you can open it while using another app.",
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 9.dp),
            color = colors.textSecondary,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        BubblePreview(modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun OnboardingPageIndicator(
    selectedPage: Int,
    onPageSelected: (Int) -> Unit,
) {
    val colors = LocalAssistantColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) { page ->
            val selected = selectedPage == page
            val pillWidth by animateDpAsState(
                targetValue = if (selected) 28.dp else 8.dp,
                animationSpec = tween(durationMillis = 280),
                label = "onboarding-page-indicator-$page",
            )
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(32.dp)
                    .semantics {
                        contentDescription = if (page == 0) {
                            "Notifications page"
                        } else {
                            "Display over other apps page"
                        }
                    }
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (page == 0) "Show notifications step" else "Show floating button step",
                    ) { onPageSelected(page) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(pillWidth)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) colors.accentBlue else colors.textSecondary.copy(alpha = 0.32f),
                        ),
                )
            }
        }
    }
}

@Composable
private fun BubblePreview(modifier: Modifier = Modifier) {
    val colors = LocalAssistantColors.current
    var composerExpanded by remember { mutableStateOf(false) }
    var showDummyDisplay by remember { mutableStateOf(false) }
    var bubbleDockedLeft by remember { mutableStateOf(false) }
    val transition = updateTransition(
        targetState = composerExpanded,
        label = "onboarding-bubble-composer",
    )
    val expansion by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 520, easing = FastOutSlowInEasing) },
        label = "onboarding-bubble-composer-progress",
    ) { expanded -> if (expanded) 1f else 0f }

    BoxWithConstraints(
        modifier = modifier.semantics {
            contentDescription = "A preview of the DHD floating bubble at the edge of the screen."
        },
    ) {
        val density = LocalDensity.current
        val composerWidth = maxWidth.coerceAtMost(320.dp)
        val collapsedX = with(density) {
            if (bubbleDockedLeft) 0f else (maxWidth - 56.dp).toPx()
        }
        val expandedX = with(density) { ((maxWidth - composerWidth) / 2f).toPx() }
        val collapsedY = with(density) { ((maxHeight - 56.dp) / 2f).toPx() }
        // Match the real idle composer: 48dp controls plus 12dp padding on each side.
        val composerHeight = 72.dp
        val expandedY = with(density) { (maxHeight - composerHeight - 12.dp).coerceAtLeast(0.dp).toPx() }
        val composerAlpha = ((expansion - 0.18f) / 0.82f).coerceIn(0f, 1f)
        val composerScale = 0.96f + (0.04f * expansion)
        val bubbleAlpha = 1f - (expansion / 0.72f).coerceIn(0f, 1f)
        val bubbleX = with(density) {
            (collapsedX + ((expandedX + 12.dp.toPx()) - collapsedX) * expansion).roundToInt()
        }
        val bubbleY = with(density) {
            (collapsedY + ((expandedY + 12.dp.toPx()) - collapsedY) * expansion).roundToInt()
        }

        Canvas(Modifier.fillMaxSize()) {
            val bubbleRadius = 28.dp.toPx()
            val bubbleCenterX = if (bubbleDockedLeft) bubbleRadius else size.width - bubbleRadius
            val bubbleCenterY = size.height * 0.5f
            val arrowDirection = if (bubbleDockedLeft) 1f else -1f
            val arrowEnd = Offset(
                bubbleCenterX + arrowDirection * (bubbleRadius + 4.dp.toPx()),
                bubbleCenterY,
            )
            val arrowShaftEnd = Offset(arrowEnd.x + arrowDirection * 12.dp.toPx(), arrowEnd.y)
            val arrowApproach = Offset(arrowShaftEnd.x + arrowDirection * 40.dp.toPx(), arrowEnd.y)
            val arrowPath = Path().apply {
                moveTo(size.width * if (bubbleDockedLeft) 0.47f else 0.53f, size.height * 0.62f)
                cubicTo(
                    size.width * if (bubbleDockedLeft) 0.34f else 0.66f,
                    size.height * 0.66f,
                    arrowApproach.x,
                    arrowApproach.y,
                    arrowShaftEnd.x,
                    arrowShaftEnd.y,
                )
            }
            drawPath(
                path = arrowPath,
                color = colors.accentBlue.copy(alpha = 0.9f),
                alpha = bubbleAlpha,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 5.dp.toPx()),
                    ),
                ),
            )

            val tangentX = arrowEnd.x - arrowApproach.x
            val tangentY = arrowEnd.y - arrowApproach.y
            val tangentLength = hypot(tangentX, tangentY).coerceAtLeast(1f)
            val unitTangent = Offset(tangentX / tangentLength, tangentY / tangentLength)
            val normal = Offset(-unitTangent.y, unitTangent.x)
            val arrowHead = 8.dp.toPx()
            val arrowHalfWidth = 4.dp.toPx()
            val arrowBase = Offset(
                arrowEnd.x - unitTangent.x * arrowHead,
                arrowEnd.y - unitTangent.y * arrowHead,
            )
            drawLine(
                color = colors.accentBlue,
                start = arrowShaftEnd,
                end = arrowEnd,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                alpha = bubbleAlpha,
            )
            drawLine(
                color = colors.accentBlue,
                start = arrowEnd,
                end = Offset(
                    arrowBase.x + normal.x * arrowHalfWidth,
                    arrowBase.y + normal.y * arrowHalfWidth,
                ),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                alpha = bubbleAlpha,
            )
            drawLine(
                color = colors.accentBlue,
                start = arrowEnd,
                end = Offset(
                    arrowBase.x - normal.x * arrowHalfWidth,
                    arrowBase.y - normal.y * arrowHalfWidth,
                ),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                alpha = bubbleAlpha,
            )
        }

        if (!showDummyDisplay) {
            Text(
                text = if (composerExpanded) {
                    "Swipe to dock the bubble.\n\nLong press the DHD logo to show or hide the virtual display."
                } else {
                    "Tap to see how it shows over other apps"
                },
                modifier = Modifier
                    .align(if (bubbleDockedLeft) Alignment.CenterEnd else Alignment.CenterStart)
                    .width(maxWidth * 0.52f)
                    .padding(
                        start = if (bubbleDockedLeft) 0.dp else 24.dp,
                        end = if (bubbleDockedLeft) 24.dp else 0.dp,
                    )
                    .then(
                        if (composerExpanded) Modifier else Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = "Preview the DHD composer",
                        ) { composerExpanded = true },
                    ),
                color = colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(bubbleX, bubbleY) }
                .size(56.dp)
                .graphicsLayer { alpha = bubbleAlpha },
        ) {
            DhdBubblePreview(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Preview DHD over other apps",
                    ) { composerExpanded = true },
            )
        }

        if (composerExpanded || transition.currentState) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(expandedX.roundToInt(), expandedY.roundToInt()) }
                    .size(width = composerWidth, height = composerHeight)
                    .graphicsLayer {
                        alpha = composerAlpha
                        scaleX = composerScale
                        scaleY = composerScale
                        translationY = with(density) { 12.dp.toPx() } * (1f - expansion)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                DhdComposerPreview(
                    modifier = Modifier.fillMaxSize(),
                    onCollapse = {
                        showDummyDisplay = false
                        composerExpanded = false
                    },
                    onSwipeCollapse = { swipedLeft ->
                        bubbleDockedLeft = swipedLeft
                        showDummyDisplay = false
                        composerExpanded = false
                    },
                    onShowPreview = { showDummyDisplay = !showDummyDisplay },
                )
            }
        }

        AnimatedVisibility(
            visible = composerExpanded && showDummyDisplay,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = composerHeight + 22.dp),
            enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 5 },
            exit = fadeOut(tween(100)) + slideOutVertically(tween(130)) { it / 5 },
        ) {
            DummyVirtualDisplayCard(
                modifier = Modifier.width(composerWidth),
                onHide = { showDummyDisplay = false },
            )
        }
    }
}

@Composable
private fun DummyVirtualDisplayCard(
    modifier: Modifier = Modifier,
    onHide: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        val virtualScreenHeight = (maxHeight - 88.dp).coerceIn(112.dp, 224.dp)
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                onClick = onHide,
                shape = CircleShape,
                color = colors.composerBackground.copy(alpha = if (colors.isDark) 0.95f else 0.92f),
                border = BorderStroke(
                    0.8.dp,
                    colors.borderColor.copy(alpha = if (colors.isDark) 0.85f else 0.9f),
                ),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        tint = colors.textPrimary.copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "Close",
                        modifier = Modifier.padding(start = 5.dp),
                        color = colors.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = liveDisplayCornerShape(),
                color = colors.composerBackground.copy(alpha = if (colors.isDark) 0.98f else 0.97f),
                border = BorderStroke(
                    width = 0.8.dp,
                    color = colors.borderColor.copy(alpha = if (colors.isDark) 0.9f else 0.95f),
                ),
                shadowElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "Live view",
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(virtualScreenHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        DummyTaskApp(
                            modifier = Modifier
                                .width(virtualScreenHeight * (9f / 16f))
                                .height(virtualScreenHeight),
                        )
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(40.dp)
                                .semantics {
                                    contentDescription = "Full screen preview illustration"
                                },
                            shape = RoundedCornerShape(999.dp),
                            color = colors.composerBackground.copy(alpha = 0.92f),
                            border = BorderStroke(0.8.dp, colors.borderColor.copy(alpha = 0.85f)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fullscreen),
                                    contentDescription = null,
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DummyTaskApp(modifier: Modifier = Modifier) {
    val colors = LocalAssistantColors.current
    val event = remember {
        TaskPointerEvent.Calibration(
            sequence = 1L,
            sessionId = "onboarding-preview",
            x = 700,
            y = 920,
            displayWidth = 1080,
            displayHeight = 1920,
        )
    }
    Surface(
        modifier = modifier,
        shape = liveDisplayCornerShape(),
        color = if (colors.isDark) Color(0xFF10151D) else Color(0xFFF6F8FC),
        border = BorderStroke(0.8.dp, colors.borderColor),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("9:41", color = colors.textSecondary, fontSize = 8.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("●  ●  ●", color = colors.textSecondary, fontSize = 7.sp)
                }
                Text(
                    text = "Today",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = "A few things to do",
                    color = colors.textSecondary,
                    fontSize = 8.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                DummyTaskRow(text = "Pick up groceries", checked = true)
                Spacer(Modifier.height(10.dp))
                DummyTaskRow(text = "Send the parcel", checked = false)
                Spacer(Modifier.height(10.dp))
                DummyTaskRow(text = "Call Sam", checked = false)
            }
            TaskPointerOverlay(
                event = event,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DummyTaskRow(text: String, checked: Boolean) {
    val colors = LocalAssistantColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(13.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) colors.accentBlue else colors.sendButtonInactiveBg),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = text,
            color = colors.textSecondary,
            fontSize = 8.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
