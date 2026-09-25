package com.phonecontrol.assistant.core

import android.os.SystemClock

interface Clock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
}

object SystemClockClock : Clock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}
