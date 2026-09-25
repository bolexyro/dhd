@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui

import android.view.Surface as AndroidSurface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.data.TimelineItem
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.ui.components.reasoning.FastModeButton
import com.phonecontrol.assistant.ui.components.reasoning.ReasoningEffortButton
import com.phonecontrol.assistant.ui.components.reasoning.ReasoningEffortOverlay
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreview
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewPlaceholder
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.displays.surface.PreviewSurfaceDestroyed
import com.phonecontrol.assistant.ui.theme.AssistantColorScheme
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import com.phonecontrol.assistant.ui.theme.toolActivityColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt
import kotlin.random.Random

private const val STALE_CONVERSATION_COUNTDOWN_SECONDS = 5

@Composable
internal fun ConversationExpiryDialog(
    onKeep: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    var secondsRemaining by remember {
        mutableStateOf(STALE_CONVERSATION_COUNTDOWN_SECONDS)
    }

    LaunchedEffect(Unit) {
        for (remaining in STALE_CONVERSATION_COUNTDOWN_SECONDS downTo 1) {
            secondsRemaining = remaining
            delay(1_000L)
        }
        onClear()
    }

    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = colors.surfaceCard,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = RoundedCornerShape(20.dp),
        title = { Text("This conversation is stale", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier.size(112.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = {
                            secondsRemaining.toFloat() / STALE_CONVERSATION_COUNTDOWN_SECONDS
                        },
                        modifier = Modifier.fillMaxSize(),
                        color = colors.accentBlue,
                        trackColor = colors.composerBackground,
                        strokeWidth = 5.dp,
                    )
                    Text(
                        text = secondsRemaining.toString(),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Light,
                        color = colors.textPrimary,
                    )
                }
                Text(
                    "This chat has been inactive for 3 hours and will clear automatically. " +
                            "Keep it to continue this conversation.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) {
                Text("Clear now", color = colors.accentBlue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("Keep conversation", color = colors.textSecondary)
            }
        },
    )
}

