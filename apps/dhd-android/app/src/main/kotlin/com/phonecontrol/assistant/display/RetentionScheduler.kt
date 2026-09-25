package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.execution.TaskDisplayRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class RetentionScheduler(
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val onExpired: suspend (sessionKey: String, expiresAtEpochMs: Long) -> Unit,
) {
    private val expiryJobs = mutableMapOf<String, Job>()

    fun schedule(record: TaskDisplayRecord) {
        val expiresAt = record.expiresAtEpochMs ?: return
        synchronized(expiryJobs) {
            expiryJobs.remove(record.sessionKey)?.cancel()
            expiryJobs[record.sessionKey] = scope.launch {
                val remaining = expiresAt - nowEpochMs()
                if (remaining > 0) delay(remaining)
                onExpired(record.sessionKey, expiresAt)
            }
        }
    }

    fun cancel(sessionKey: String) {
        synchronized(expiryJobs) {
            expiryJobs.remove(sessionKey)?.cancel()
        }
    }
}
