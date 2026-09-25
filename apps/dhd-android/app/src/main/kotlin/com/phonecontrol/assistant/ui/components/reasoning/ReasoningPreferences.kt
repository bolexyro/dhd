package com.phonecontrol.assistant.ui.components.reasoning

import com.phonecontrol.assistant.data.UiPreferences
import com.phonecontrol.assistant.data.UiPreferencesRepository
import com.phonecontrol.assistant.domain.ReasoningEffort

internal val UiPreferences.visibleReasoningEffortList: List<ReasoningEffort>
    get() = visibleReasoningEffortsFromStorage(visibleReasoningEfforts)

internal val UiPreferences.selectedReasoningEffort: ReasoningEffort
    get() = effectiveReasoningEffort(reasoningEffort, visibleReasoningEffortList)

internal fun UiPreferencesRepository.selectReasoningEffort(effort: ReasoningEffort) {
    if (effort in current().visibleReasoningEffortList) setReasoningEffort(effort.storageValue)
}

internal fun UiPreferencesRepository.setReasoningEffortVisible(effort: ReasoningEffort, visible: Boolean) {
    val current = current().visibleReasoningEffortList.toSet()
    val next = if (visible) current + effort else current - effort
    if (next.isEmpty()) return
    val ordered = ReasoningEffort.entries.filter { it in next }
    setVisibleReasoningEfforts(ordered.joinToString(",") { it.storageValue })
    normalizeReasoningEffort()
}

internal fun UiPreferencesRepository.normalizeReasoningEffort() {
    val preferences = current()
    val selected = preferences.selectedReasoningEffort
    if (ReasoningEffort.fromStorage(preferences.reasoningEffort) != selected) {
        setReasoningEffort(selected.storageValue)
    }
}
