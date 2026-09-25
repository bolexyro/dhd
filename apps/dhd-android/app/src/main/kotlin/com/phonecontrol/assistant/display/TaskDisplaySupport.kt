package com.phonecontrol.assistant.display

internal const val TASK_DISPLAY_SDK_INT = 36

internal fun taskDisplayUnsupportedReason(sdkInt: Int): String? =
    if (sdkInt == TASK_DISPLAY_SDK_INT) {
        null
    } else {
        "DHD task displays currently require Android 16 (API $TASK_DISPLAY_SDK_INT). " +
            "This phone runs Android API $sdkInt."
    }
