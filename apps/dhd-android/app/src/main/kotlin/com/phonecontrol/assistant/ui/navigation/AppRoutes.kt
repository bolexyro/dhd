package com.phonecontrol.assistant.ui.navigation

internal fun supportedInitialRoute(initialRoute: String?): String? = when (initialRoute) {
    AppRoutes.SETTINGS,
    AppRoutes.TASK_DISPLAYS,
    AppRoutes.PAIRING,
    AppRoutes.APPROVED_APPS,
    AppRoutes.COMPANION,
    AppRoutes.PERMISSION_SETUP,
    -> initialRoute
    else -> null
}

object AppRoutes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val TASK_DISPLAYS = "task_displays"
    const val PAIRING = "pairing"
    const val APPROVED_APPS = "approved_apps"
    const val COMPANION = "companion"
    const val PERMISSION_SETUP = "permission_setup"
}
