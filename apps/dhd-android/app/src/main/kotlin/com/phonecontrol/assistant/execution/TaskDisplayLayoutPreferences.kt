package com.phonecontrol.assistant.execution

import android.content.Context

/**
 * Per-app display-layout preferences for the fixed task display.
 *
 * This intentionally stores a per-app choice rather than a package exception.
 * The standard layout remains the default; the Task Displays UI or the DHD
 * layout tool can opt an app into a larger logical canvas when its content is
 * clipped or scaled incorrectly.
 */
class TaskDisplayLayoutPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun fullSizeLayoutPackages(): Set<String> = preferences
        .getStringSet(KEY_FULL_SIZE_LAYOUT_PACKAGES, emptySet())
        ?.toSet()
        ?: emptySet()

    fun isFullSizeLayoutEnabled(packageName: String): Boolean {
        require(packageName.isNotBlank()) { "Package name must not be blank." }
        return packageName in fullSizeLayoutPackages()
    }

    fun setFullSizeLayoutEnabled(packageName: String, enabled: Boolean) {
        require(packageName.isNotBlank()) { "Package name must not be blank." }
        val packages = fullSizeLayoutPackages().toMutableSet()
        if (enabled) {
            packages += packageName
        } else {
            packages -= packageName
        }
        preferences.edit()
            .putStringSet(KEY_FULL_SIZE_LAYOUT_PACKAGES, packages)
            .apply()
    }

    companion object {
        internal const val PREFERENCES_NAME = "dhd_task_display_preferences"
        internal const val KEY_FULL_SIZE_LAYOUT_PACKAGES = "full_size_layout_packages"
    }
}

/**
 * Give the app a larger logical canvas while keeping the encoded task display
 * geometry fixed. The density stays at the S23-compatible base so Android
 * lays out normal dp-sized controls, while the native display service scales
 * captures and input back into the fixed task coordinate space.
 */
fun TaskDisplaySpec.withFullSizeAppLayout(enabled: Boolean): TaskDisplaySpec =
    if (enabled && appDensityDpi != densityDpi) {
        copy(
            appDensityDpi = densityDpi,
            appDisplayWidth = scaledLogicalSize(width, densityDpi, appDensityDpi),
            appDisplayHeight = scaledLogicalSize(height, densityDpi, appDensityDpi),
        )
    } else if (enabled) {
        copy(appDensityDpi = densityDpi)
    } else {
        this
    }

private fun scaledLogicalSize(
    physicalPixels: Int,
    baseDensityDpi: Int,
    targetDensityDpi: Int,
): Int = ((physicalPixels.toLong() * baseDensityDpi + targetDensityDpi / 2) / targetDensityDpi)
    .toInt()