@Composable
fun AssistantScreen(
    store: ConversationStore,
    coordinator: SessionCoordinator,
    @Suppress("UNUSED_PARAMETER") initialConversationId: String?,
    onRunRequest: (String, String?, String?, Boolean) -> Unit,
    reasoningEffort: ReasoningEffort,
    visibleReasoningEfforts: List<ReasoningEffort>,
    onSelectReasoningEffort: (ReasoningEffort) -> Unit,
    fastMode: Boolean,
    onSetFastMode: (Boolean) -> Unit,
    onStopSession: () -> Unit,
    onContinueSession: () -> Unit = {},
    onAcknowledgeAttention: () -> Boolean,
    onSteerRequest: (String) -> Boolean,
    onOpenSettings: () -> Unit,
    onOpenPhoneAccess: () -> Unit,
    onOpenTaskDisplays: () -> Unit = {},
    onStartFresh: () -> Unit,
    developerStatus: DeveloperModeStatus,
    companionConnected: Boolean,
    onOpenCompanion: () -> Unit,
    previewState: LiveDisplayPreviewState? = null,
    onPreviewSurfaceAvailable: (AndroidSurface) -> Unit = {},
    onPreviewSurfaceDestroyed: PreviewSurfaceDestroyed = { _, release -> release() },
    onOpenPreview: (String) -> Unit = {},
    expandedPreviewSessionKey: String? = null,
) {
    val colors = LocalAssistantColors.current
    val state by coordinator.state.collectAsState()
    val toolCalls by coordinator.toolCalls.collectAsState()
    val timeline by store.timeline(DHD_CONVERSATION_ID).collectAsState()
    val active = state.isActive
    val combinePhoneAndCompanionRecovery = shouldCombineRecoveryBanners(
        state = state,
        developerStatus = developerStatus,
        companionConnected = companionConnected,
    )
    val showTopRecoveryBanner = shouldShowTopRecoveryBanner(
        state = state,
        developerStatus = developerStatus,
        companionConnected = companionConnected,
    )
    val phoneRecoveryShownAtTop = showTopRecoveryBanner &&
            (developerStatus.requiresUserAction || state.showsPhoneAccessRecovery())
    val companionRecoveryShownAtTop = showTopRecoveryBanner && !companionConnected
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val runningState = state as? SessionState.Running
    val companionWaitSeconds = if (combinePhoneAndCompanionRecovery && runningState != null) {
        rememberElapsedSeconds(runningState.startedAtEpochMs, runningState.elapsedBeforeStartMs)
    } else {
        null
    }
    val canSteer = state is SessionState.Running
    var showStartFreshConfirmation by rememberSaveable { mutableStateOf(false) }
    var steerDrafts by rememberSaveable(stateSaver = steerDraftsSaver) {
        mutableStateOf(emptyList<PendingSteerDraft>())
    }
    var steerDraftSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var carrySteerDraftsToNextRun by rememberSaveable { mutableStateOf(false) }
    var composerEditText by rememberSaveable { mutableStateOf<String?>(null) }
    var showReasoningSelector by rememberSaveable { mutableStateOf(false) }
    var topRecoverySlotHeightPx by remember { mutableStateOf(0) }
    val activeSessionId = state.sessionIdOrNull
    val currentToolCall = toolCalls.lastOrNull {
        it.sessionId == activeSessionId && it.status == DhdToolCallStatus.RUNNING
    }
    LaunchedEffect(activeSessionId) {
        val next = SteerDraftQueue(steerDrafts, steerDraftSessionId, carrySteerDraftsToNextRun)
            .forActiveSession(activeSessionId)
        steerDrafts = next.drafts
        steerDraftSessionId = next.sessionId
        carrySteerDraftsToNextRun = next.carryToNextRun
    }
    LaunchedEffect(state) {
        val completed = state as? SessionState.Completed ?: return@LaunchedEffect
        val promotion = SteerDraftQueue(steerDrafts, steerDraftSessionId, carrySteerDraftsToNextRun)
            .promoteAfterCompletion(completed.sessionId)
            ?: return@LaunchedEffect
        steerDrafts = promotion.queue.drafts
        carrySteerDraftsToNextRun = promotion.queue.carryToNextRun
        onRunRequest(
            promotion.draft.text.trim(),
            DHD_CONVERSATION_ID,
            promotion.draft.reasoningEffort,
            promotion.draft.fastMode,
        )
    }
    val continuationRunId = state.continuationSessionIdOrNullForUi()
    val activeTaskRunIds = activeTaskRunIds(timeline, active, activeSessionId, continuationRunId)
    val recentTimeline = recentTimelineItems(
        timeline = timeline,
        nowEpochMs = System.currentTimeMillis(),
        active = active,
        activeTaskRunIds = activeTaskRunIds,
    )

    Scaffold(
        containerColor = colors.background,
        contentColor = colors.textPrimary,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    actionIconContentColor = colors.textPrimary,
                ),
                title = {
                    Text(
                        text = "DHD",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
                actions = {
                    // Start fresh circular button (48dp)
                    Surface(
                        shape = CircleShape,
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenTaskDisplays),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_laptop),
                                contentDescription = "Task displays",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !active) { showStartFreshConfirmation = true },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_compose_new),
                                contentDescription = "Start fresh",
                                tint = if (active) colors.textSecondary.copy(alpha = 0.4f) else colors.textPrimary,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    // Settings circular button (48dp)
                    Surface(
                        shape = CircleShape,
                        color = colors.composerBackground,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onOpenSettings() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "Settings",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                    .background(colors.background)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                if (showTopRecoveryBanner) {
                    // Keep the island visually floating while giving it a
                    // real layout slot. Conversation content is measured
                    // below it instead of rendering underneath an overlay.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { topRecoverySlotHeightPx = it.size.height },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 16.dp, end = 16.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            if (combinePhoneAndCompanionRecovery) {
                                CombinedRecoveryCard(
                                    phoneStatus = developerStatus,
                                    companionWaitSeconds = companionWaitSeconds,
                                    onOpenPhoneAccess = onOpenPhoneAccess,
                                    onOpenCompanion = onOpenCompanion,
                                    compact = keyboardVisible || recentTimeline.isEmpty(),
                                    modifier = Modifier.widthIn(max = 520.dp),
                                )
                            } else if (phoneRecoveryShownAtTop) {
                                DeveloperConnectionRecoveryCard(
                                    status = developerStatus,
                                    onOpenPhoneAccess = onOpenPhoneAccess,
                                    compact = recentTimeline.isEmpty(),
                                    modifier = Modifier.widthIn(max = 520.dp),
                                )
                            } else {
                                CompanionRecoveryCard(
                                    elapsedSeconds = null,
                                    onOpenCompanion = onOpenCompanion,
                                    compact = recentTimeline.isEmpty(),
                                    modifier = Modifier.widthIn(max = 520.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // Chat timeline stays strictly above composer area with soft fade at the bottom
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fadePx = 24.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color.Black,
                                    (1f - (fadePx / size.height).coerceIn(0f, 1f)) to Color.Black,
                                    1.0f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    if (recentTimeline.isEmpty()) {
                        EmptyChat(
                            modifier = Modifier.fillMaxSize(),
                            topReservedSpacePx = if (showTopRecoveryBanner) topRecoverySlotHeightPx else 0,
                            onSelectPrompt = { prompt ->
                                onRunRequest(
                                    prompt,
                                    DHD_CONVERSATION_ID,
                                    reasoningEffort.codexValue,
                                    fastMode,
                                )
                            },
                        )
                    } else {
                        ConversationTimeline(
                            timeline = recentTimeline,
                            state = state,
                            currentToolCall = currentToolCall,
                            developerStatus = developerStatus,
                            phoneRecoveryShownAtTop = phoneRecoveryShownAtTop,
                            companionRecoveryShownAtTop = companionRecoveryShownAtTop,
                            companionConnected = companionConnected,
                            onOpenPhoneAccess = onOpenPhoneAccess,
                            onOpenCompanion = onOpenCompanion,
                            onStopSession = onStopSession,
                            onAcknowledgeAttention = onAcknowledgeAttention,
                            previewState = previewState,
                            onPreviewSurfaceAvailable = onPreviewSurfaceAvailable,
                            onPreviewSurfaceDestroyed = onPreviewSurfaceDestroyed,
                            onOpenPreview = onOpenPreview,
                            expandedPreviewSessionKey = expandedPreviewSessionKey,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 12.dp,
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 44.dp,
                            ),
                        )
                    }
                }

                // Bottom composer bar with solid background - nothing scrolls behind or between composer and keyboard
                Surface(
                    color = colors.background,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = if (showReasoningSelector) 0f else 1f
                        },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (canSteer && steerDrafts.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 192.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                steerDrafts.forEachIndexed { index, draft ->
                                    SteerDraftBar(
                                        text = draft.text,
                                        onSteer = {
                                            if (onSteerRequest(draft.text)) {
                                                steerDrafts = steerDrafts.toMutableList().also {
                                                    it.removeAt(index)
                                                }
                                                if (steerDrafts.isEmpty()) {
                                                    steerDraftSessionId = null
                                                    carrySteerDraftsToNextRun = false
                                                }
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                        onDismiss = {
                                            steerDrafts = steerDrafts.toMutableList().also {
                                                it.removeAt(index)
                                            }
                                            if (steerDrafts.isEmpty()) {
                                                steerDraftSessionId = null
                                                carrySteerDraftsToNextRun = false
                                            }
                                        },
                                        onEdit = {
                                            composerEditText = draft.text
                                            steerDrafts = steerDrafts.toMutableList().also {
                                                it.removeAt(index)
                                            }
                                            if (steerDrafts.isEmpty()) {
                                                steerDraftSessionId = null
                                                carrySteerDraftsToNextRun = false
                                            }
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        RequestComposer(
                            enabled = !active || canSteer,
                            isActive = active,
                            canSteer = canSteer,
                            reasoningEffort = reasoningEffort,
                            visibleReasoningEfforts = visibleReasoningEfforts,
                            showReasoningSelector = showReasoningSelector,
                            onOpenReasoningSelector = { showReasoningSelector = true },
                            fastMode = fastMode,
                            onSetFastMode = onSetFastMode,
                            onExpandedChanged = { expanded ->
                                if (!expanded) showReasoningSelector = false
                            },
                            editText = composerEditText,
                            onEditTextConsumed = { composerEditText = null },
                            onSend = { request ->
                                if (canSteer) {
                                    steerDrafts = steerDrafts + PendingSteerDraft(
                                        text = request,
                                        reasoningEffort = reasoningEffort.codexValue,
                                        fastMode = fastMode,
                                    )
                                    steerDraftSessionId = activeSessionId
                                    true
                                } else {
                                    onRunRequest(
                                        request,
                                        DHD_CONVERSATION_ID,
                                        reasoningEffort.codexValue,
                                        fastMode,
                                    )
                                    true
                                }
                            },
                            onStop = onStopSession,
                            canContinue = state is SessionState.Stopped && recentTimeline.isNotEmpty(),
                            onContinue = onContinueSession,
                        )
                    }
                }
            }

            if (showReasoningSelector) {
                ReasoningEffortOverlay(
                    selectedEffort = reasoningEffort,
                    visibleEfforts = visibleReasoningEfforts,
                    onSelect = onSelectReasoningEffort,
                    onDismiss = { showReasoningSelector = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                )
            }
        }
    }

    if (showStartFreshConfirmation) {
        AlertDialog(
            onDismissRequest = { showStartFreshConfirmation = false },
            containerColor = colors.surfaceCard,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Start fresh?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "This clears the DHD conversation timeline and rotates its stored Codex thread " +
                            "binding. App permissions and DHD's local phone connection remain unchanged.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStartFreshConfirmation = false
                        steerDrafts = emptyList()
                        steerDraftSessionId = null
                        carrySteerDraftsToNextRun = false
                        composerEditText = null
                        coordinator.reset()
                        onStartFresh()
                    },
                ) {
                    Text("Start fresh", color = colors.accentBlue, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartFreshConfirmation = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun EmptyChat(
    modifier: Modifier = Modifier,
    topReservedSpacePx: Int = 0,
    onSelectPrompt: (String) -> Unit = {},
) {
    SubcomposeLayout(
        modifier = modifier.padding(horizontal = 24.dp),
    ) { constraints ->
        val layoutWidth = constraints.maxWidth
        val hasBoundedHeight = constraints.maxHeight != Constraints.Infinity
        val layoutHeight = if (hasBoundedHeight) constraints.maxHeight else 0
        val content = subcompose("centered-content") {
            EmptyChatContent(
                modifier = Modifier.widthIn(max = 480.dp),
                onSelectPrompt = onSelectPrompt,
            )
        }.single().measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )

        if (!hasBoundedHeight) {
            layout(layoutWidth, content.height) {
                content.placeRelative(
                    x = ((layoutWidth - content.width) / 2).coerceAtLeast(0),
                    y = 0,
                )
            }
        } else if (content.height <= layoutHeight) {
            val reserved = topReservedSpacePx.coerceAtMost(layoutHeight)
            val idealTop = ((reserved + layoutHeight) / 2f - reserved - content.height / 2f)
                .roundToInt()
            val top = idealTop.coerceIn(0, (layoutHeight - content.height).coerceAtLeast(0))
            layout(layoutWidth, layoutHeight) {
                content.placeRelative(
                    x = ((layoutWidth - content.width) / 2).coerceAtLeast(0),
                    y = top,
                )
            }
        } else {
            val scrollableContent = subcompose("scrollable-content") {
                EmptyChatContent(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    onSelectPrompt = onSelectPrompt,
                )
            }.single().measure(
                constraints.copy(minWidth = 0, minHeight = 0),
            )
            val height = if (hasBoundedHeight) layoutHeight else scrollableContent.height
            layout(layoutWidth, height) {
                scrollableContent.placeRelative(
                    x = ((layoutWidth - scrollableContent.width) / 2).coerceAtLeast(0),
                    y = 0,
                )
            }
        }
    }
}

@Composable
private fun EmptyChatContent(
    modifier: Modifier = Modifier,
    onSelectPrompt: (String) -> Unit,
) {
    val colors = LocalAssistantColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "What can I do on your phone?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Ask DHD to operate apps on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PromptSuggestionChip(
                text = "Get me jollof rice and chicken under 6k",
                onClick = { onSelectPrompt("Get me jollof rice and chicken under 6k") },
            )
            PromptSuggestionChip(
                text = "Play me \"Jesus be the name\" on Spotify",
                onClick = { onSelectPrompt("Play me \"Jesus be the name\" on Spotify") },
            )
        }
    }
}

@Composable
private fun PromptSuggestionChip(
    text: String,
    onClick: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

internal data class TaskGroup(
    val id: String,
    val runIds: Set<String>,
    val userMessage: TimelineItem.Message?,
    val steerMessages: List<TimelineItem.Message>,
    val activities: List<TimelineItem.Activity>,
    val assistantMessages: List<TimelineItem.Message>,
    val timestampEpochMs: Long,
)

private class TaskGroupBuilder(val id: String) {
    val runIds = linkedSetOf<String>()
    var userMessage: TimelineItem.Message? = null
    val steerMessages = mutableListOf<TimelineItem.Message>()
    val activities = mutableListOf<TimelineItem.Activity>()
    val assistantMessages = mutableListOf<TimelineItem.Message>()
    var timestampEpochMs: Long = Long.MAX_VALUE

    fun addTimestamp(timestamp: Long) {
        timestampEpochMs = minOf(timestampEpochMs, timestamp)
    }

    fun build(): TaskGroup = TaskGroup(
        id = id,
        runIds = runIds.toSet(),
        userMessage = userMessage,
        steerMessages = steerMessages.toList(),
        activities = activities.toList(),
        assistantMessages = assistantMessages.toList(),
        timestampEpochMs = timestampEpochMs,
    )
}

internal fun groupTimeline(
    timeline: List<TimelineItem>,
    continuationRunId: String? = null,
): List<TaskGroup> {
    val builders = linkedMapOf<String, TaskGroupBuilder>()
    timeline.forEach { item ->
        val key = when (item) {
            is TimelineItem.Message -> item.runId ?: "message-${item.id}"
            is TimelineItem.Activity -> item.runId
        }
        val builder = builders.getOrPut(key) { TaskGroupBuilder(key) }
        builder.runIds += key
        builder.addTimestamp(item.timestampEpochMs)
        when (item) {
            is TimelineItem.Message -> {
                if (item.role == "user" && builder.userMessage == null) {
                    builder.userMessage = item
                } else if (item.role == "steer") {
                    builder.steerMessages += item
                } else {
                    builder.assistantMessages += item
                }
            }

            is TimelineItem.Activity -> {
                // The activity feed is a DHD tool trace, not a general-purpose
                // session log. Keep only physical action events and omit
                // legacy confirmation rows.
                if (item.isDhdActionActivity()) builder.activities += item
            }
        }
    }
    val rawGroups = builders.values.map(TaskGroupBuilder::build).sortedBy { it.timestampEpochMs }
    val groups = mutableListOf<TaskGroup>()

    rawGroups.forEach { group ->
        // A Continue run deliberately has no user-message row. Fold those
        // hidden runs into the latest visible task so an interrupted task
        // keeps one activity trace after it resumes. The actual run IDs stay
        // in the group for active-state and preview matching.
        if (group.userMessage == null) {
            val parentIndex = groups.indexOfLast { it.userMessage != null }
            if (parentIndex >= 0) {
                groups[parentIndex] = groups[parentIndex].merge(group)
                return@forEach
            }
        }
        groups += group
    }

    // Before the resumed turn emits its first event, it has no timeline item
    // of its own. Associate the active continuation with the latest task now
    // so the thinking animation is visible immediately after Continue.
    if (continuationRunId != null && groups.isNotEmpty() &&
        groups.none { continuationRunId in it.runIds }
    ) {
        val parentIndex = groups.indexOfLast { it.userMessage != null }
            .takeIf { it >= 0 }
            ?: groups.lastIndex
        groups[parentIndex] = groups[parentIndex].copy(
            runIds = groups[parentIndex].runIds + continuationRunId,
        )
    }

    return groups
}

private fun TaskGroup.merge(other: TaskGroup): TaskGroup = copy(
    runIds = runIds + other.runIds,
    steerMessages = steerMessages + other.steerMessages,
    activities = activities + other.activities,
    assistantMessages = assistantMessages + other.assistantMessages,
    timestampEpochMs = minOf(timestampEpochMs, other.timestampEpochMs),
)

private fun TimelineItem.Activity.isDhdActionActivity(): Boolean =
    !status.equals("confirmation", ignoreCase = true) &&
            !ToolNames.isCloseDisplay(toolName)

internal const val MAX_VISIBLE_TRACE_ACTIVITIES = 5

internal data class ActivityTraceSlice(
    val visibleActivities: List<TimelineItem.Activity>,
    val earlierCount: Int,
    val currentActivityId: String?,
    val hasSyntheticCurrent: Boolean,
)

/** Keep the conversation trace compact without discarding the stored history. */
internal fun capActivityTrace(
    activities: List<TimelineItem.Activity>,
    active: Boolean,
    currentActivityId: String? = null,
    hasCurrentTool: Boolean = false,
): ActivityTraceSlice {
    val persistedCurrentId = currentActivityId
        ?.takeIf { id -> active && activities.any { it.id == id } }
    val hasCurrent = persistedCurrentId != null || (active && hasCurrentTool)
    val historyLimit = (MAX_VISIBLE_TRACE_ACTIVITIES - if (hasCurrent) 1 else 0)
        .coerceAtLeast(0)
    val recentActivities = activities
        .filterNot { it.id == persistedCurrentId }
        .takeLast(historyLimit)
    val visibleIds = (recentActivities.map { it.id } + listOfNotNull(persistedCurrentId)).toSet()
    val visibleActivities = activities.filter { it.id in visibleIds }

    return ActivityTraceSlice(
        visibleActivities = visibleActivities,
        earlierCount = (activities.size - visibleActivities.size).coerceAtLeast(0),
        currentActivityId = persistedCurrentId,
        hasSyntheticCurrent = active && hasCurrentTool && persistedCurrentId == null,
    )
}

internal fun earlierActionsLabel(count: Int): String {
    val safeCount = count.coerceAtLeast(0)
    return "+$safeCount earlier action${if (safeCount == 1) "" else "s"}"
}

private fun TimelineItem.Activity.isInFlight(): Boolean =
    status.equals("proposed", ignoreCase = true) || status.equals("running", ignoreCase = true)

private fun TimelineItem.Activity.matchesLiveTool(toolCall: DhdToolCall): Boolean {
    val activityStatus = status.lowercase()
    val sameTool = toolName?.equals(toolCall.toolName, ignoreCase = true) == true ||
            (toolCall.toolName.equals(ToolNames.OPEN_APP, ignoreCase = true) &&
                    toolName.equals(ToolNames.EXECUTE, ignoreCase = true) &&
                    actionType.equals("OPEN_APP", ignoreCase = true))
    return runId == toolCall.sessionId &&
            sameTool &&
            createdAtEpochMs >= toolCall.startedAtEpochMs &&
            // The phone records ACTION_SUCCEEDED/ACTION_FAILED before the bridge
            // finishes the outer live tool call. Treat that terminal row as the
            // same call so the UI never renders a green persisted row alongside
            // its cyan synthetic counterpart.
            activityStatus in setOf("info", "proposed", "running", "completed", "failed", "attention")
}

@Composable
private fun ConversationTimeline(
    timeline: List<TimelineItem>,
    state: SessionState,
    currentToolCall: DhdToolCall? = null,
    developerStatus: DeveloperModeStatus,
    phoneRecoveryShownAtTop: Boolean,
    companionRecoveryShownAtTop: Boolean,
    companionConnected: Boolean,
    onOpenPhoneAccess: () -> Unit,
    onOpenCompanion: () -> Unit,
    onStopSession: () -> Unit,
    onAcknowledgeAttention: () -> Boolean,
    previewState: LiveDisplayPreviewState? = null,
    onPreviewSurfaceAvailable: (AndroidSurface) -> Unit = {},
    onPreviewSurfaceDestroyed: PreviewSurfaceDestroyed = { _, release -> release() },
    onOpenPreview: (String) -> Unit = {},
    expandedPreviewSessionKey: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 12.dp),
) {
    val listState = rememberLazyListState()
    var timelineBounds by remember { mutableStateOf<Rect?>(null) }
    var expandButtonBounds by remember { mutableStateOf<Rect?>(null) }
    val continuationRunId = state.continuationSessionIdOrNullForUi()
    val groups = remember(timeline, continuationRunId) {
        groupTimeline(timeline, continuationRunId)
    }

    // Keep following the live answer until the user starts a real list drag.
    // Content growth can temporarily make the list report that it is no longer
    // at the end, so only a user drag disables following; reaching the end
    // enables it again.
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && listState.canScrollBackward) {
                followLatest = false
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScrollForward ->
            if (!canScrollForward) followLatest = true
        }
    }
    // The inline preview is a live child of the task group. Its decoder and
    // tool rows can change height while the user is reading older messages;
    // do not reposition the list around that child as it updates.
    // A stopped/completed run can still own a retained display. Keep the
    // preview attached to its historical task group while the display is
    // retained; stopping the run only removes action authority.
    val previewBelongsToCurrentTimeline = state.sessionIdOrNull != null &&
            previewState?.let { preview ->
                preview.belongsToRun(state.sessionIdOrNull) &&
                        groups.any { group ->
                            group.runIds.any { runId -> preview.belongsToGroup(runId) }
                        }
            } == true
    val previewExpandedInViewer = previewBelongsToCurrentTimeline &&
            previewState?.isExpanded(expandedPreviewSessionKey) == true
    val inlinePreviewVisible = previewBelongsToCurrentTimeline && !previewExpandedInViewer
    LaunchedEffect(
        groups.lastOrNull()?.id,
        groups.lastOrNull()?.activities?.size,
        groups.lastOrNull()?.steerMessages?.size,
        groups.lastOrNull()?.assistantMessages?.lastOrNull()?.text?.length,
        currentToolCall?.id,
        currentToolCall?.status,
        inlinePreviewVisible,
        previewExpandedInViewer,
    ) {
        if (
            groups.isNotEmpty() &&
            followLatest &&
            !listState.isScrollInProgress &&
            !inlinePreviewVisible &&
            !previewExpandedInViewer
        ) {
            // A very large offset positions the last item at the bottom of
            // the viewport instead of repeatedly snapping to its start.
            listState.scrollToItem(groups.lastIndex, scrollOffset = Int.MAX_VALUE)
        }
    }
    val expandSessionKey = previewState?.sessionKey ?: state.sessionIdOrNull
    Box(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            timelineBounds = coordinates.boundsInRoot()
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            // The preview has interactive Compose children. Do not keep an
            // edge overscroll gesture active over them at the list boundary.
            overscrollEffect = null,
        ) {
            items(groups, key = { it.id }) { group ->
                TaskGroupCard(
                    group = group,
                    state = state,
                    currentToolCall = currentToolCall,
                    developerStatus = developerStatus,
                    phoneRecoveryShownAtTop = phoneRecoveryShownAtTop,
                    companionRecoveryShownAtTop = companionRecoveryShownAtTop,
                    companionConnected = companionConnected,
                    onOpenPhoneAccess = onOpenPhoneAccess,
                    onOpenCompanion = onOpenCompanion,
                    onStopSession = onStopSession,
                    onAcknowledgeAttention = onAcknowledgeAttention,
                    previewState = previewState,
                    onPreviewSurfaceAvailable = onPreviewSurfaceAvailable,
                    onPreviewSurfaceDestroyed = onPreviewSurfaceDestroyed,
                    onOpenPreview = onOpenPreview,
                    onPreviewExpandBoundsChanged = { bounds ->
                        expandButtonBounds = bounds
                    },
                    expandedPreviewSessionKey = expandedPreviewSessionKey,
                    active = state.isActive &&
                            state.sessionIdOrNull?.let(group.runIds::contains) == true,
                )
            }
        }

        // Keep the visible affordance in the item for stable semantics and
        // rendering, but route physical taps through a sibling of LazyColumn.
        // LazyColumn's drag/selection/edge gesture chain can otherwise retain
        // the pointer stream after the list reaches its final offset.
        val rootBounds = timelineBounds
        val buttonBounds = expandButtonBounds
        if (
            rootBounds != null &&
            buttonBounds != null &&
            buttonBounds.left >= rootBounds.left &&
            buttonBounds.top >= rootBounds.top &&
            buttonBounds.right <= rootBounds.right &&
            buttonBounds.bottom <= rootBounds.bottom &&
            expandSessionKey != null
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (buttonBounds.left - rootBounds.left).roundToInt(),
                            y = (buttonBounds.top - rootBounds.top).roundToInt(),
                        )
                    }
                    .size(48.dp)
                    .zIndex(3f)
                    .pointerInput(expandSessionKey) {
                        detectTapGestures {
                            android.util.Log.d("DhdPreview", "Expand requested")
                            onOpenPreview(expandSessionKey)
                        }
                    },
            )
        }
    }
}


