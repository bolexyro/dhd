package com.phonecontrol.assistant.ui.chat.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.ui.chat.timeline.isExpanded
import com.phonecontrol.assistant.ui.components.reasoning.FastModeButton
import com.phonecontrol.assistant.ui.components.reasoning.ReasoningEffortButton
import com.phonecontrol.assistant.ui.theme.AssistantColorScheme
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlinx.coroutines.flow.collect

@Composable
internal fun RequestComposer(
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
                contentDescription = stringResource(R.string.chat_attach),
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
                            contentDescription = stringResource(R.string.chat_steer_active_task),
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
                        contentDescription = stringResource(R.string.chat_stop_task),
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
                    contentDescription = stringResource(R.string.chat_continue_task),
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
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (hasText && enabled) colors.sendButtonActiveIcon else colors.sendButtonInactiveIcon,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
