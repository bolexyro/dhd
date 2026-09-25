package com.phonecontrol.assistant.data

import com.phonecontrol.assistant.testing.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class UiPreferencesRepositoryTest {
    private val preferences = InMemorySharedPreferences()
    private val repository = UiPreferencesRepository(preferences)

    @Test
    fun `defaults match what the app and overlay read today`() {
        assertEquals(
            UiPreferences(
                themeMode = "dark",
                reasoningEffort = "high",
                visibleReasoningEfforts = null,
                fastMode = false,
                overlayEnabled = false,
                bubbleX = 24,
                bubbleY = 240,
            ),
            repository.current(),
        )
    }

    @Test
    fun `writes land on the persisted keys`() {
        repository.setThemeMode("light")
        repository.setReasoningEffort("low")
        repository.setVisibleReasoningEfforts("low,high")
        repository.setFastMode(true)
        repository.setOverlayEnabled(true)
        repository.setBubblePosition(10, 20)

        assertEquals(
            mapOf(
                "pref_theme_mode" to "light",
                "pref_reasoning_effort" to "low",
                "pref_visible_reasoning_efforts" to "low,high",
                "pref_fast_mode" to true,
                "pref_overlay_enabled" to true,
                "pref_overlay_bubble_x" to 10,
                "pref_overlay_bubble_y" to 20,
            ),
            preferences.snapshot,
        )
    }

    @Test
    fun `state follows writes from any holder of the preferences`() {
        val overlayRepository = UiPreferencesRepository(preferences)

        overlayRepository.setFastMode(true)
        preferences.edit().putString("pref_theme_mode", "light").apply()

        assertEquals(true, repository.state.value.fastMode)
        assertEquals("light", repository.state.value.themeMode)
    }

    @Test
    fun `writes that do not change a value are skipped`() {
        repository.setFastMode(true)
        repository.setReasoningEffort("low")
        repository.setBubblePosition(10, 20)
        repository.setVisibleReasoningEfforts("low,high")
        val edits = preferences.appliedEdits

        repeat(30) {
            repository.setFastMode(true)
            repository.setReasoningEffort("low")
            repository.setBubblePosition(10, 20)
            repository.setThemeMode("dark")
            repository.setOverlayEnabled(false)
            repository.setVisibleReasoningEfforts("low,high")
        }

        assertEquals(edits, preferences.appliedEdits)
    }
}
