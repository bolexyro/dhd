@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.phonecontrol.assistant.ui.chat

import android.view.Surface as AndroidSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.chat.composer.PendingSteerDraft
import com.phonecontrol.assistant.ui.chat.composer.RequestComposer
import com.phonecontrol.assistant.ui.chat.composer.SteerDraftBar
import com.phonecontrol.assistant.ui.chat.composer.SteerDraftQueue
import com.phonecontrol.assistant.ui.chat.composer.forActiveSession
import com.phonecontrol.assistant.ui.chat.composer.promoteAfterCompletion
import com.phonecontrol.assistant.ui.chat.composer.steerDraftsSaver
import com.phonecontrol.assistant.ui.chat.status.rememberElapsedSeconds
import com.phonecontrol.assistant.ui.chat.timeline.ConversationTimeline
import com.phonecontrol.assistant.ui.chat.timeline.activeTaskRunIds
import com.phonecontrol.assistant.ui.chat.timeline.recentTimelineItems
import com.phonecontrol.assistant.ui.components.CircleIconButton
import com.phonecontrol.assistant.ui.components.DhdConfirmDialog
import com.phonecontrol.assistant.ui.components.reasoning.ReasoningEffortOverlay
import com.phonecontrol.assistant.ui.displays.LiveDisplayPreviewState
import com.phonecontrol.assistant.ui.displays.surface.PreviewSurfaceDestroyed
import com.phonecontrol.assistant.ui.recovery.CombinedRecoveryCard
import com.phonecontrol.assistant.ui.recovery.CompanionRecoveryCard
import com.phonecontrol.assistant.ui.recovery.DeveloperConnectionRecoveryCard
import com.phonecontrol.assistant.ui.recovery.shouldCombineRecoveryBanners
import com.phonecontrol.assistant.ui.recovery.shouldShowTopRecoveryBanner
import com.phonecontrol.assistant.ui.recovery.showsPhoneAccessRecovery
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
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
    val state by viewModel.sessionState.collectAsState()
    val toolCalls by viewModel.toolCalls.collectAsState()
    val timeline by viewModel.timeline.collectAsState()
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
                    CircleIconButton(
                        icon = R.drawable.ic_laptop,
                        contentDescription = "Task displays",
                        onClick = onOpenTaskDisplays,
                        buttonSize = 48.dp,
                        iconSize = 22.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    CircleIconButton(
                        icon = R.drawable.ic_compose_new,
                        contentDescription = "Start fresh",
                        onClick = { showStartFreshConfirmation = true },
                        buttonSize = 48.dp,
                        iconSize = 23.dp,
                        enabled = !active,
                        tint = if (active) colors.textSecondary.copy(alpha = 0.4f) else colors.textPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    // Settings circular button (48dp)
                    CircleIconButton(
                        icon = R.drawable.ic_settings,
                        contentDescription = "Settings",
                        onClick = onOpenSettings,
                        buttonSize = 48.dp,
                        iconSize = 23.dp,
                    )
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
        DhdConfirmDialog(
            title = "Start fresh?",
            message = "This clears the DHD conversation timeline and rotates its stored Codex thread " +
                    "binding. App permissions and DHD's local phone connection remain unchanged.",
            confirmLabel = "Start fresh",
            onConfirm = {
                showStartFreshConfirmation = false
                steerDrafts = emptyList()
                steerDraftSessionId = null
                carrySteerDraftsToNextRun = false
                composerEditText = null
                viewModel.startFresh()
                onStartFresh()
            },
            onDismiss = { showStartFreshConfirmation = false },
        )
    }
}

internal fun SessionState.continuationSessionIdOrNullForUi(): String? = when (this) {
    is SessionState.Running -> sessionId.takeIf { isContinuation }
    is SessionState.Paused -> sessionId.takeIf { isContinuation }
    else -> null
}

internal fun SessionState.workedDurationMsOrNullForUi(): Long? = when (this) {
    is SessionState.Stopped -> workedDurationMs
    is SessionState.Completed -> workedDurationMs
    else -> null
}
