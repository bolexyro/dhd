package com.phonecontrol.assistant.core

object ToolNames {
    const val LIST_ALLOWED_APPS = "dhd_list_allowed_apps"
    const val FOREGROUND_APP = "dhd_get_foreground_app"
    const val EXECUTE = "dhd_execute"
    const val EXECUTE_SEQUENCE = "dhd_execute_sequence"
    const val OPEN_APP = "dhd_open_app"
    const val BROWSE_APP = "dhd_browse_app"
    const val SET_APP_DISPLAY_LAYOUT = "dhd_set_app_display_layout"
    const val OBSERVE = "dhd_observe"
    const val REQUEST_ATTENTION = "dhd_request_attention"
    const val CLOSE_DISPLAY = "dhd_close_display"
    const val LEGACY_CLOSE_DISPLAY = "close_display"

    fun isCloseDisplay(toolName: String?): Boolean =
        toolName.equals(CLOSE_DISPLAY, ignoreCase = true) ||
            toolName.equals(LEGACY_CLOSE_DISPLAY, ignoreCase = true)
}
