package com.phonecontrol.assistant.overlay.composer

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.overlay.bubble.DhdIdentity
import com.phonecontrol.assistant.overlay.effects.ComposerPerimeterGlow
import com.phonecontrol.assistant.ui.components.reasoning.FastModeButton
import com.phonecontrol.assistant.ui.components.reasoning.ReasoningEffortButton
import com.phonecontrol.assistant.ui.components.reasoning.ReasoningEffortTrack
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.math.abs

private const val MAX_DRAFT_LENGTH = 4_000

@Composable
internal fun DhdComposerPreview(
    modifier: Modifier = Modifier,
    onCollapse: () -> Unit,
    onSwipeCollapse: (swipedLeft: Boolean) -> Unit,
    onShowPreview: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val density = LocalDensity.current
    val collapseThreshold = with(density) { 48.dp.toPx() }
    Box(
        modifier = modifier
            // Keep swipe-to-collapse at the container level so it can coexist
            // with the logo's tap and long-press gestures without a full-card
            // clickable indication covering the composer.
            .pointerInput(onCollapse) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        totalDrag += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        if (kotlin.math.abs(totalDrag) >= collapseThreshold) {
                            onSwipeCollapse(totalDrag < 0f)
                        }
                    },
                    onDragCancel = { totalDrag = 0f },
                )
            }
            .semantics {
                contentDescription = "Swipe left to dock the bubble on the left or right to dock it on the right. Long press the DHD logo to show or hide the virtual display preview."
            },
    ) {
        ComposerPerimeterGlow(
            modifier = Modifier.matchParentSize(),
            hasRecovery = false,
            capsule = true,
        )
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = colors.composerBackground.copy(alpha = if (colors.isDark) 0.98f else 0.97f),
            border = BorderStroke(
                width = 0.8.dp,
                color = colors.borderColor.copy(alpha = if (colors.isDark) 0.9f else 0.95f),
            ),
        ) {
            Composer(
                onSubmit = {},
                onContinueInDhd = {},
                onCollapse = onCollapse,
                onShowPreview = onShowPreview,
                onTextFieldFocusChanged = {},
                onComposerTapped = {},
                fastMode = false,
                onSetFastMode = {},
                reasoningEffort = ReasoningEffort.default,
                visibleReasoningEfforts = ReasoningEffort.entries,
                onSelectReasoningEffort = {},
                previewReadOnly = true,
            )
        }
    }
}