@Composable
private fun TaskGroupCard(
    group: TaskGroup,
    state: SessionState,
    currentToolCall: DhdToolCall? = null,
    developerStatus: DeveloperModeStatus,
    phoneRecoveryShownAtTop: Boolean,
    companionRecoveryShownAtTop: Boolean,
    companionConnected: Boolean,
    onOpenPhoneAccess: () -> Unit,
    onOpenCompanion: () -> Unit,
    onStopSession: () -> Unit,
    onAcknowledgeAttention: () -> Boolean,
    previewState: LiveDisplayPreviewState? = null,
    onPreviewSurfaceAvailable: (AndroidSurface) -> Unit = {},
    onPreviewSurfaceDestroyed: PreviewSurfaceDestroyed = { _, release -> release() },
    onOpenPreview: (String) -> Unit = {},
    onPreviewExpandBoundsChanged: (Rect?) -> Unit = {},
    expandedPreviewSessionKey: String? = null,
    active: Boolean,
) {
    var traceExpanded by rememberSaveable(group.id) { mutableStateOf(false) }
    var earlierActionsExpanded by rememberSaveable(group.id) { mutableStateOf(false) }

    val terminalDurationMs = state.workedDurationMsOrNullForUi()
        ?.takeIf { state.sessionIdOrNull?.let(group.runIds::contains) == true }
    val durationSeconds = remember(group, terminalDurationMs) {
        terminalDurationMs?.let { maxOf(1L, it / 1_000L) } ?: run {
            val start = group.userMessage?.timestampEpochMs ?: group.timestampEpochMs
            val end = group.assistantMessages.lastOrNull()?.timestampEpochMs
                ?: group.activities.lastOrNull()?.timestampEpochMs
                ?: start
            maxOf(1L, (end - start) / 1000L)
        }
    }
    val liveToolCall = currentToolCall?.takeIf { toolCall ->
        active && toolCall.sessionId in group.runIds
    }
    val liveActivity = liveToolCall?.let { toolCall ->
        group.activities.asReversed().firstOrNull { it.matchesLiveTool(toolCall) }
    }
    val fallbackLiveActivity = if (liveToolCall == null && active) {
        group.activities.asReversed().firstOrNull { it.isInFlight() }
    } else {
        null
    }
    val trace = capActivityTrace(
        activities = group.activities,
        active = active,
        currentActivityId = liveActivity?.id ?: fallbackLiveActivity?.id,
        hasCurrentTool = liveToolCall != null,
    )
    val visibleTraceIds = trace.visibleActivities.map { it.id }.toSet()
    val earlierTraceActivities = group.activities.filterNot { it.id in visibleTraceIds }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // User message bubble (ChatGPT navy bubble in dark, soft gray bubble in light)
        group.userMessage?.let { message ->
            SelectionContainer {
                MessageBubble(message)
            }
        }

        // Steering instructions stay attached to the current run instead of
        // appearing as a new task.
        group.steerMessages.forEach { message ->
            SelectionContainer {
                SteerMessageBubble(message)
            }
        }

        val taskPreviewState = previewState?.let { preview ->
            if (preview.sessionKey == null) {
                preview.copy(sessionKey = state.sessionIdOrNull)
            } else {
                preview
            }
        }
        val previewVisibleForGroup = taskPreviewState?.let { preview ->
            group.runIds.any { runId -> preview.belongsToGroup(runId) } &&
                    !preview.isExpanded(expandedPreviewSessionKey)
        } == true
        val previewExpandedForGroup = taskPreviewState?.let { preview ->
            group.runIds.any { runId -> preview.belongsToGroup(runId) } &&
                    preview.isExpanded(expandedPreviewSessionKey)
        } == true
        if (previewVisibleForGroup) {
            // Keep the preview outside any SelectionContainer. Its native
            // TextureView and expand control must not share a selection
            // gesture surface with the surrounding timeline text.
            LiveDisplayPreview(
                state = taskPreviewState,
                onSurfaceAvailable = onPreviewSurfaceAvailable,
                onSurfaceDestroyed = onPreviewSurfaceDestroyed,
                onExpand = { taskPreviewState.sessionKey?.let(onOpenPreview) },
                onExpandBoundsChanged = onPreviewExpandBoundsChanged,
            )
        } else if (previewExpandedForGroup) {
            // Fullscreen owns the decoder surface. Keep this equal-sized slot
            // in the timeline so opening/closing the viewer cannot change the
            // LazyColumn's measured content or clamp its scroll offset.
            LiveDisplayPreviewPlaceholder(state = taskPreviewState)
        }

        // Keep the playful status for healthy work. Phone-access recovery is
        // rendered above the conversation so it does not jump underneath the
        // user's newly submitted message.
        if (active) {
            when (state) {
                is SessionState.Running -> {
                    RunningStatusIndicator(
                        currentPurpose = state.currentPurpose,
                        attentionReason = state.attentionReason,
                        attentionActionLabel = state.attentionActionLabel,
                        startedAtEpochMs = state.startedAtEpochMs,
                        elapsedBeforeStartMs = state.elapsedBeforeStartMs,
                        phoneAccessTitle = developerStatus.recoveryTitle,
                        phoneAccessDetail = developerStatus.recoveryDetail,
                        companionConnected = companionConnected,
                        phoneAccessRecoveryShownAtTop = phoneRecoveryShownAtTop,
                        companionRecoveryShownAtTop = companionRecoveryShownAtTop,
                        onOpenPhoneAccess = onOpenPhoneAccess,
                        onOpenCompanion = onOpenCompanion,
                        onStopSession = onStopSession,
                        onAcknowledgeAttention = onAcknowledgeAttention,
                    )
                }

                is SessionState.Paused -> if (
                    state.attentionReason != null &&
                    !(phoneRecoveryShownAtTop &&
                            state.attentionActionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true))
                ) {
                    AttentionRecoveryCard(
                        reason = state.attentionReason,
                        actionLabel = state.attentionActionLabel,
                        phoneAccessTitle = developerStatus.recoveryTitle,
                        phoneAccessDetail = developerStatus.recoveryDetail,
                        onAcknowledgeAttention = onAcknowledgeAttention,
                        onOpenPhoneAccess = onOpenPhoneAccess,
                        onStopSession = onStopSession,
                    )
                } else {
                    PausedStatusIndicator(
                        currentPurpose = state.currentPurpose,
                    )
                }

                else -> Unit
            }
        }

        // While active: show the current tool and the latest four completed
        // steps directly under the thinking indicator. Older work is summarized
        // so a long-running task cannot push the conversation downward forever.
        if (active && (trace.visibleActivities.isNotEmpty() || trace.hasSyntheticCurrent)) {
            val syntheticCurrent = liveToolCall?.takeIf { trace.hasSyntheticCurrent }
            AnimatedCollapsedTrace(
                activities = trace.visibleActivities,
                earlierActivities = earlierTraceActivities,
                earlierCount = trace.earlierCount,
                earlierExpanded = earlierActionsExpanded,
                currentActivityId = trace.currentActivityId,
                syntheticCurrent = syntheticCurrent,
                onToggleEarlier = { earlierActionsExpanded = !earlierActionsExpanded },
            )
        }

        // Completed runs with phone actions keep a collapsible trace. Direct
        // responses should flow directly from the user message to the answer.
        if (!active && group.activities.isNotEmpty()) {
            WorkedTraceSection(
                durationSeconds = durationSeconds,
                activities = group.activities,
                expanded = traceExpanded,
                onToggleExpand = { traceExpanded = !traceExpanded },
            )
        }

        // Assistant response message(s)
        group.assistantMessages.forEach { message ->
            SelectionContainer {
                MessageBubble(message)
            }
        }
    }
}

