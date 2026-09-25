package com.phonecontrol.assistant.overlay

import android.content.Context
import com.phonecontrol.assistant.data.UiPreferencesRepository

object OverlayPreferences {
    private fun repository(context: Context) = UiPreferencesRepository(
        context.applicationContext.getSharedPreferences(UiPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun isEnabled(context: Context): Boolean = repository(context).current().overlayEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        repository(context).setOverlayEnabled(enabled)
    }

    fun bubblePosition(context: Context): BubblePosition {
        val preferences = repository(context).current()
        return BubblePosition(x = preferences.bubbleX, y = preferences.bubbleY)
    }

    fun setBubblePosition(context: Context, position: BubblePosition) {
        repository(context).setBubblePosition(position.x, position.y)
    }
}
