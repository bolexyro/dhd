package com.phonecontrol.assistant.overlay

import android.view.Surface as AndroidSurface
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.core.needsAttention
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.data.UiPreferencesRepository
import com.phonecontrol.assistant.display.TaskPreviewState
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.overlay.bubble.BubbleButton
import com.phonecontrol.assistant.overlay.cards.FloatingRecoveryCard
import com.phonecontrol.assistant.overlay.cards.FloatingResultCard
import com.phonecontrol.assistant.overlay.cards.FloatingVirtualDisplayCard
import com.phonecontrol.assistant.overlay.composer.Composer
import com.phonecontrol.assistant.overlay.effects.ComposerPerimeterGlow
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.components.GlassSurface
import com.phonecontrol.assistant.ui.components.reasoning.normalizeReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.selectReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.selectedReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.visibleReasoningEffortList
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

@Composable
fun OverlayPanel(
    sessionState: StateFlow<SessionState>,
    toolCalls: StateFlow<List<DhdToolCall>>,
    panelMode: StateFlow<OverlayPanelMode>,
    resultMessage: StateFlow<String?>,
    developerStatus: StateFlow<DeveloperModeStatus>,
    companionConnected: StateFlow<Boolean>,
    pointerEvent: StateFlow<TaskPointerEvent?>,
    onExpand: () -> Unit,
    onNewRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onBubbleDragEnd: () -> Unit,
    onStop: () -> Unit,
    onAcknowledgeAttention: () -> Boolean,
    onOpenPhoneAccess: () -> Unit,
    onOpenCompanion: () -> Unit,
    onContinueInDhd: () -> Unit,
    onCollapse: () -> Unit,
    onDismissResult: () -> Unit = {},
    onHorizontalSwipeDismiss: (OverlaySwipeDirection, Int) -> Unit,
    taskPreviewState: StateFlow<TaskPreviewState>,
    taskDisplaySession: StateFlow<TaskDisplaySession?>,
    overlayHidden: StateFlow<Boolean>,
    onKeyboardVisibilityChanged: (Boolean) -> Unit,
    onTextFieldFocusChanged: (Boolean) -> Unit,
    onComposerTapped: () -> Unit,
    onTaskPreviewSurfaceAvailable: (TaskDisplaySession, AndroidSurface) -> Unit,
    onTaskPreviewSurfaceDestroyed: (TaskDisplaySession, AndroidSurface, () -> Unit) -> Unit,
    preferences: UiPreferencesRepository,
) {
    val state by sessionState.collectAsState()
    val calls by toolCalls.collectAsState()
    val mode by panelMode.collectAsState()
    val result by resultMessage.collectAsState()
    val previewState by taskPreviewState.collectAsState()
    val activeDisplaySession by taskDisplaySession.collectAsState()
    val isOverlayHidden by overlayHidden.collectAsState()
    val currentDeveloperStatus by developerStatus.collectAsState()
    val isCompanionConnected by companionConnected.collectAsState()
    val latestPointerEvent by pointerEvent.collectAsState()
    val active = state is SessionState.Running || state is SessionState.Paused
    val uiPreferences by preferences.state.collectAsState()
    var previewVisible by rememberSaveable { mutableStateOf(false) }
    val fastMode = uiPreferences.fastMode
    val visibleReasoningEfforts = uiPreferences.visibleReasoningEffortList
    val reasoningEffort = uiPreferences.selectedReasoningEffort
    LaunchedEffect(uiPreferences) {
        preferences.normalizeReasoningEffort()
    }
    val setFastMode: (Boolean) -> Unit = preferences::setFastMode
    val setReasoningEffort: (ReasoningEffort) -> Unit = preferences::selectReasoningEffort

    val recoveryKind = overlayRecoveryKind(
        state = state,
        developerStatus = currentDeveloperStatus,
        companionConnected = isCompanionConnected,
    )
    val hasRecovery = state.needsAttention || recoveryKind != null

    val effectiveMode = effectiveOverlayPanelMode(mode, active, hasRecovery)

    val focusManager = LocalFocusManager.current
    LaunchedEffect(effectiveMode) {
        if (effectiveMode == OverlayPanelMode.BUBBLE) {
            focusManager.clearFocus(force = true)
        }
    }

    if (effectiveMode == OverlayPanelMode.BUBBLE) {
        BubbleButton(
            onClick = onExpand,
            onDrag = onDrag,
            onDragEnd = onBubbleDragEnd,
            running = state is SessionState.Running || state is SessionState.Paused,
            attention = hasRecovery,
            animated = state is SessionState.Running && !hasRecovery,
        )
        return
    }

    val colors = LocalAssistantColors.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboardVisible = imeBottom > with(density) { 96.dp.toPx() }

    LaunchedEffect(keyboardVisible) {
        onKeyboardVisibilityChanged(keyboardVisible)
    }

    val resultText = result?.takeIf { it.isNotBlank() } ?: terminalMessage(state)
    val hasFloatingResult = effectiveMode == OverlayPanelMode.RESULT && resultText.isNotBlank()
    val hasFloatingRecovery = recoveryKind != null && effectiveMode != OverlayPanelMode.BUBBLE
    // The composer geometry is independent from the optional cards above it.
    // Opening or closing a card must not change the parent padding/width that
    // anchors the composer window.
    val composerCollapsed = !keyboardVisible

    val bottomCapsuleShape = when {
        effectiveMode == OverlayPanelMode.WORKING || effectiveMode == OverlayPanelMode.ATTENTION -> CircleShape
        !keyboardVisible -> CircleShape
        else -> RoundedCornerShape(28.dp)
    }

    val panelDescription = when (effectiveMode) {
        OverlayPanelMode.COMPOSER -> "DHD assistant. Ready for a request."
        OverlayPanelMode.RESULT -> "DHD assistant. Result ready."
        OverlayPanelMode.ATTENTION -> "DHD assistant needs your attention."
        OverlayPanelMode.WORKING -> "DHD assistant is working."
        OverlayPanelMode.BUBBLE -> "DHD assistant."
    }

    // Keep the content lane wide even when the visible composer is the
    // compact 320.dp pill. This is the expanded-composer width used by both
    // floating cards in every keyboard/IME state.
    val horizontalPadding = 14.dp
    val bottomPadding = when {
        composerCollapsed -> 10.dp
        keyboardVisible -> 20.dp
        else -> 16.dp
    }
    var composerOriginX by remember { mutableStateOf(0) }
    val latestComposerOriginX by rememberUpdatedState(composerOriginX)
    val swipeDismissThresholdPx = with(density) { 56.dp.toPx() }
    val swipeDismissModifier = if (effectiveMode != OverlayPanelMode.BUBBLE) {
        Modifier.pointerInput(onCollapse, onHorizontalSwipeDismiss) {
            var totalDragX = 0f
            var totalDragY = 0f
            var lastPointerX = 0f
            detectDragGestures(
                onDragStart = { startOffset ->
                    totalDragX = 0f
                    totalDragY = 0f
                    lastPointerX = startOffset.x
                },
                onDrag = { change, dragAmount ->
                    totalDragX += dragAmount.x
                    totalDragY += dragAmount.y
                    lastPointerX = change.position.x
                    change.consume()
                },
                onDragEnd = {
                    val horizontalSwipe = abs(totalDragX) >= swipeDismissThresholdPx &&
                        abs(totalDragX) > abs(totalDragY)
                    val downwardSwipe = totalDragY >= swipeDismissThresholdPx &&
                        totalDragY > abs(totalDragX)
                    if (horizontalSwipe) {
                        onHorizontalSwipeDismiss(
                            if (totalDragX < 0f) {
                                OverlaySwipeDirection.LEFT
                            } else {
                                OverlaySwipeDirection.RIGHT
                            },
                            latestComposerOriginX + lastPointerX.roundToInt(),
                        )
                    } else if (downwardSwipe) {
                        onCollapse()
                    }
                },
            )
        }
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 10.dp,
                bottom = bottomPadding,
            )
            .semantics { contentDescription = panelDescription },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val cardWidth = maxWidth.coerceAtMost(520.dp)
        val composerWidth = if (composerCollapsed) {
            320.dp.coerceAtMost(maxWidth)
        } else {
            cardWidth
        }
        var composerHeightPx by remember { mutableStateOf(0) }
        var resultCardHeightPx by remember { mutableStateOf(0) }
        var recoveryCardHeightPx by remember { mutableStateOf(0) }
        val fallbackComposerHeightPx = with(density) { 82.dp.roundToPx() }
        val popupBottomOffsetPx = (composerHeightPx.takeIf { it > 0 } ?: fallbackComposerHeightPx) +
            with(density) { bottomPadding.roundToPx() + 4.dp.roundToPx() }
        val popupSpacingPx = with(density) { 10.dp.roundToPx() }

        // Keep these cards in their own popup windows. They are visually
        // anchored above the composer, but do not take part in measuring the
        // composer window, so opening/closing either card cannot move it.
        if (hasFloatingResult) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -popupBottomOffsetPx),
                onDismissRequest = {},
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    clippingEnabled = false,
                ),
            ) {
                FloatingResultCard(
                    message = resultText,
                    onDismiss = onDismissResult,
                    modifier = Modifier
                        .width(cardWidth)
                        .onGloballyPositioned { resultCardHeightPx = it.size.height },
                )
            }
        }

        if (effectiveMode != OverlayPanelMode.BUBBLE) {
            when (val recovery = recoveryKind) {
                null -> Unit
                else -> Popup(
                    alignment = Alignment.BottomCenter,
                    offset = IntOffset(
                        0,
                        -(popupBottomOffsetPx +
                            if (hasFloatingResult) resultCardHeightPx + popupSpacingPx else 0),
                    ),
                    onDismissRequest = {},
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                        clippingEnabled = false,
                    ),
                ) {
                    FloatingRecoveryCard(
                        kind = recovery,
                        state = state,
                        developerStatus = currentDeveloperStatus,
                        onAcknowledgeAttention = onAcknowledgeAttention,
                        onStop = onStop,
                        onOpenCompanion = onOpenCompanion,
                        onOpenPhoneAccess = onOpenPhoneAccess,
                        modifier = Modifier
                            .width(cardWidth)
                            .onGloballyPositioned { recoveryCardHeightPx = it.size.height },
                    )
                }
            }
        }

        // The overlay window can be GONE while its composition survives an
        // Activity handoff. Remove the TextureView while hidden so the
        // session's single decoder target is released; recreating it on
        // reveal receives a fresh surface and cannot show frozen pixels from
        // the inline/fullscreen viewer.
        val displaySession = activeDisplaySession ?: previewState.sessionOrNull()
        if (shouldRenderOverlayPreview(
                previewVisible = previewVisible,
                overlayHidden = isOverlayHidden,
                hasDisplaySession = displaySession != null,
            )) {
            val displayPointerEvent = latestPointerEvent?.takeIf { event ->
                event.sessionId == state.sessionIdOrNull ||
                    event.sessionId == displaySession?.sessionKey
            }
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(
                    0,
                    -(popupBottomOffsetPx +
                        (if (hasFloatingResult) resultCardHeightPx + popupSpacingPx else 0) +
                        (if (hasFloatingRecovery) recoveryCardHeightPx + popupSpacingPx else 0)),
                ),
                onDismissRequest = {},
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    clippingEnabled = false,
                ),
            ) {
                FloatingVirtualDisplayCard(
                    previewState = previewState,
                    taskDisplaySession = displaySession,
                    pointerEvent = displayPointerEvent,
                    onHide = { previewVisible = false },
                    onContinue = onContinueInDhd,
                    onSurfaceAvailable = onTaskPreviewSurfaceAvailable,
                    onSurfaceDestroyed = onTaskPreviewSurfaceDestroyed,
                    modifier = Modifier.width(cardWidth),
                )
            }
        }

        val containerWidthModifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)

        Column(
            modifier = containerWidthModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.width(composerWidth),
                contentAlignment = Alignment.Center,
            ) {
                if (effectiveMode == OverlayPanelMode.COMPOSER || effectiveMode == OverlayPanelMode.RESULT) {
                    ComposerPerimeterGlow(
                        modifier = Modifier.matchParentSize(),
                        hasRecovery = hasRecovery,
                        capsule = bottomCapsuleShape == CircleShape,
                    )
                }

                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(swipeDismissModifier)
                        .onGloballyPositioned { coordinates ->
                            composerOriginX = coordinates.positionInRoot().x.roundToInt()
                            composerHeightPx = coordinates.size.height
                        },
                    shape = bottomCapsuleShape,
                ) {
                    when (effectiveMode) {
                        OverlayPanelMode.COMPOSER,
                        OverlayPanelMode.RESULT -> Composer(
                            onSubmit = onSubmit,
                            onContinueInDhd = onContinueInDhd,
                            onCollapse = onCollapse,
                            onShowPreview = { previewVisible = true },
                            fastMode = fastMode,
                            onSetFastMode = setFastMode,
                            reasoningEffort = reasoningEffort,
                            visibleReasoningEfforts = visibleReasoningEfforts,
                            onSelectReasoningEffort = setReasoningEffort,
                            onTextFieldFocusChanged = onTextFieldFocusChanged,
                            onComposerTapped = onComposerTapped,
                        )
                        OverlayPanelMode.WORKING,
                        OverlayPanelMode.ATTENTION -> WorkingRow(
                            state = state,
                            calls = calls,
                            recoveryKind = recoveryKind,
                            onStop = onStop,
                            onContinueInDhd = onContinueInDhd,
                            onCollapse = onCollapse,
                            onShowPreview = { previewVisible = true },
                        )
                        OverlayPanelMode.BUBBLE -> Unit
                    }
                }
            }
        }
    }
}

