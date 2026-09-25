package com.phonecontrol.assistant.ui.components.reasoning

import com.phonecontrol.assistant.domain.ReasoningEffort

internal fun visibleReasoningEffortsFromStorage(value: String?): List<ReasoningEffort> {
    val storedValues = value
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        .orEmpty()
    val configured = ReasoningEffort.entries.filter { it.storageValue in storedValues }
    return configured.ifEmpty { ReasoningEffort.entries }
}

internal fun effectiveReasoningEffort(
    storedValue: String?,
    visibleEfforts: List<ReasoningEffort>,
): ReasoningEffort = ReasoningEffort.fromStorage(storedValue).takeIf { it in visibleEfforts }
    ?: visibleEfforts.first()
