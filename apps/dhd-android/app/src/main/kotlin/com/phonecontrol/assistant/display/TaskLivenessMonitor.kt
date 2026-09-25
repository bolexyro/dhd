package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.execution.PhoneProcessRunner
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.observation.ActivityDumpParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class TaskLivenessMonitor(
    private val processRunner: PhoneProcessRunner,
    private val liveSessions: suspend () -> List<TaskDisplaySession>,
    private val currentPackageName: (TaskDisplaySession) -> String,
    private val onTaskMissing: suspend (TaskDisplaySession) -> Unit,
) {
    /**
     * Keep the display lifecycle tied to the app task that was launched on it.
     * The native display can outlive that task and continue producing an empty
     * surface, so a decoder error alone is not enough to identify this case.
     * Query failures are treated as unknown; three confirmed missing polls are
     * required before ending a display to tolerate activity transitions.
     */
    suspend fun run() {
        val missingPolls = mutableMapOf<String, Int>()
        while (true) {
            val candidates = liveSessions()
            if (candidates.isEmpty()) {
                missingPolls.clear()
                delay(TASK_LIVENESS_POLL_MS)
                continue
            }

            val result = try {
                processRunner.run(DUMPSYS_ACTIVITY_COMMAND)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (result?.exitCode == 0 && !result.timedOut) {
                val output = result.stdout.toString(Charsets.UTF_8)
                val currentKeys = candidates.mapTo(mutableSetOf()) { it.sessionKey }
                missingPolls.keys.retainAll(currentKeys)
                candidates.forEach { session ->
                    val currentPackage = currentPackageName(session)
                    when (ActivityDumpParser.displayTaskPresence(output, session.displayId, currentPackage)) {
                        true -> missingPolls.remove(session.sessionKey)
                        false -> {
                            val count = (missingPolls[session.sessionKey] ?: 0) + 1
                            if (count >= TASK_LIVENESS_MISSING_CONFIRMATIONS) {
                                missingPolls.remove(session.sessionKey)
                                onTaskMissing(session)
                            } else {
                                missingPolls[session.sessionKey] = count
                            }
                        }
                        null -> missingPolls.remove(session.sessionKey)
                    }
                }
            } else {
                missingPolls.clear()
            }
            delay(TASK_LIVENESS_POLL_MS)
        }
    }

    private companion object {
        const val TASK_LIVENESS_POLL_MS = 1_000L
        const val TASK_LIVENESS_MISSING_CONFIRMATIONS = 3
        val DUMPSYS_ACTIVITY_COMMAND = listOf("dumpsys", "activity", "activities")
    }
}
