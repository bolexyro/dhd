package com.phonecontrol.assistant.bridge.routing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PhoneActionLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
