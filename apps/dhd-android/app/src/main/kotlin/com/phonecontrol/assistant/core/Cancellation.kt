package com.phonecontrol.assistant.core

import kotlinx.coroutines.CancellationException

inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