@Composable
private fun RunningStatusIndicator(
    currentPurpose: String,
    attentionReason: String?,
    attentionActionLabel: String?,
    startedAtEpochMs: Long,
    elapsedBeforeStartMs: Long,
    phoneAccessTitle: String,
    phoneAccessDetail: String,
    companionConnected: Boolean,
    phoneAccessRecoveryShownAtTop: Boolean,
    companionRecoveryShownAtTop: Boolean,
    onOpenPhoneAccess: () -> Unit,
    onOpenCompanion: () -> Unit,
    onStopSession: () -> Unit,
    onAcknowledgeAttention: () -> Boolean,
) {
    val elapsedSeconds = rememberElapsedSeconds(startedAtEpochMs, elapsedBeforeStartMs)
    // A pending attention request owns the next step. Keep Done visible even
    // if the companion or developer-status poll changes while the user is
    // completing a biometric/PIN prompt.
    if (currentPurpose.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true)) {
        val phoneAccessInstructionsAtTop = phoneAccessRecoveryShownAtTop &&
                attentionActionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true)
        if (!phoneAccessInstructionsAtTop) {
            AttentionRecoveryCard(
                reason = attentionReason,
                actionLabel = attentionActionLabel,
                phoneAccessTitle = phoneAccessTitle,
                phoneAccessDetail = phoneAccessDetail,
                onAcknowledgeAttention = onAcknowledgeAttention,
                onOpenPhoneAccess = onOpenPhoneAccess,
                onStopSession = onStopSession,
            )
        }
        return
    }

    // A slow Codex startup or a released request does not mean that the LAN
    // companion is disconnected. The phone-side heartbeat lease is the source
    // of truth for this recovery card.
    val waitingForCompanion = !companionConnected
    if (waitingForCompanion) {
        if (!companionRecoveryShownAtTop) {
            CompanionRecoveryCard(
                elapsedSeconds = elapsedSeconds,
                onOpenCompanion = onOpenCompanion,
            )
        }
        return
    }

    ShimmerThinkingIndicator(
        startedAtEpochMs = startedAtEpochMs,
        elapsedBeforeStartMs = elapsedBeforeStartMs,
        elapsedSeconds = elapsedSeconds,
    )
}

@Composable
private fun ShimmerThinkingIndicator(
    startedAtEpochMs: Long,
    elapsedBeforeStartMs: Long,
    elapsedSeconds: Long,
) {
    val colors = LocalAssistantColors.current
    var wordIndex by rememberSaveable(startedAtEpochMs) {
        mutableStateOf(Random.nextInt(THINKING_WORDS.size))
    }
    LaunchedEffect(startedAtEpochMs) {
        while (true) {
            delay(THINKING_WORD_INTERVAL_MS)
            wordIndex = nextThinkingWordIndex(wordIndex)
        }
    }
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_shimmer")

    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = -150f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            colors.accentBlue.copy(alpha = 0.35f),
            colors.accentBlue,
            colors.textPrimary,
            colors.accentBlue,
            colors.accentBlue.copy(alpha = 0.35f),
        ),
        start = Offset(shimmerTranslate, 0f),
        end = Offset(shimmerTranslate + 160f, 0f),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_bot),
            contentDescription = "Thinking",
            tint = colors.accentBlue.copy(alpha = pulseAlpha),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = THINKING_WORDS[wordIndex],
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(brush = shimmerBrush),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "·",
            fontSize = 14.sp,
            color = colors.textSecondary.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${elapsedSeconds}s",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Normal,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun PausedStatusIndicator(currentPurpose: String) {
    val colors = LocalAssistantColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_stop),
            contentDescription = "Paused",
            tint = colors.warningAmber,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(
                text = "Paused",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.warningAmber,
            )
            Text(
                text = thinkingDetail(currentPurpose, 0L),
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun CompanionRecoveryCard(
    elapsedSeconds: Long?,
    onOpenCompanion: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    if (compact) {
        CompactRecoveryStatusCard(
            icon = R.drawable.ic_laptop,
            title = "Desktop companion not connected",
            detail = "DHD is waiting for the desktop companion.",
            accent = colors.warningAmber,
            actionLabel = "Instructions",
            onAction = onOpenCompanion,
            modifier = modifier,
        )
        return
    }
    RecoveryCard(
        icon = R.drawable.ic_laptop,
        title = "Desktop companion not connected",
        detail = "DHD is waiting for the desktop companion. Connect this phone on your local network.",
        accent = colors.warningAmber,
        actionLabel = "View connection instructions",
        onAction = onOpenCompanion,
        modifier = modifier,
        trailing = elapsedSeconds?.let { "Waiting ${it}s" },
    )
}

@Composable
private fun DeveloperConnectionRecoveryCard(
    status: DeveloperModeStatus,
    onOpenPhoneAccess: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    if (compact) {
        CompactRecoveryStatusCard(
            icon = R.drawable.ic_shield,
            title = status.recoveryTitle,
            detail = status.recoveryDetail,
            accent = colors.warningAmber,
            actionLabel = "Instructions",
            onAction = onOpenPhoneAccess,
            modifier = modifier,
        )
        return
    }
    RecoveryCard(
        icon = R.drawable.ic_shield,
        title = status.recoveryTitle,
        detail = status.recoveryDetail,
        accent = colors.warningAmber,
        actionLabel = "View instructions",
        onAction = onOpenPhoneAccess,
        modifier = modifier,
    )
}

@Composable
private fun CompactRecoveryStatusCard(
    icon: Int,
    title: String,
    detail: String,
    accent: Color,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = colors.warningAmber),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(actionLabel, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CombinedRecoveryCard(
    phoneStatus: DeveloperModeStatus,
    companionWaitSeconds: Long?,
    onOpenPhoneAccess: () -> Unit,
    onOpenCompanion: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 13.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            CombinedRecoverySection(
                icon = R.drawable.ic_shield,
                title = if (compact) "Phone access needed" else phoneStatus.recoveryTitle,
                detail = phoneStatus.recoveryDetail,
                actionLabel = "View instructions",
                onAction = onOpenPhoneAccess,
                compact = compact,
                compactActionLabel = "Instructions",
            )
            HorizontalDivider(color = colors.borderColor.copy(alpha = 0.8f))
            CombinedRecoverySection(
                icon = R.drawable.ic_laptop,
                title = if (compact) "Companion not connected" else "Desktop companion not connected",
                detail = "DHD is waiting for the desktop companion. Connect this phone on your local network.",
                trailing = companionWaitSeconds?.let { "Waiting ${it}s" },
                actionLabel = "View instructions",
                onAction = onOpenCompanion,
                compact = compact,
                compactActionLabel = "Instructions",
            )
        }
    }
}

