package com.phonecontrol.assistant.overlay

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import com.phonecontrol.assistant.adb.DeveloperModeStatus
import com.phonecontrol.assistant.core.isActive
import com.phonecontrol.assistant.core.needsAttention
import com.phonecontrol.assistant.core.sessionIdOrNull
import com.phonecontrol.assistant.session.SessionState

enum class OverlayPanelMode { BUBBLE, COMPOSER, WORKING, ATTENTION, RESULT }

enum class OverlaySwipeDirection { LEFT, RIGHT }

internal enum class OverlayRecoveryKind { ATTENTION, COMPANION, DEVELOPER }

internal fun effectiveOverlayPanelMode(
    mode: OverlayPanelMode,
    active: Boolean,
    hasRecovery: Boolean,
): OverlayPanelMode =
    // An explicit collapse request wins over the session state. The perimeter glow is separate.
    when {
        mode == OverlayPanelMode.BUBBLE -> OverlayPanelMode.BUBBLE
        active && hasRecovery -> OverlayPanelMode.ATTENTION
        active -> OverlayPanelMode.WORKING
        else -> mode
    }

internal fun overlayRecoveryKind(
    state: SessionState,
    developerStatus: DeveloperModeStatus,
    companionConnected: Boolean,
): OverlayRecoveryKind? {
    if (state.needsAttention) return OverlayRecoveryKind.ATTENTION

    val developerConnectionNeedsAction = developerStatus.requiresUserAction
    if (developerConnectionNeedsAction) return OverlayRecoveryKind.DEVELOPER

    if (state !is SessionState.Running && state !is SessionState.Paused) return null

    // A Codex retry/release can leave the companion connected. Only show the
    // disconnected recovery overlay when the phone-side heartbeat lease is
    // actually absent.
    if (!companionConnected) {
        return OverlayRecoveryKind.COMPANION
    }
    return null
}

internal fun nextOverlayPanelMode(
    currentMode: OverlayPanelMode,
    previousState: SessionState,
    state: SessionState,
): OverlayPanelMode {
    val wasActive = previousState.isActive
    val isActive = state.isActive
    if (isActive) {
        val newSession = !wasActive ||
            previousState.sessionIdOrNull != state.sessionIdOrNull
        val attentionStarted = state.needsAttention && !previousState.needsAttention
        return when {
            attentionStarted -> OverlayPanelMode.ATTENTION
            newSession -> if (state.needsAttention) {
                OverlayPanelMode.ATTENTION
            } else {
                OverlayPanelMode.WORKING
            }
            currentMode == OverlayPanelMode.BUBBLE -> OverlayPanelMode.BUBBLE
            state.needsAttention -> OverlayPanelMode.ATTENTION
            else -> OverlayPanelMode.WORKING
        }
    }
    return if (wasActive) {
        when (state) {
            is SessionState.Completed -> OverlayPanelMode.RESULT
            is SessionState.Stopped -> if (currentMode == OverlayPanelMode.BUBBLE) {
                OverlayPanelMode.BUBBLE
            } else {
                OverlayPanelMode.COMPOSER
            }
            else -> currentMode
        }
    } else {
        currentMode
    }
}

internal fun overlayPanelModeForUserExpand(state: SessionState): OverlayPanelMode =
    if (state.isActive) {
        if (state.needsAttention) OverlayPanelMode.ATTENTION else OverlayPanelMode.WORKING
    } else {
        OverlayPanelMode.COMPOSER
    }

internal fun shouldShowOverlayGlow(
    mode: OverlayPanelMode,
    state: SessionState,
    hidden: Boolean,
): Boolean = !hidden && mode == OverlayPanelMode.COMPOSER && !state.isActive
