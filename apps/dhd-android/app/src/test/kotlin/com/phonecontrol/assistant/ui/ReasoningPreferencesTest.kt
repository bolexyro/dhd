package com.phonecontrol.assistant.ui

import com.phonecontrol.assistant.data.UiPreferencesRepository
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.testing.InMemorySharedPreferences
import com.phonecontrol.assistant.ui.components.reasoning.normalizeReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.selectReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.selectedReasoningEffort
import com.phonecontrol.assistant.ui.components.reasoning.setReasoningEffortVisible
import com.phonecontrol.assistant.ui.components.reasoning.visibleReasoningEffortList
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningPreferencesTest {
    private val preferences = InMemorySharedPreferences()
    private val repository = UiPreferencesRepository(preferences)

    @Test
    fun `hiding the selected effort falls back to the first visible one`() {
        repository.selectReasoningEffort(ReasoningEffort.HIGH)

        repository.setReasoningEffortVisible(ReasoningEffort.HIGH, visible = false)

        assertEquals(ReasoningEffort.LIGHT, repository.state.value.selectedReasoningEffort)
        assertEquals("light", preferences.snapshot["pref_reasoning_effort"])
        assertEquals(
            listOf(ReasoningEffort.LIGHT, ReasoningEffort.MEDIUM, ReasoningEffort.EXTRA_HIGH, ReasoningEffort.MAX),
            repository.state.value.visibleReasoningEffortList,
        )
    }

    @Test
    fun `the last visible effort cannot be hidden`() {
        repository.setVisibleReasoningEfforts("medium")

        repository.setReasoningEffortVisible(ReasoningEffort.MEDIUM, visible = false)

        assertEquals(listOf(ReasoningEffort.MEDIUM), repository.state.value.visibleReasoningEffortList)
    }

    @Test
    fun `a hidden effort cannot be selected and a stale one is normalized`() {
        repository.setVisibleReasoningEfforts("light,medium")
        repository.selectReasoningEffort(ReasoningEffort.MAX)
        assertEquals(null, preferences.snapshot["pref_reasoning_effort"])

        repository.normalizeReasoningEffort()

        assertEquals("light", preferences.snapshot["pref_reasoning_effort"])
    }
}
