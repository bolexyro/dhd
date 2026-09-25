package com.phonecontrol.assistant.core

import com.phonecontrol.assistant.session.SessionState

val SessionState.isActive: Boolean
    get() = this is SessionState.Running || this is SessionState.Paused

val SessionState.sessionIdOrNull: String?
    get() = when (this) {
        SessionState.Idle -> null
        is SessionState.Running -> sessionId
        is SessionState.Paused -> sessionId
        is SessionState.Stopped -> sessionId
        is SessionState.Completed -> sessionId
    }

val SessionState.conversationIdOrNull: String?
    get() = when (this) {
        SessionState.Idle -> null
        is SessionState.Running -> conversationId
        is SessionState.Paused -> conversationId
        is SessionState.Stopped -> conversationId
        is SessionState.Completed -> conversationId
    }

val SessionState.currentPurposeOrNull: String?
    get() = when (this) {
        is SessionState.Running -> currentPurpose
        is SessionState.Paused -> currentPurpose
        else -> null
    }

val SessionState.needsAttention: Boolean
    get() = currentPurposeOrNull?.equals(CoordinatorCopy.NEEDS_ATTENTION, ignoreCase = true) == true
