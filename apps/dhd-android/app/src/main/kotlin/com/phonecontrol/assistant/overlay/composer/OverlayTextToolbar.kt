package com.phonecontrol.assistant.overlay.composer

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors
import kotlin.math.roundToInt

internal data class TextToolbarMenu(
    val rect: Rect,
    val onCopy: (() -> Unit)?,
    val onPaste: (() -> Unit)?,
    val onCut: (() -> Unit)?,
    val onSelectAll: (() -> Unit)?,
)

internal class OverlayTextToolbar(
    private val clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    private val onFallbackPaste: (String) -> Unit,
) : TextToolbar {
    var currentMenu by mutableStateOf<TextToolbarMenu?>(null)
        private set

    override val status: TextToolbarStatus
        get() = if (currentMenu != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val hasClip = clipboardManager.hasText()
        val pasteAction = onPasteRequested ?: if (hasClip) {
            {
                val text = clipboardManager.getText()?.text
                if (!text.isNullOrEmpty()) {
                    onFallbackPaste(text)
                }
            }
        } else null

        currentMenu = TextToolbarMenu(
            rect = rect,
            onCopy = onCopyRequested,
            onPaste = pasteAction,
            onCut = onCutRequested,
            onSelectAll = onSelectAllRequested,
        )
    }

    override fun hide() {
        currentMenu = null
    }
}

@Composable
internal fun TextToolbarPopup(
    menu: TextToolbarMenu,
    onDismiss: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    val density = LocalDensity.current

    val hasActions = menu.onCut != null || menu.onCopy != null || menu.onPaste != null || menu.onSelectAll != null
    if (!hasActions) return

    val toolbarHeight = with(density) { 48.dp.roundToPx() }
    val spacingPx = with(density) { 8.dp.roundToPx() }
    val yOffset = if (menu.rect.top > toolbarHeight + spacingPx) {
        (menu.rect.top - toolbarHeight - spacingPx).roundToInt()
    } else {
        (menu.rect.bottom + spacingPx).roundToInt()
    }.coerceAtLeast(with(density) { 6.dp.roundToPx() })
    val xOffset = (menu.rect.left - with(density) { 12.dp.roundToPx() }).roundToInt()
        .coerceAtLeast(with(density) { 10.dp.roundToPx() })

    Popup(
        offset = IntOffset(xOffset, yOffset),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.composerBackground,
            border = androidx.compose.foundation.BorderStroke(0.8.dp, colors.borderColor),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var needsDivider = false
                if (menu.onCut != null) {
                    TextToolbarItem("Cut") {
                        menu.onCut.invoke()
                        onDismiss()
                    }
                    needsDivider = true
                }
                if (menu.onCopy != null) {
                    if (needsDivider) ToolbarDivider()
                    TextToolbarItem("Copy") {
                        menu.onCopy.invoke()
                        onDismiss()
                    }
                    needsDivider = true
                }
                if (menu.onPaste != null) {
                    if (needsDivider) ToolbarDivider()
                    TextToolbarItem("Paste") {
                        menu.onPaste.invoke()
                        onDismiss()
                    }
                    needsDivider = true
                }
                if (menu.onSelectAll != null) {
                    if (needsDivider) ToolbarDivider()
                    TextToolbarItem("Select all") {
                        menu.onSelectAll.invoke()
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarDivider() {
    val colors = LocalAssistantColors.current
    Box(
        modifier = Modifier
            .width(0.8.dp)
            .height(16.dp)
            .background(colors.borderColor.copy(alpha = 0.7f)),
    )
}

@Composable
private fun TextToolbarItem(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
