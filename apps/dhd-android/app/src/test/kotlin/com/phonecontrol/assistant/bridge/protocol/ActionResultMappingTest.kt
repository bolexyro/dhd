package com.phonecontrol.assistant.bridge.protocol

import com.phonecontrol.assistant.domain.StaleObservationDiagnostics
import com.phonecontrol.assistant.execution.RejectionCode
import com.phonecontrol.assistant.execution.TransportResult
import com.phonecontrol.assistant.session.ActionExecutionResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionResultMappingTest {
    private val stale = StaleObservationDiagnostics(approvedObservationId = "obs-1", currentObservationId = null, reasons = emptyList())
    private val succeeded = ActionExecutionResult.TransportFinished(TransportResult.Succeeded("Tapped", byteArrayOf(7)))
    private val rejected = ActionExecutionResult.TransportFinished(
        TransportResult.Rejected(RejectionCode.STALE_OBSERVATION, "Stale", stale),
    )
    private val unsupported = ActionExecutionResult.TransportFinished(TransportResult.Unsupported("Nope"))
    private val policy = ActionExecutionResult.PolicyRejected("Blocked", stale, code = "SECURE_SCREEN_REQUIRES_USER")
    private val notRunning = ActionExecutionResult.SessionNotRunning
    private val all = listOf(succeeded, rejected, unsupported, policy, notRunning)

    @Test
    fun `messages come from the transport or the rejection`() {
        assertEquals(
            listOf("Tapped", "Stale", "Nope", "Blocked", "The phone session is no longer running."),
            all.map { it.resultMessage() },
        )
    }

    @Test
    fun `execute action reports the specific policy code`() {
        assertEquals(
            listOf(null, "STALE_OBSERVATION", "UNSUPPORTED_ACTION", "SECURE_SCREEN_REQUIRES_USER", "SESSION_NOT_RUNNING"),
            all.map { it.failureCode() },
        )
    }

    @Test
    fun `sequence steps report the same specific policy code as execute action`() {
        assertEquals(
            listOf("ACTION_FAILED", "STALE_OBSERVATION", "UNSUPPORTED_ACTION", "SECURE_SCREEN_REQUIRES_USER", "SESSION_NOT_RUNNING"),
            all.map { it.sequenceStepFailureCode() },
        )
    }

    @Test
    fun `only transport success carries a before screenshot and stale details come from rejections`() {
        assertEquals(listOf(true, false, false, false, false), all.map { it.isSuccessful() })
        assertArrayEquals(byteArrayOf(7), succeeded.beforeScreenshotOrNull())
        assertNull(policy.beforeScreenshotOrNull())
        assertEquals(listOf(null, stale, null, stale, null), all.map { it.staleDetailsOrNull() })
    }
}
