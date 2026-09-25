package com.phonecontrol.assistant

import android.app.Application
import com.phonecontrol.assistant.app.AppContainer

class PhoneControlApplication : Application() {
    @Volatile
    lateinit var container: AppContainer
        private set

    val containerOrNull: AppContainer?
        get() = if (::container.isInitialized) container else null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
