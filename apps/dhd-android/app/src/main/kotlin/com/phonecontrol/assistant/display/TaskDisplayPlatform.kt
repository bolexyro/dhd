package com.phonecontrol.assistant.display

import android.content.Context
import android.hardware.display.DisplayManager
import com.phonecontrol.assistant.execution.TaskDisplayLayoutPreferences

internal interface TaskDisplayPlatform {
    fun isFullSizeLayoutEnabled(packageName: String): Boolean
    fun displayRotation(displayId: Int): Int?
    fun hasLaunchIntent(packageName: String): Boolean
}

internal class AndroidTaskDisplayPlatform(
    private val appContext: Context,
    private val layoutPreferences: TaskDisplayLayoutPreferences,
) : TaskDisplayPlatform {
    override fun isFullSizeLayoutEnabled(packageName: String): Boolean =
        layoutPreferences.isFullSizeLayoutEnabled(packageName)

    override fun displayRotation(displayId: Int): Int? =
        appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)
            ?.rotation

    override fun hasLaunchIntent(packageName: String): Boolean =
        appContext.packageManager.getLaunchIntentForPackage(packageName) != null
}
