package com.phonecontrol.assistant.overlay

import android.content.Context
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.data.UiPreferencesRepository
import com.phonecontrol.assistant.overlay.bubble.BubblePosition

object OverlayPreferences {
    private fun repository(context: Context): UiPreferencesRepository =
        (context.applicationContext as PhoneControlApplication).container.uiPreferencesRepository

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
