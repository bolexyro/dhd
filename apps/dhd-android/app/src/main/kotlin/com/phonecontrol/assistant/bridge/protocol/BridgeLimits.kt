package com.phonecontrol.assistant.bridge.protocol

internal object BridgeLimits {
    const val MAX_REQUEST_CHARS = 16_384
    const val MAX_TEXT_CHARS = 240
    const val MAX_AGENT_FEEDBACK_CHARS = 4_000
    const val MAX_APP_QUERY_CHARS = 120
    const val MAX_APP_BROWSE_RESULTS = 25
    const val MAX_GUARD_REGIONS = 8
    const val MAX_SEQUENCE_ACTIONS = 16
    const val MAX_OBSERVATIONS = 64
    const val OPEN_SETTLE_DELAY_MS = 750L
    const val POST_ACTION_SETTLE_DELAY_MS = 350L
    const val CAPTURE_ATTEMPTS = 5
    const val CAPTURE_RETRY_DELAY_MS = 250L
    val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
    val DISPLAY_REF_PATTERN = Regex("dsp_[a-f0-9]{14}")
}
