package com.phonecontrol.assistant.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.phonecontrol.assistant.domain.ReasoningEffort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

data class UiPreferences(
    val themeMode: String?,
    val reasoningEffort: String?,
    val visibleReasoningEfforts: String?,
    val fastMode: Boolean,
    val overlayEnabled: Boolean,
    val bubbleX: Int,
    val bubbleY: Int,
)

class UiPreferencesRepository(private val preferences: SharedPreferences) {
    val changes: Flow<UiPreferences> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(current()) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        send(current())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    fun current(): UiPreferences = UiPreferences(
        themeMode = preferences.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE),
        reasoningEffort = preferences.getString(KEY_REASONING_EFFORT, ReasoningEffort.default.storageValue),
        visibleReasoningEfforts = preferences.getString(KEY_VISIBLE_REASONING_EFFORTS, null),
        fastMode = preferences.getBoolean(KEY_FAST_MODE, false),
        overlayEnabled = preferences.getBoolean(KEY_OVERLAY_ENABLED, false),
        bubbleX = preferences.getInt(KEY_BUBBLE_X, DEFAULT_BUBBLE_X),
        bubbleY = preferences.getInt(KEY_BUBBLE_Y, DEFAULT_BUBBLE_Y),
    )

    fun setThemeMode(storageValue: String) {
        preferences.edit { putString(KEY_THEME_MODE, storageValue) }
    }

    fun setReasoningEffort(storageValue: String) {
        preferences.edit { putString(KEY_REASONING_EFFORT, storageValue) }
    }

    fun setVisibleReasoningEfforts(storageValue: String) {
        preferences.edit { putString(KEY_VISIBLE_REASONING_EFFORTS, storageValue) }
    }

    fun setFastMode(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_FAST_MODE, enabled) }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_OVERLAY_ENABLED, enabled) }
    }

    fun setBubblePosition(x: Int, y: Int) {
        preferences.edit {
            putInt(KEY_BUBBLE_X, x)
            putInt(KEY_BUBBLE_Y, y)
        }
    }

    companion object {
        const val PREFS_NAME = "dhd_ui_preferences"
        const val KEY_THEME_MODE = "pref_theme_mode"
        const val KEY_REASONING_EFFORT = "pref_reasoning_effort"
        const val KEY_VISIBLE_REASONING_EFFORTS = "pref_visible_reasoning_efforts"
        const val KEY_FAST_MODE = "pref_fast_mode"
        const val KEY_OVERLAY_ENABLED = "pref_overlay_enabled"
        const val KEY_BUBBLE_X = "pref_overlay_bubble_x"
        const val KEY_BUBBLE_Y = "pref_overlay_bubble_y"
        const val DEFAULT_THEME_MODE = "dark"
        const val DEFAULT_BUBBLE_X = 24
        const val DEFAULT_BUBBLE_Y = 240
    }
}
