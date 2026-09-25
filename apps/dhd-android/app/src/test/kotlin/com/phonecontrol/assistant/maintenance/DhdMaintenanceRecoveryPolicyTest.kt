package com.phonecontrol.assistant.maintenance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DhdMaintenanceRecoveryPolicyTest {
    @Test
    fun `one failed probe does not restart the daemon`() {
        val policy = DhdMaintenanceRecoveryPolicy()

        assertFalse(policy.recordUnavailable())
    }

    @Test
    fun `two consecutive failed probes request recovery`() {
        val policy = DhdMaintenanceRecoveryPolicy()

        policy.recordUnavailable()

        assertTrue(policy.recordUnavailable())
    }

    @Test
    fun `healthy probe resets the failure streak`() {
        val policy = DhdMaintenanceRecoveryPolicy()

        policy.recordUnavailable()
        policy.recordHealthy()

        assertFalse(policy.recordUnavailable())
    }
}
