package com.phonecontrol.assistant.ui.navigation

internal fun supportedInitialRoute(initialRoute: String?): String? = when (initialRoute) {
    AppRoutes.SETTINGS,
    AppRoutes.PAIRING,
    AppRoutes.APPROVED_APPS,
    AppRoutes.COMPANION,
    -> initialRoute
    else -> null
}

object AppRoutes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val PAIRING = "pairing"
    const val APPROVED_APPS = "approved_apps"
    const val COMPANION = "companion"
}