@Composable
private fun CombinedRecoverySection(
    icon: Int,
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
    trailing: String? = null,
    compact: Boolean = false,
    compactActionLabel: String = actionLabel,
) {
    val colors = LocalAssistantColors.current
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = colors.warningAmber,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = colors.warningAmber),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(compactActionLabel, fontSize = 11.sp, maxLines = 1)
            }
        }
    } else {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = colors.warningAmber,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                trailing?.let { Text(text = it, color = colors.textSecondary, fontSize = 11.sp) }
            }
            Text(
                text = detail,
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(start = 27.dp, top = 5.dp),
            )
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = colors.warningAmber),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                modifier = Modifier.padding(start = 27.dp, top = 9.dp),
            ) {
                Text(actionLabel, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AttentionRecoveryCard(
    reason: String?,
    actionLabel: String?,
    phoneAccessTitle: String,
    phoneAccessDetail: String,
    onAcknowledgeAttention: () -> Boolean,
    onOpenPhoneAccess: (() -> Unit)? = null,
    onStopSession: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val usesPhoneAccessInstructions = actionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true) &&
            onOpenPhoneAccess != null
    if (usesPhoneAccessInstructions) {
        PhoneAccessPausedCard(
            title = phoneAccessTitle,
            detail = phoneAccessDetail,
            onOpenPhoneAccess = onOpenPhoneAccess!!,
        )
        return
    }
    RecoveryCard(
        icon = R.drawable.ic_info,
        title = "DHD needs your attention",
        detail = reason?.takeIf(String::isNotBlank)
            ?: "Review the phone and complete the requested step before continuing.",
        accent = colors.warningAmber,
        actionLabel = actionLabel?.takeIf(String::isNotBlank) ?: "Done",
        onAction = {
            onAcknowledgeAttention()
        },
        secondaryActionLabel = "Stop",
        onSecondaryAction = onStopSession,
    )
}

@Composable
private fun PhoneAccessPausedCard(
    title: String,
    detail: String,
    onOpenPhoneAccess: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, colors.warningAmber.copy(alpha = 0.55f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = colors.warningAmber,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = detail,
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(start = 27.dp, top = 5.dp),
            )
            Button(
                onClick = onOpenPhoneAccess,
                colors = ButtonDefaults.buttonColors(containerColor = colors.warningAmber),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                modifier = Modifier.padding(start = 27.dp, top = 9.dp),
            ) {
                Text("View instructions", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RecoveryCard(
    icon: Int,
    title: String,
    detail: String,
    accent: Color,
    actionLabel: String,
    onAction: () -> Unit,
    trailing: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAssistantColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                trailing?.let {
                    Text(text = it, color = colors.textSecondary, fontSize = 11.sp)
                }
            }
            Text(
                text = detail,
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(start = 27.dp, top = 5.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 27.dp, top = 9.dp),
            ) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(actionLabel, fontSize = 12.sp)
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        border = BorderStroke(1.dp, colors.borderColor),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(secondaryActionLabel, color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberElapsedSeconds(
    startedAtEpochMs: Long,
    elapsedBeforeStartMs: Long = 0L,
): Long {
    val normalizedElapsedBeforeStartMs = elapsedBeforeStartMs.coerceAtLeast(0L)
    var elapsedSeconds by remember(startedAtEpochMs, normalizedElapsedBeforeStartMs) {
        mutableStateOf(
            accumulatedElapsedSeconds(
                startedAtEpochMs,
                normalizedElapsedBeforeStartMs,
                System.currentTimeMillis(),
            ),
        )
    }
    LaunchedEffect(startedAtEpochMs, normalizedElapsedBeforeStartMs) {
        while (true) {
            elapsedSeconds = accumulatedElapsedSeconds(
                startedAtEpochMs,
                normalizedElapsedBeforeStartMs,
                System.currentTimeMillis(),
            )
            delay(1_000L)
        }
    }
    return elapsedSeconds
}

internal fun accumulatedElapsedSeconds(
    startedAtEpochMs: Long,
    elapsedBeforeStartMs: Long,
    nowEpochMs: Long,
): Long = (
        elapsedBeforeStartMs.coerceAtLeast(0L) +
                (nowEpochMs - startedAtEpochMs).coerceAtLeast(0L)
        ) / 1_000L

private fun thinkingDetail(currentPurpose: String, elapsedSeconds: Long): String = when {
    currentPurpose.equals(CoordinatorCopy.PREPARING_REQUEST, ignoreCase = true) && elapsedSeconds >= COMPANION_WAIT_CALLOUT_SECONDS ->
        "Waiting for the desktop companion"

    currentPurpose.equals(CoordinatorCopy.PREPARING_REQUEST, ignoreCase = true) -> "Connecting to the desktop companion"
    currentPurpose.equals(CoordinatorCopy.CODEX_PLANNING, ignoreCase = true) || currentPurpose.equals(
        CoordinatorCopy.DHD_PLANNING,
        ignoreCase = true
    ) -> "Thinking…"

    else -> currentPurpose.ifBlank { "Preparing the next step" }
}

internal val THINKING_WORDS = listOf(
    "Thinking…",
    "DHD-ing…",
    "Discombobulating…",
    "NPC-ing…",
    "Combobulating…",
    "Recombobulating…",
    "Cerebrating…",
    "Noodling…",
    "Orchestrating…",
    "Razzle-dazzling…",
    "Vibing…",
    "Accomplishing…",
    "Actioning…",
    "Actualizing…",
    "Architecting…",
    "Baking…",
    "Beaming…",
    "Beboppin’…",
    "Bloviating…",
    "Boogieing…",
    "Boondoggling…",
    "Booping…",
    "Bootstrapping…",
    "Brewing…",
    "Burrowing…",
    "Calculating…",
    "Canoodling…",
    "Caramelizing…",
    "Cascading…",
    "Catapulting…",
    "Channeling…",
    "Choreographing…",
    "Churning…",
    "Coalescing…",
    "Composing…",
    "Computing…",
    "Concocting…",
    "Considering…",
    "Contemplating…",
    "Cooking…",
    "Crafting…",
    "Creating…",
    "Crunching…",
    "Crystallizing…",
    "Cultivating…",
    "Deciphering…",
    "Deliberating…",
    "Determining…",
    "Dilly-dallying…",
    "Doing…",
    "Doodling…",
    "Drizzling…",
    "Effecting…",
    "Elucidating…",
    "Embellishing…",
    "Enchanting…",
    "Envisioning…",
    "Fermenting…",
    "Fiddle-faddling…",
    "Finagling…",
    "Flambeing…",
    "Flibbertigibbeting…",
    "Flowing…",
    "Fluttering…",
    "Forging…",
    "Forming…",
    "Frolicking…",
    "Gallivanting…",
    "Garnishing…",
    "Generating…",
    "Germinating…",
    "Gitifying…",
    "Grooving…",
    "Hatching…",
    "Herding…",
    "Honking…",
    "Hullaballooing…",
    "Hyperspacing…",
    "Ideating…",
    "Imagining…",
    "Improvising…",
    "Incubating…",
    "Inferring…",
    "Infusing…",
    "Jitterbugging…",
    "Kneading…",
    "Leavening…",
    "Levitating…",
    "Lollygagging…",
    "Manifesting…",
    "Marinating…",
    "Meandering…",
    "Metamorphosing…",
    "Moseying…",
    "Mulling…",
    "Musing…",
    "Nesting…",
    "Orbiting…",
    "Percolating…",
    "Perusing…",
    "Philosophising…",
    "Pondering…",
    "Pontificating…",
    "Pouncing…",
    "Processing…",
    "Proofing…",
    "Puttering…",
    "Puzzling…",
    "Quantumizing…",
    "Reticulating…",
    "Roosting…",
    "Ruminating…",
    "Sauteing…",
    "Scampering…",
    "Schlepping…",
    "Scurrying…",
    "Seasoning…",
    "Shenaniganing…",
    "Shimmying…",
    "Simmering…",
    "Skedaddling…",
    "Sketching…",
    "Slithering…",
    "Smooshing…",
    "Sock-hopping…",
    "Spelunking…",
    "Spinning…",
    "Sprouting…",
    "Stewing…",
    "Synthesizing…",
    "Tempering…",
    "Thundering…",
    "Tinkering…",
    "Tomfoolering…",
    "Transfiguring…",
    "Transmuting…",
    "Unfurling…",
    "Unravelling…",
    "Warping…",
    "Whatchamacalliting…",
    "Whirring…",
    "Whisking…",
    "Wibbling…",
    "Working…",
    "Wrangling…",
    "Zesting…",
    "Zigzagging…",
    "Phone-wrangling…",
    "Tap-dancing…",
    "Screen-sleuthing…",
    "Guard-checking…",
)

private fun nextThinkingWordIndex(previous: Int): Int {
    if (THINKING_WORDS.size < 2) return 0
    var next: Int
    do {
        next = Random.nextInt(THINKING_WORDS.size)
    } while (next == previous)
    return next
}

private const val COMPANION_WAIT_CALLOUT_SECONDS = 15L
private const val THINKING_WORD_INTERVAL_MS = 4_000L

@Composable
private fun MessageBubble(message: TimelineItem.Message) {
    val colors = LocalAssistantColors.current
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Surface(
                color = colors.userBubble,
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 6.dp,
                ),
                modifier = Modifier.padding(start = 48.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = parseInlineMarkdown(message.text, colors),
                        color = colors.userBubbleText,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 24.dp, top = 2.dp),
            ) {
                MarkdownContent(
                    markdown = message.text,
                    baseTextStyle = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = colors.textPrimary,
                    ),
                )
            }
        }
    }
}

private const val TRACE_NEW_ROW_HANDOFF_MS = 220L
private const val TRACE_ROW_EXIT_MS = 180L

private data class AnimatedTraceRow(
    val activity: TimelineItem.Activity,
    val visible: Boolean = true,
)

@Composable
private fun AnimatedCollapsedTrace(
    activities: List<TimelineItem.Activity>,
    earlierActivities: List<TimelineItem.Activity>,
    earlierCount: Int,
    earlierExpanded: Boolean,
    currentActivityId: String?,
    syntheticCurrent: DhdToolCall?,
    onToggleEarlier: () -> Unit,
) {
    var rows by remember { mutableStateOf(activities.map { AnimatedTraceRow(it) }) }
    var displayedEarlierCount by remember { mutableStateOf(earlierCount) }
    var displayedSyntheticCurrent by remember { mutableStateOf(syntheticCurrent) }
    var syntheticVisible by remember { mutableStateOf(syntheticCurrent != null) }
    val activityIds = activities.map { it.id }
    val activityById = remember(activities) { activities.associateBy { it.id } }

    LaunchedEffect(activityIds, syntheticCurrent?.id, earlierCount) {
        val desiredIds = activityIds.toSet()
        val previousRows = rows
        val previousIds = previousRows.map { it.activity.id }.toSet()
        val incomingRows = activities.filter { it.id !in previousIds }
        val removedIds = previousRows
            .filter { it.activity.id !in desiredIds }
            .map { it.activity.id }
            .toSet()
        val previousSynthetic = displayedSyntheticCurrent
        val syntheticAdded = syntheticCurrent != null && previousSynthetic?.id != syntheticCurrent.id
        val syntheticRemoved = syntheticCurrent == null && previousSynthetic != null
        val syntheticReplacedByPersisted = syntheticRemoved && previousSynthetic?.let { previous ->
            activities.any { it.matchesLiveTool(previous) }
        } == true

        if (syntheticCurrent != null) {
            displayedSyntheticCurrent = syntheticCurrent
            syntheticVisible = true
        }
        if (syntheticReplacedByPersisted) {
            // The persisted lifecycle row is the same tool call, not a new
            // action. Swap it in place instead of running the add/remove
            // handoff that is reserved for genuinely new calls.
            displayedSyntheticCurrent = null
            syntheticVisible = false
        }
        if (incomingRows.isNotEmpty()) {
            rows = previousRows.map { it.copy(visible = true) } +
                    incomingRows.map { AnimatedTraceRow(it) }
        }

        // Let the new tool call arrive before the displaced row is moved into
        // the earlier-actions bucket.
        if ((incomingRows.isNotEmpty() || syntheticAdded) && !syntheticReplacedByPersisted) {
            delay(TRACE_NEW_ROW_HANDOFF_MS)
        }

        if (removedIds.isNotEmpty()) {
            rows = rows.map { row ->
                if (row.activity.id in removedIds) row.copy(visible = false) else row
            }
        }
        if (syntheticRemoved && !syntheticReplacedByPersisted) {
            syntheticVisible = false
        }
        displayedEarlierCount = earlierCount

        val syntheticNeedsExit = syntheticRemoved && !syntheticReplacedByPersisted
        if (removedIds.isNotEmpty() || syntheticNeedsExit) {
            delay(TRACE_ROW_EXIT_MS)
            rows = rows.filter { it.activity.id in desiredIds }
            if (syntheticNeedsExit) {
                displayedSyntheticCurrent = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EarlierActionsRow(
            count = displayedEarlierCount,
            expanded = earlierExpanded,
            onToggle = onToggleEarlier,
        )
        earlierActivities.forEach { activity ->
            key("earlier-${activity.id}") {
                AnimatedVisibility(
                    visible = earlierExpanded,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
                ) {
                    TraceStepRow(activity = activity)
                }
            }
        }
        rows.forEach { row ->
            key(row.activity.id) {
                AnimatedVisibility(
                    visible = row.visible,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
                ) {
                    TraceStepRow(
                        activity = activityById[row.activity.id] ?: row.activity,
                        isCurrent = row.activity.id == currentActivityId,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = syntheticVisible,
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            displayedSyntheticCurrent?.let { CurrentToolTraceRow(it) }
        }
    }
}

@Composable
private fun WorkedTraceSection(
    durationSeconds: Long,
    activities: List<TimelineItem.Activity>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onToggleExpand() },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggleExpand() }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Worked for ${durationSeconds}s",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
            )
            Icon(
                painter = painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                contentDescription = if (expanded) "Collapse trace" else "Expand trace",
                tint = colors.textSecondary,
                modifier = Modifier.size(13.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { onToggleExpand() },
                        )
                    }
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                activities.forEach { activity ->
                    TraceStepRow(activity = activity)
                }
            }
        }
    }
}

