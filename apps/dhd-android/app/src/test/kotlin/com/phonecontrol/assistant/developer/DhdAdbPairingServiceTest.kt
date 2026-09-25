package com.phonecontrol.assistant.developer

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Test

class DhdAdbPairingServiceTest {
    @Test
    fun `a submitted pairing code is never redelivered after process death`() {
        assertEquals(Service.START_NOT_STICKY, pairingServiceStartMode(DhdAdbPairingService.ACTION_SUBMIT_CODE))
    }

    @Test
    fun `starting or stopping the pairing flow keeps redelivery`() {
        assertEquals(Service.START_REDELIVER_INTENT, pairingServiceStartMode(DhdAdbPairingService.ACTION_START))
        assertEquals(Service.START_REDELIVER_INTENT, pairingServiceStartMode(DhdAdbPairingService.ACTION_STOP))
        assertEquals(Service.START_REDELIVER_INTENT, pairingServiceStartMode(null))
    }
}
