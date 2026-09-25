package com.phonecontrol.assistant.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperModeStatusTest {
    @Test
    fun `connecting remains non-actionable while recovery is in progress`() {
        val status = DeveloperModeStatus(DeveloperConnectionState.CONNECTING, paired = true)

        assertFalse(status.requiresUserAction)
        assertFalse(status.requiresMaintenanceRestart)
        assertTrue(status.executionUnavailableMessage.contains("still connecting"))
    }

    @Test
    fun `wireless debugging unavailable asks the user to recover access`() {
        val status = DeveloperModeStatus(
            state = DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
            paired = true,
        )

        assertTrue(status.requiresUserAction)
        assertTrue(status.requiresMaintenanceRestart)
        assertTrue(status.phoneAccessInterrupted)
        assertTrue(status.recoveryTitle.contains("needed"))
    }

    @Test
    fun `saved pairing distinguishes restart from first-time pairing`() {
        val paired = DeveloperModeStatus(
            state = DeveloperConnectionState.PAIRING_REQUIRED,
            paired = true,
        )
        val unpaired = DeveloperModeStatus(
            state = DeveloperConnectionState.PAIRING_REQUIRED,
            paired = false,
        )

        assertTrue(paired.requiresMaintenanceRestart)
        assertFalse(unpaired.requiresMaintenanceRestart)
        assertTrue(paired.phoneAccessInterrupted)
        assertTrue(unpaired.needsInitialConnection)
        assertEquals("Phone access needed", unpaired.recoveryTitle)
        assertTrue(unpaired.recoveryDetail.contains("one-time connection"))
    }
}