@Composable
private fun EarlierActionsRow(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    if (count <= 0) return
    val colors = LocalAssistantColors.current
    val label = if (expanded) "Hide earlier actions" else earlierActionsLabel(count)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "earlier_actions_chevron",
    )
    Row(
        modifier = Modifier
            .padding(start = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse earlier actions" else "Show earlier actions",
                onClick = onToggle,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
            label = "earlier_actions_label",
        ) { animatedLabel ->
            Text(
                text = animatedLabel,
                fontSize = 12.5.sp,
                color = colors.textSecondary.copy(alpha = 0.78f),
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = colors.textSecondary.copy(alpha = 0.78f),
            modifier = Modifier
                .size(13.dp)
                .graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

@Composable
private fun TraceStepRow(
    activity: TimelineItem.Activity,
    isCurrent: Boolean = false,
) {
    TraceStepRowContent(
        toolName = activity.toolName,
        actionType = activity.actionType,
        label = activityLabel(activity),
        status = activity.status,
        isCurrent = isCurrent,
    )
}

@Composable
private fun CurrentToolTraceRow(toolCall: DhdToolCall) {
    TraceStepRowContent(
        toolName = toolCall.toolName,
        label = toolCall.purpose,
        status = "running",
        isCurrent = true,
    )
}

private data class ToolActivityShimmer(
    val brush: Brush,
    val pulseAlpha: Float,
)

@Composable
private fun rememberToolActivityShimmer(
    colors: AssistantColorScheme,
    shimmerColor: Color,
): ToolActivityShimmer {
    val infiniteTransition = rememberInfiniteTransition(label = "tool_activity_shimmer")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = -150f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tool_activity_shimmer_translate",
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tool_activity_pulse_alpha",
    )
    return ToolActivityShimmer(
        brush = Brush.linearGradient(
            colors = listOf(
                shimmerColor.copy(alpha = 0.35f),
                shimmerColor,
                colors.textPrimary,
                shimmerColor,
                shimmerColor.copy(alpha = 0.35f),
            ),
            start = Offset(shimmerTranslate, 0f),
            end = Offset(shimmerTranslate + 160f, 0f),
        ),
        pulseAlpha = pulseAlpha,
    )
}

@Composable
private fun TraceStepRowContent(
    toolName: String?,
    actionType: String? = null,
    label: String,
    status: String,
    isCurrent: Boolean,
) {
    val colors = LocalAssistantColors.current

    val normalizedStatus = status.lowercase()
    val statusColor = when (normalizedStatus) {
        "completed" -> colors.accentGreen
        "failed" -> colors.errorRed
        "attention" -> colors.warningAmber
        else -> colors.textSecondary
    }
    val iconColor = when (normalizedStatus) {
        "failed" -> colors.errorRed
        "attention" -> colors.warningAmber
        else -> toolActivityColor(toolName, colors, statusColor, actionType)
    }
    val shimmer = if (isCurrent) {
        rememberToolActivityShimmer(colors, iconColor)
    } else {
        null
    }
    val labelStyle = TextStyle(
        brush = shimmer?.brush,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Normal,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 3.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_connected_nodes),
            contentDescription = "${toolName ?: "Tool"} call${if (isCurrent) " in progress" else ""}",
            tint = shimmer?.let { iconColor.copy(alpha = it.pulseAlpha) } ?: iconColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = if (shimmer == null) colors.textPrimary else Color.Unspecified,
            style = labelStyle,
            modifier = Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal data class PendingSteerDraft(
    val text: String,
    val reasoningEffort: String,
    val fastMode: Boolean,
)

internal data class SteerDraftQueue(
    val drafts: List<PendingSteerDraft>,
    val sessionId: String?,
    val carryToNextRun: Boolean,
)

internal data class SteerDraftPromotion(
    val draft: PendingSteerDraft,
    val queue: SteerDraftQueue,
)

internal fun SteerDraftQueue.forActiveSession(activeSessionId: String?): SteerDraftQueue {
    if (sessionId == activeSessionId) return this
    return if (carryToNextRun && activeSessionId != null && drafts.isNotEmpty()) {
        // Keep the remaining queue attached to the auto-started run.
        copy(sessionId = activeSessionId, carryToNextRun = false)
    } else {
        SteerDraftQueue(drafts = emptyList(), sessionId = activeSessionId, carryToNextRun = false)
    }
}

internal fun SteerDraftQueue.promoteAfterCompletion(completedSessionId: String): SteerDraftPromotion? {
    if (drafts.isEmpty() || sessionId != completedSessionId) return null
    // Promote only the oldest held draft. Keep the rest in FIFO order for
    // later follow-up runs.
    val remaining = drafts.drop(1)
    return SteerDraftPromotion(
        draft = drafts.first(),
        queue = copy(drafts = remaining, carryToNextRun = remaining.isNotEmpty()),
    )
}

internal val steerDraftsSaver = listSaver<List<PendingSteerDraft>, String>(
    save = { drafts ->
        drafts.flatMap { draft ->
            listOf(draft.text, draft.reasoningEffort, draft.fastMode.toString())
        }
    },
    restore = { saved ->
        saved.chunked(3).mapNotNull { fields ->
            if (fields.size != 3) return@mapNotNull null
            PendingSteerDraft(
                text = fields[0],
                reasoningEffort = fields[1],
                fastMode = fields[2].toBoolean(),
            )
        }
    },
)

@Composable
private fun SteerDraftBar(
    text: String,
    onSteer: () -> Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        color = colors.surfaceCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "↪",
                color = colors.textSecondary,
                fontSize = 18.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = text,
                color = colors.textPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "↪ Steer",
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSteer() }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Discard steer draft",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onDismiss() }
                    .padding(8.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_compose_new),
                contentDescription = "Edit steer draft",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onEdit() }
                    .padding(7.dp),
            )
        }
    }
}

@Composable
private fun SteerMessageBubble(message: TimelineItem.Message) {
    val colors = LocalAssistantColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = colors.surfaceCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colors.accentBlue.copy(alpha = 0.65f)),
            modifier = Modifier.padding(start = 64.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = "Steer",
                    color = colors.accentBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message.text,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}


private fun activityLabel(activity: TimelineItem.Activity): String = activity.purpose
    .trim()
    .ifBlank { "Working on the phone" }


