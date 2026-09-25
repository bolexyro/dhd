package com.phonecontrol.assistant.observation

internal data class FocusedComponent(
    val packageName: String,
    val activityName: String,
)

internal object ActivityDumpParser {
    val COMPONENT_REGEX = Regex(
        "\\b([A-Za-z][A-Za-z0-9_.$]*)/(\\.?[A-Za-z0-9_.$]+)",
    )

    private val DISPLAY_ID_REGEX = Regex(
        "(?:\\bdisplayId\\s*[:=]?\\s*(\\d+))|(?:\\bmDisplayId\\s*[:=]?\\s*(\\d+))|(?:\\bDisplay\\s*#?\\s*(\\d+)\\b)",
        RegexOption.IGNORE_CASE,
    )

    private val FOCUS_REGEX = Regex(
        "m(?:CurrentFocus|FocusedApp)=.*\\s([A-Za-z0-9_.\\$]+)/(\\.?[A-Za-z0-9_.\\$]+)",
    )

    fun focusedWindow(text: String): FocusedComponent? {
        val match = FOCUS_REGEX.find(text) ?: return null
        val packageName = match.groupValues[1]
        val activityName = match.groupValues[2].let { raw ->
            if (raw.startsWith('.')) packageName + raw else raw
        }
        return FocusedComponent(packageName, activityName)
    }

    /**
     * Returns whether [packageName] still has an activity/task on [displayId].
     * A null result means the command output did not contain a recognizable
     * section for that display, so callers must treat the observation as unknown.
     */
    fun displayTaskPresence(
        text: String,
        displayId: Int,
        packageName: String,
    ): Boolean? {
        if (displayId <= 0 || packageName.isBlank()) return null
        val packagePattern = Regex(
            "(?<![A-Za-z0-9_.])${Regex.escape(packageName)}(?:/|(?=[^A-Za-z0-9_.]|$))",
        )
        var currentDisplay: Int? = null
        var sawDisplay = false
        for (line in text.lineSequence()) {
            DISPLAY_ID_REGEX.find(line)?.let { match ->
                currentDisplay = match.groupValues
                    .drop(1)
                    .firstOrNull(String::isNotBlank)
                    ?.toIntOrNull()
                if (currentDisplay == displayId) sawDisplay = true
            }
            if (currentDisplay == displayId && packagePattern.containsMatchIn(line)) return true
        }
        return if (sawDisplay) false else null
    }

    fun displayFocusedWindow(text: String, displayId: Int): FocusedComponent? {
        var currentDisplay: Int? = null
        var candidate: FocusedComponent? = null
        for (line in text.lineSequence()) {
            DISPLAY_ID_REGEX.find(line)?.let { match ->
                currentDisplay = match.groupValues
                    .drop(1)
                    .firstOrNull(String::isNotBlank)
                    ?.toIntOrNull()
            }
            val marker = line.contains("topResumedActivity", ignoreCase = true) ||
                line.contains("mResumedActivity", ignoreCase = true) ||
                line.contains("mCurrentFocus", ignoreCase = true) ||
                line.contains("mFocusedApp", ignoreCase = true)
            if (!marker || currentDisplay != displayId) continue
            COMPONENT_REGEX.find(line)?.let { match ->
                val packageName = match.groupValues[1]
                val rawActivity = match.groupValues[2]
                val activityName = if (rawActivity.startsWith('.')) packageName + rawActivity else rawActivity
                candidate = FocusedComponent(packageName, activityName)
            }
        }
        return candidate
    }
}
