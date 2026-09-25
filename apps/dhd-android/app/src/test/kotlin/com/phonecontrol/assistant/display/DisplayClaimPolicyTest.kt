package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayClaimPolicyTest {
    private val now = 10_000L
    private val retention = 1_000L

    private fun record(
        status: TaskDisplayStatus,
        terminalAt: Long? = null,
        expiresAt: Long? = null,
        error: String? = null,
    ) = TaskDisplayRecord(
        sessionKey = "owner-1",
        taskId = "owner-1@7",
        packageName = "com.example.shop",
        displayId = 7,
        width = 720,
        height = 1_560,
        densityDpi = 420,
        rotation = 0,
        status = status,
        createdAtEpochMs = 1_000L,
        terminalAtEpochMs = terminalAt,
        expiresAtEpochMs = expiresAt,
        error = error,
    )

    @Test
    fun `a record without its native session is retained, expired or marked unavailable`() {
        fun next(record: TaskDisplayRecord) = DisplayClaimPolicy.withoutNativeSession(record, "gone", now, retention)

        assertEquals(TaskDisplayStatus.ENDED, next(record(TaskDisplayStatus.ENDED)).status)
        assertEquals(TaskDisplayStatus.EXPIRED, next(record(TaskDisplayStatus.EXPIRED)).status)
        assertEquals(
            record(TaskDisplayStatus.STOPPED, expiresAt = now, error = "The retained display expired.")
                .copy(status = TaskDisplayStatus.EXPIRED),
            next(record(TaskDisplayStatus.STOPPED, expiresAt = now)),
        )
        assertEquals(now + retention, next(record(TaskDisplayStatus.COMPLETED)).expiresAtEpochMs)
        assertEquals(TaskDisplayStatus.EXPIRED, next(record(TaskDisplayStatus.UNAVAILABLE, expiresAt = now)).status)
        assertEquals(
            record(TaskDisplayStatus.RUNNING, terminalAt = now, expiresAt = now + retention, error = "gone")
                .copy(status = TaskDisplayStatus.UNAVAILABLE),
            next(record(TaskDisplayStatus.RUNNING)),
        )
    }

    @Test
    fun `only retained or unavailable records refresh their expiry`() {
        assertNull(DisplayClaimPolicy.refreshedExpiry(record(TaskDisplayStatus.RUNNING), now, retention))
        assertNull(DisplayClaimPolicy.refreshedExpiry(record(TaskDisplayStatus.ENDED), now, retention))
        val refreshed = DisplayClaimPolicy.refreshedExpiry(record(TaskDisplayStatus.STOPPED, terminalAt = 5L), now, retention)
        assertEquals(5L, refreshed?.terminalAtEpochMs)
        assertEquals(now + retention, refreshed?.expiresAtEpochMs)
    }

    @Test
    fun `claims are rejected for gone, expired and foreign running displays`() {
        fun code(record: TaskDisplayRecord, bound: Boolean = false, cancelled: Boolean = false) =
            DisplayClaimPolicy.claimRejection(record, "owner-1", "run-2", bound, cancelled, now)?.code

        assertEquals("DISPLAY_ENDED", code(record(TaskDisplayStatus.ENDED)))
        assertEquals("DISPLAY_EXPIRED", code(record(TaskDisplayStatus.EXPIRED)))
        assertEquals("DISPLAY_EXPIRED", code(record(TaskDisplayStatus.STOPPED, expiresAt = now)))
        assertEquals("DISPLAY_IN_USE", code(record(TaskDisplayStatus.RUNNING)))
        assertNull(code(record(TaskDisplayStatus.RUNNING), bound = true))
        assertNull(code(record(TaskDisplayStatus.RUNNING), cancelled = true))
        assertNull(code(record(TaskDisplayStatus.STOPPED, expiresAt = now + 1)))
    }

    @Test
    fun `expiry fires only for the scheduled deadline of a retained record`() {
        val retained = record(TaskDisplayStatus.STOPPED, terminalAt = 1L, expiresAt = now)
        assertTrue(DisplayClaimPolicy.isExpiryDue(retained, now, now))
        assertFalse(DisplayClaimPolicy.isExpiryDue(retained, now + 1, now + 1))
        assertFalse(DisplayClaimPolicy.isExpiryDue(retained, now, now - 1))
        assertFalse(DisplayClaimPolicy.isExpiryDue(record(TaskDisplayStatus.RUNNING, expiresAt = now), now, now))
        assertTrue(DisplayClaimPolicy.canCloseExpired(retained, now, now))
        assertFalse(DisplayClaimPolicy.canCloseExpired(null, now, now))
        assertFalse(DisplayClaimPolicy.canCloseExpired(retained.copy(status = TaskDisplayStatus.ENDED), now, now))
    }
}