/** Compose the preview only while requested, visible, and backed by a session. */
internal fun shouldRenderOverlayPreview(
    previewVisible: Boolean,
    overlayHidden: Boolean,
    hasDisplaySession: Boolean = true,
): Boolean = previewVisible && !overlayHidden && hasDisplaySession

internal fun TaskPreviewState.sessionOrNull(): TaskDisplaySession? = when (this) {
    is TaskPreviewState.Connecting -> session
    is TaskPreviewState.Attached -> session
    is TaskPreviewState.Ended -> null
    is TaskPreviewState.Error -> null
    TaskPreviewState.Detached -> null
}

internal fun SessionState.attentionReasonOrNull(): String? = when (this) {
    is SessionState.Running -> attentionReason?.takeIf { it.isNotBlank() }
    is SessionState.Paused -> attentionReason?.takeIf { it.isNotBlank() }
    else -> null
}

internal fun SessionState.attentionActionLabelOrNull(): String? = when (this) {
    is SessionState.Running -> attentionActionLabel?.takeIf { it.isNotBlank() }
    is SessionState.Paused -> attentionActionLabel?.takeIf { it.isNotBlank() }
    else -> null
}

private fun terminalMessage(state: SessionState): String = when (state) {
    is SessionState.Completed -> state.message
    is SessionState.Stopped -> ""
    else -> "Ready for your next request."
}
