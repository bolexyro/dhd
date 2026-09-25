package com.phonecontrol.assistant.ui.theme

enum class ThemeMode(val storageValue: String, val label: String) {
    SYSTEM("system", "System (Default)"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: DARK
    }
}
