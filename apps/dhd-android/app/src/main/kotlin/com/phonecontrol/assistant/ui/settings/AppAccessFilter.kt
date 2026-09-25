package com.phonecontrol.assistant.ui.settings

import com.phonecontrol.assistant.apps.InstalledUserApp

internal fun filterAppsByQuery(apps: List<InstalledUserApp>, query: String): List<InstalledUserApp> =
    if (query.isBlank()) apps
    else apps.filter {
        it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }
