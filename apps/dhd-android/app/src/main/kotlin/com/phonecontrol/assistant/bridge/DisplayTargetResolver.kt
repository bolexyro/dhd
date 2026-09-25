package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.core.Clock
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.session.SessionCoordinator
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal class DisplayTargetResolver(
    private val taskDisplayBackend: TaskDisplayBackend?,
    private val coordinator: SessionCoordinator,
    private val taskDisplayRequiredProvider: () -> Boolean,
    private val clock: Clock,
    private val platform: BridgePlatform,
) {
    /**
     * Return the same actionable inventory as dhd_list_displays. Keeping this
     * in one path means a model can use a displayRef from a recovery response
     * without first making another list call.
     */
    suspend fun currentDisplayJson(
        backend: TaskDisplayBackend,
    ): List<JSONObject> {
        // Joining the registry's reconciliation job here ensures a freshly
        // started app does not report stale persisted records before native
        // sessions have been adopted or marked unavailable.
        backend.activeDisplaySessions()
        val now = clock.wallMillis()
        return backend.displayRecords.value
            .asSequence()
            .filter { record ->
                record.status != TaskDisplayStatus.ENDED &&
                    record.status != TaskDisplayStatus.EXPIRED &&
                    (record.expiresAtEpochMs == null || record.expiresAtEpochMs > now)
            }
            .map { record -> displayJson(record, now) }
            .toList()
    }

    suspend fun displayInventoryForRecovery(
        backend: TaskDisplayBackend,
    ): List<JSONObject> = try {
        currentDisplayJson(backend)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        // The limit response is still useful when reconciliation is briefly
        // unavailable; return an empty, well-formed inventory instead of
        // replacing the actionable allocator error with a registry error.
        emptyList()
    }

    private fun displayJson(
        record: TaskDisplayRecord,
        now: Long = clock.wallMillis(),
    ): JSONObject = JSONObject()
        .put("displayRef", record.displayRef)
        .put("appLabel", platform.appLabel(record.packageName))
        .put("packageName", record.packageName)
        .put("status", record.status.name.lowercase())
        .put("width", record.width)
        .put("height", record.height)
        .put("densityDpi", record.densityDpi)
        .put("createdAtEpochMs", record.createdAtEpochMs)
        .put("terminalAtEpochMs", record.terminalAtEpochMs ?: JSONObject.NULL)
        .put("expiresAtEpochMs", record.expiresAtEpochMs ?: JSONObject.NULL)
        .put(
            "remainingRetentionMs",
            record.expiresAtEpochMs?.let { expiresAt -> (expiresAt - now).coerceAtLeast(0L) }
                ?: JSONObject.NULL,
        )
        .put("lastPurpose", record.lastPurpose)
        .put("error", record.error ?: JSONObject.NULL)

    suspend fun resolve(
        displayRef: String?,
        fallbackDisplayId: Int? = null,
        fallbackDisplayRef: String? = null,
        claimForRun: Boolean = true,
    ): TaskDisplayResolution {
        val backend = taskDisplayBackend
            ?: return TaskDisplayResolution.Unavailable(
                code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
                message = "The task display registry is unavailable; call dhd_open_app to create a task display.",
            )
        val runSessionKey = coordinator.activeSessionId()
        if (claimForRun && taskDisplayRequiredProvider() && runSessionKey == null) {
            return TaskDisplayResolution.Unavailable(
                code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
                message = "No active task display run is available. Call dhd_open_app from an active DHD task first.",
            )
        }
        val selectedDisplayRef = displayRef ?: fallbackDisplayRef
        return if (selectedDisplayRef != null) {
            backend.activeDisplaySessions()
            val record = backend.displayRecords.value.firstOrNull { it.displayRef == selectedDisplayRef }
                ?: return TaskDisplayResolution.Unavailable(
                    code = BridgeErrorCodes.DISPLAY_NOT_FOUND,
                    message = "No task display matches the supplied displayRef. Call dhd_list_displays to see the available displays.",
                )
            backend.resolveDisplay(
                displayId = record.displayId,
                claimForSessionKey = if (claimForRun) runSessionKey else null,
                expectedDisplayRef = selectedDisplayRef,
            )
        } else if (fallbackDisplayId != null) {
            backend.resolveDisplay(
                displayId = fallbackDisplayId,
                claimForSessionKey = if (claimForRun) runSessionKey else null,
            )
        } else {
            backend.resolveDefaultDisplay(
                claimForSessionKey = if (claimForRun) runSessionKey else null,
            )
        }
    }
}
