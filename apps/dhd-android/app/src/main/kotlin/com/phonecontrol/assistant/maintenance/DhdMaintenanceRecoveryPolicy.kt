package com.phonecontrol.assistant.maintenance

/**
 * Debounces local maintenance failures before DHD asks ADB to bootstrap the
 * daemon again. A single failed probe can be a transient socket or scheduling
 * blip; recovery is reserved for consecutive failures.
 */
internal class DhdMaintenanceRecoveryPolicy(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
) {
    private var consecutiveFailures = 0

    @Synchronized
    fun recordHealthy() {
        consecutiveFailures = 0
    }

    @Synchronized
    fun recordUnavailable(): Boolean {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(failureThreshold)
        return consecutiveFailures >= failureThreshold
    }

    @Synchronized
    fun reset() {
        consecutiveFailures = 0
    }

    init {
        require(failureThreshold > 0) { "The maintenance failure threshold must be positive." }
    }

    private companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 2
    }
}
