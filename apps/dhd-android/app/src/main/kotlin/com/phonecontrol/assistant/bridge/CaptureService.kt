package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.CAPTURE_ATTEMPTS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.CAPTURE_RETRY_DELAY_MS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_OBSERVATIONS
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.observation.ObservationCaptureResult
import com.phonecontrol.assistant.observation.PhoneObservationSource
import com.phonecontrol.assistant.session.SessionCoordinator
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.coroutines.delay

internal class CaptureService(
    private val coordinator: SessionCoordinator,
    private val observationProvider: PhoneObservationSource,
    private val taskDisplayRequiredProvider: () -> Boolean,
) {
    private val observations = Collections.synchronizedMap(
        object : LinkedHashMap<String, ObservationSnapshot>(MAX_OBSERVATIONS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ObservationSnapshot>?): Boolean =
                size > MAX_OBSERVATIONS
        },
    )

    fun lookup(observationId: String): ObservationSnapshot? = synchronized(observations) {
        observations[observationId]
    }

    fun remember(snapshot: ObservationSnapshot) {
        synchronized(observations) {
            observations[snapshot.id] = snapshot
        }
    }

    suspend fun captureWithRetry(
        expectedPackageName: String?,
        guardRegions: List<GuardRegion>,
        taskSessionKey: String? = coordinator.activeSessionId(),
        displayId: Int? = null,
        expectedDisplayRef: String? = null,
    ): ObservationCaptureResult {
        if (taskDisplayRequiredProvider() && taskSessionKey == null) {
            return ObservationCaptureResult.Failed(
                message = "No active task display is available; refusing to use the physical display.",
                code = BridgeErrorCodes.TASK_DISPLAY_UNAVAILABLE,
            )
        }
        if (!coordinator.awaitPhoneAccessForTool()) {
            return ObservationCaptureResult.Failed(
                message = "Phone access is no longer available; the observation was not captured.",
                code = BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE,
            )
        }
        var last: ObservationCaptureResult = ObservationCaptureResult.Failed("No capture attempted.")
        repeat(CAPTURE_ATTEMPTS) {
            last = observationProvider.capture(
                expectedPackageName = expectedPackageName,
                guardRegions = guardRegions,
                taskSessionKey = taskSessionKey,
                displayId = displayId,
                expectedDisplayRef = expectedDisplayRef,
            )
            if (last is ObservationCaptureResult.Succeeded) return last
            delay(CAPTURE_RETRY_DELAY_MS)
        }
        return last
    }

    private fun observationFailureCode(message: String): String =
        if (message.contains("Wireless Debugging", ignoreCase = true) ||
            message.contains("DHD could not execute", ignoreCase = true)
        ) {
            BridgeErrorCodes.DEVELOPER_MODE_UNAVAILABLE
        } else {
            BridgeErrorCodes.OBSERVATION_FAILED
        }
}
