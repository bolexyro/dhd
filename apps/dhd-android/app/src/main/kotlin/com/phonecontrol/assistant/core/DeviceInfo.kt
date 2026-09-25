package com.phonecontrol.assistant.core

import android.os.Build

interface DeviceInfo {
    val manufacturer: String
    val model: String
}

object BuildDeviceInfo : DeviceInfo {
    override val manufacturer: String
        get() = Build.MANUFACTURER
    override val model: String
        get() = Build.MODEL
}
