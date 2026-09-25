package com.phonecontrol.assistant.data

import com.phonecontrol.assistant.testing.InMemorySharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `changes publish the current snapshot and every later write`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<UiPreferences>()
        backgroundScope.launch { repository.changes.collect { seen += it } }

        repository.setFastMode(true)
        repository.setThemeMode("light")

        assertEquals(listOf(false, true, true), seen.map(UiPreferences::fastMode))
        assertEquals(listOf("dark", "dark", "light"), seen.map(UiPreferences::themeMode))
    }
}