@Composable
internal fun Composer(
    onSubmit: (String) -> Unit,
    onContinueInDhd: () -> Unit,
    onCollapse: () -> Unit,
    onShowPreview: () -> Unit,
    onTextFieldFocusChanged: (Boolean) -> Unit,
    onComposerTapped: () -> Unit,
    fastMode: Boolean,
    onSetFastMode: (Boolean) -> Unit,
    reasoningEffort: ReasoningEffort,
    visibleReasoningEfforts: List<ReasoningEffort>,
    onSelectReasoningEffort: (ReasoningEffort) -> Unit,
    hint: String = "Ask DHD",
    previewReadOnly: Boolean = false,
) {
    val colors = LocalAssistantColors.current
    var draft by rememberSaveable { mutableStateOf("") }
    var reasoningSelectorOpen by rememberSaveable { mutableStateOf(false) }
    var focusRequestToken by remember { mutableStateOf(0) }
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current
    val textToolbar = remember(clipboardManager) {
        OverlayTextToolbar(clipboardManager) { clipText ->
            draft = (draft + clipText).take(MAX_DRAFT_LENGTH)
        }
    }
    val imeBottom = WindowInsets.ime.getBottom(density)
    // Do not let the row appear during the IME's first few animation frames.
    // That intermediate layout pass was what made the overlay bounce while the
    // keyboard was opening and closing.
    val keyboardVisible = imeBottom > with(density) { 96.dp.toPx() }

    LaunchedEffect(keyboardVisible) {
        if (!keyboardVisible) {
            reasoningSelectorOpen = false
            textToolbar.hide()
        }
    }

    LaunchedEffect(focusRequestToken) {
        if (focusRequestToken == 0) return@LaunchedEffect
        // The overlay window becomes focusable in response to the same touch
        // that reached this field. Retry across a few frames so the request is
        // made after WindowManager has granted the overlay window focus.
        repeat(6) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }

    fun submit() {
        textToolbar.hide()
        val request = draft.trim()
        if (request.isEmpty()) return
        draft = ""
        focus.clearFocus()
        onSubmit(request)
    }

    CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (keyboardVisible) 14.dp else 12.dp,
                        end = if (keyboardVisible) 14.dp else 12.dp,
                        top = if (keyboardVisible) 14.dp else 12.dp,
                        bottom = if (keyboardVisible) 8.dp else 12.dp,
                    ),
                verticalAlignment = if (keyboardVisible) Alignment.Top else Alignment.CenterVertically,
            ) {
            Box(
                modifier = Modifier
                    .size(if (keyboardVisible) 42.dp else 48.dp)
                    .pointerInput(onContinueInDhd, onCollapse, onShowPreview) {
                        detectTapGestures(
                            onTap = { onContinueInDhd() },
                            onDoubleTap = {
                                focus.clearFocus(force = true)
                                onCollapse()
                            },
                            onLongPress = { onShowPreview() },
                        )
                    }
                    .semantics {
                        contentDescription =
                            "DHD. Tap to continue in DHD, double tap to collapse, long press to show the virtual display."
                    },
                contentAlignment = Alignment.Center,
            ) {
                DhdIdentity(
                    modifier = Modifier.fillMaxSize(),
                    working = false,
                    // Keep the idle composer static. The working perimeter
                    // glow carries the motion; a continuously invalidated
                    // icon competes with the IME and live preview for frames.
                    animated = false,
                )
            }
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(MAX_DRAFT_LENGTH) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .pointerInput(onComposerTapped) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onComposerTapped()
                            focusRequestToken += 1
                        }
                    }
                    .onFocusChanged { onTextFieldFocusChanged(it.isFocused) }
                    .then(
                        if (keyboardVisible) {
                            Modifier.heightIn(min = 38.dp, max = 126.dp)
                        } else {
                            Modifier
                        }
                    )
                .semantics { contentDescription = "Message DHD assistant" },
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
                cursorBrush = Brush.verticalGradient(
                    listOf(colors.accentBlue, colors.accentBlue.copy(alpha = 0.55f)),
                ),
                minLines = 1,
                maxLines = if (keyboardVisible) 4 else 1,
                readOnly = previewReadOnly,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { field ->
                    Box(
                        contentAlignment = if (keyboardVisible) Alignment.TopStart else Alignment.CenterStart,
                        modifier = if (keyboardVisible) Modifier.padding(top = 8.dp) else Modifier,
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                text = hint,
                                color = colors.textSecondary,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                            )
                        }
                        field()
                    }
                },
            )
            if (!keyboardVisible) {
                Spacer(Modifier.width(8.dp))
                GlyphButton(
                    label = "Send request",
                    glyph = "send",
                    onClick = ::submit,
                    enabled = draft.isNotBlank(),
                    filled = true,
                    buttonSize = 48.dp,
                    iconSize = 24.dp,
                )
            }
        }

        if (keyboardVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                FastModeButton(
                    enabled = true,
                    selected = fastMode,
                    onToggle = { onSetFastMode(!fastMode) },
                )
                Spacer(Modifier.width(8.dp))
                ReasoningEffortButton(
                    effort = reasoningEffort,
                    visibleEfforts = visibleReasoningEfforts,
                    enabled = true,
                    expanded = reasoningSelectorOpen,
                    onClick = { reasoningSelectorOpen = !reasoningSelectorOpen },
                )
                Spacer(Modifier.width(10.dp))
                GlyphButton(
                    label = "Send request",
                    glyph = "send",
                    onClick = ::submit,
                    enabled = draft.isNotBlank(),
                    filled = true,
                    buttonSize = 36.dp,
                    iconSize = 18.dp,
                )
            }
            if (reasoningSelectorOpen) {
                // This is deliberately a non-focusable popup. A selector that
                // participates in the composer layout changes the overlay
                // window height and Android responds by dismissing/reopening
                // the IME. Keeping it in its own touchable window preserves
                // both the keyboard and the compact composer geometry.
                Popup(
                    alignment = Alignment.BottomEnd,
                    offset = with(density) {
                        IntOffset(0, -56.dp.roundToPx())
                    },
                    onDismissRequest = { reasoningSelectorOpen = false },
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnBackPress = false,
                        dismissOnClickOutside = true,
                        clippingEnabled = false,
                    ),
                ) {
                    Surface(
                        modifier = Modifier.width(286.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = colors.composerBackground,
                        border = androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            colors.borderColor,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                start = 14.dp,
                                top = 12.dp,
                                end = 14.dp,
                                bottom = 12.dp,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${reasoningEffort.label} reasoning",
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.weight(1f))
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = "Close reasoning selector",
                                            onClick = { reasoningSelectorOpen = false },
                                        )
                                        .semantics { contentDescription = "Close reasoning selector" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "×",
                                        color = colors.textSecondary,
                                        fontSize = 18.sp,
                                        lineHeight = 18.sp,
                                    )
                                }
                            }
                            ReasoningEffortTrack(
                                selectedEffort = reasoningEffort,
                                visibleEfforts = visibleReasoningEfforts,
                                onSelect = {
                                    onSelectReasoningEffort(it)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    textToolbar.currentMenu?.let { menu ->
            TextToolbarPopup(menu = menu, onDismiss = { textToolbar.hide() })
        }
    }
}