@Composable
private fun RequestComposer(
    enabled: Boolean,
    isActive: Boolean,
    canSteer: Boolean,
    reasoningEffort: ReasoningEffort,
    visibleReasoningEfforts: List<ReasoningEffort>,
    fastMode: Boolean,
    onSetFastMode: (Boolean) -> Unit,
    showReasoningSelector: Boolean,
    onOpenReasoningSelector: () -> Unit,
    onExpandedChanged: (Boolean) -> Unit,
    editText: String? = null,
    onEditTextConsumed: () -> Unit = {},
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    canContinue: Boolean,
    onContinue: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isImeVisible = imeInsets.getBottom(density) > 0

    // Using TextFieldValue for accurate cursor position & selection tracking with Wispr Flow / Accessibility / IME
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var isFocused by remember { mutableStateOf(false) }
    var isImeHiding by remember { mutableStateOf(false) }
    var staleValueToIgnore by remember { mutableStateOf<String?>(null) }
    val composerFocusRequester = remember { FocusRequester() }

    LaunchedEffect(editText) {
        if (!editText.isNullOrBlank()) {
            textFieldValue = TextFieldValue(
                editText,
                selection = TextRange(editText.length),
            )
            composerFocusRequester.requestFocus()
            onEditTextConsumed()
        }
    }

    LaunchedEffect(density, imeInsets) {
        var previousImeBottom = imeInsets.getBottom(density)
        snapshotFlow { imeInsets.getBottom(density) }
            .collect { currentImeBottom ->
                isImeHiding = previousImeBottom > 0 && currentImeBottom < previousImeBottom
                previousImeBottom = currentImeBottom
            }
    }

    // When the on-screen keyboard is dismissed, automatically clear focus and collapse the composer back
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible && textFieldValue.text.isBlank()) {
            focusManager.clearFocus()
            isFocused = false
        }
    }

    // Clear the field before handing the request to the session layer. Starting
    // a session can immediately change the composition (and disable the field),
    // while the IME may still deliver one final value-change callback. Clearing
    // first prevents that callback from restoring the submitted text. If the
    // caller rejects the request, restore the draft so it is not lost.
    fun submitRequest() {
        val request = textFieldValue.text.trim()
        if (request.isEmpty() || !enabled) return

        val previousValue = textFieldValue
        staleValueToIgnore = previousValue.text
        textFieldValue = TextFieldValue("", selection = TextRange.Zero)
        focusManager.clearFocus()
        if (!onSend(request)) {
            staleValueToIgnore = null
            textFieldValue = previousValue
        }
    }

    val hasText = textFieldValue.text.isNotBlank()
    val isExpanded = hasText || (!isImeHiding && isImeVisible)
    val isWidened = hasText || (!isImeHiding && isImeVisible)

    LaunchedEffect(isExpanded) {
        onExpandedChanged(isExpanded)
    }

    val horizontalPadding by animateDpAsState(
        targetValue = if (isWidened) 14.dp else 36.dp,
        animationSpec = tween(
            durationMillis = 120,
            easing = FastOutSlowInEasing,
        ),
        label = "composer_horizontal_padding",
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isExpanded) 22.dp else 28.dp,
        animationSpec = tween(
            durationMillis = 120,
            easing = FastOutSlowInEasing,
        ),
        label = "composer_corner_radius",
    )

    Surface(
        color = colors.composerBackground,
        shape = RoundedCornerShape(cornerRadius),
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
    ) {
        Column(
            modifier = Modifier
                .padding(
                    start = 10.dp,
                    end = 10.dp,
                    top = 7.dp,
                    bottom = 7.dp,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (isExpanded) 48.dp else 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isExpanded) {
                    AttachButton(colors = colors)
                    Spacer(Modifier.width(10.dp))
                }
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { nextValue ->
                        // Some IMEs deliver the pre-submit value after focus is
                        // cleared. Ignore that one stale callback instead of
                        // putting the just-submitted request back in the field.
                        if (staleValueToIgnore != null && nextValue.text == staleValueToIgnore) {
                            staleValueToIgnore = null
                        } else {
                            staleValueToIgnore = null
                            textFieldValue = nextValue
                        }
                    },
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(colors.accentBlue),
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (isExpanded) 4.dp else 0.dp,
                            end = if (isExpanded) 4.dp else 10.dp,
                            top = if (isExpanded) 4.dp else 0.dp,
                        )
                        .focusRequester(composerFocusRequester)
                        .semantics {
                            contentDescription = "Ask DHD input"
                        }
                        .onFocusChanged { isFocused = it.isFocused },
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            submitRequest()
                        },
                    ),
                    decorationBox = { innerTextField ->
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = if (enabled) "Ask DHD" else "DHD is working…",
                                color = colors.textSecondary,
                                fontSize = 16.sp,
                            )
                        }
                        innerTextField()
                    },
                )

                if (!isExpanded) {
                    ActionOrSendButton(
                        isActive = isActive,
                        hasText = hasText,
                        enabled = enabled,
                        colors = colors,
                        onSend = ::submitRequest,
                        onStop = onStop,
                        canSteer = canSteer,
                        canContinue = canContinue,
                        onContinue = onContinue,
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = tween(120),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(80)),
                exit = shrinkVertically(
                    animationSpec = tween(100),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(animationSpec = tween(60)),
            ) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AttachButton(colors = colors)
                        Spacer(Modifier.weight(1f))
                        FastModeButton(
                            enabled = enabled,
                            selected = fastMode,
                            onToggle = { onSetFastMode(!fastMode) },
                        )
                        Spacer(Modifier.width(8.dp))
                        ReasoningEffortButton(
                            effort = reasoningEffort,
                            visibleEfforts = visibleReasoningEfforts,
                            enabled = enabled && visibleReasoningEfforts.isNotEmpty(),
                            expanded = showReasoningSelector,
                            onClick = onOpenReasoningSelector,
                        )
                        Spacer(Modifier.width(8.dp))
                        ActionOrSendButton(
                            isActive = isActive,
                            hasText = hasText,
                            enabled = enabled,
                            colors = colors,
                            onSend = ::submitRequest,
                            onStop = onStop,
                            canSteer = canSteer,
                            canContinue = canContinue,
                            onContinue = onContinue,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachButton(
    colors: AssistantColorScheme,
    onClick: () -> Unit = {},
) {
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { onClick() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = "Attach",
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ActionOrSendButton(
    isActive: Boolean,
    hasText: Boolean,
    enabled: Boolean,
    canSteer: Boolean,
    canContinue: Boolean,
    colors: AssistantColorScheme,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
) {
    if (isActive) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canSteer) {
                Surface(
                    shape = CircleShape,
                    color = if (hasText) colors.sendButtonActiveBg else colors.sendButtonInactiveBg,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(enabled = hasText && enabled) { onSend() },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_send_arrow),
                            contentDescription = "Steer active task",
                            tint = if (hasText) colors.sendButtonActiveIcon else colors.sendButtonInactiveIcon,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Active Stop Square Button inside blue circle (Matching Image)
            Surface(
                shape = CircleShape,
                color = colors.accentBlue,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onStop() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_stop),
                        contentDescription = "Stop task",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    } else if (canContinue && !hasText) {
        Surface(
            shape = CircleShape,
            color = if (enabled) colors.accentBlue else colors.sendButtonInactiveBg,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onContinue),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = "Continue task",
                    tint = if (enabled) Color.White else colors.sendButtonInactiveIcon,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    } else {
        // Send Upward Arrow Button
        Surface(
            shape = CircleShape,
            color = if (hasText && enabled) colors.sendButtonActiveBg else colors.sendButtonInactiveBg,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(enabled = hasText && enabled) { onSend() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_send_arrow),
                    contentDescription = "Send",
                    tint = if (hasText && enabled) colors.sendButtonActiveIcon else colors.sendButtonInactiveIcon,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun LiveDisplayPreviewState.belongsToRun(runSessionKey: String?): Boolean =
    runSessionKey != null && (sessionKey == runSessionKey || this.runSessionKey == runSessionKey)

/**
 * Conversation cards are keyed by coordinator run, not by the retained
 * native display owner. A later run can claim the same display, so accepting
 * both identities here would mount one live preview in both the old and new
 * task cards. Keep the display-key fallback for callers that do not have a
 * run binding yet.
 */
internal fun LiveDisplayPreviewState.belongsToGroup(groupId: String): Boolean =
    if (runSessionKey != null) {
        runSessionKey == groupId
    } else {
        sessionKey == groupId
    }

private fun LiveDisplayPreviewState.isExpanded(expandedSessionKey: String?): Boolean =
    expandedSessionKey != null &&
            (sessionKey == expandedSessionKey || runSessionKey == expandedSessionKey)

internal fun shouldCombineRecoveryBanners(
    state: SessionState,
    developerStatus: DeveloperModeStatus,
    companionConnected: Boolean,
): Boolean =
    (developerStatus.requiresUserAction || state.showsPhoneAccessRecovery()) && !companionConnected

internal fun shouldShowTopRecoveryBanner(
    state: SessionState,
    developerStatus: DeveloperModeStatus,
    companionConnected: Boolean,
): Boolean = developerStatus.requiresUserAction ||
        state.showsPhoneAccessRecovery() ||
        !companionConnected

private fun SessionState.showsPhoneAccessRecovery(): Boolean = when (this) {
    is SessionState.Running -> currentPurpose.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true) &&
            attentionActionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true)

    is SessionState.Paused -> attentionReason != null &&
            attentionActionLabel.equals(CoordinatorCopy.VIEW_INSTRUCTIONS, ignoreCase = true)

    else -> false
}

internal fun activeTaskRunIds(
    timeline: List<TimelineItem>,
    active: Boolean,
    activeSessionId: String?,
    continuationRunId: String?,
): Set<String> = if (active && activeSessionId != null) {
    groupTimeline(timeline, continuationRunId)
        .firstOrNull { activeSessionId in it.runIds }
        ?.runIds
        ?: setOf(activeSessionId)
} else {
    emptySet()
}

internal fun recentTimelineItems(
    timeline: List<TimelineItem>,
    nowEpochMs: Long,
    active: Boolean,
    activeTaskRunIds: Set<String>,
): List<TimelineItem> {
    val recentCutoff = nowEpochMs - RECENT_HISTORY_WINDOW_MS
    return timeline.filter { item ->
        item.timestampEpochMs >= recentCutoff ||
                (active && item.belongsTo(activeTaskRunIds))
    }
}

private fun TimelineItem.belongsTo(runIds: Set<String>): Boolean = when (this) {
    is TimelineItem.Message -> this.runId?.let(runIds::contains) == true
    is TimelineItem.Activity -> this.runId in runIds
}

private fun SessionState.continuationSessionIdOrNullForUi(): String? = when (this) {
    is SessionState.Running -> sessionId.takeIf { isContinuation }
    is SessionState.Paused -> sessionId.takeIf { isContinuation }
    else -> null
}

private fun SessionState.workedDurationMsOrNullForUi(): Long? = when (this) {
    is SessionState.Stopped -> workedDurationMs
    is SessionState.Completed -> workedDurationMs
    else -> null
}

private const val RECENT_HISTORY_WINDOW_MS = 24L * 60L * 60L * 1000L
