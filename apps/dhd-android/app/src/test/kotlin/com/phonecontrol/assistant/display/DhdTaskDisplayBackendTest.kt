package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DhdTaskDisplayBackendTest {
    @Test
    fun `recognizes native missing-session errors including wrapped failures`() {
        assertTrue(isNativeDisplaySessionMissing(IOException("DHD display session is not active.")))
        assertTrue(
            isNativeDisplaySessionMissing(
                IllegalStateException(
                    "preview attach failed",
                    IOException("The virtual display session is not active."),
                ),
            ),
        )
    }

    @Test
    fun `does not classify unrelated preview failures as missing sessions`() {
        assertFalse(isNativeDisplaySessionMissing(IOException("The AVC stream timed out.")))
    }

    @Test
    fun `a stopped owner can be reclaimed before terminal retention is published`() {
        assertFalse(
            isTaskDisplayOwnedByAnotherRun(
                status = TaskDisplayStatus.RUNNING,
                alreadyBoundToRun = false,
                ownerKey = "stopped-owner",
                runSessionKey = "continuation-run",
                ownerCancelled = true,
            ),
        )
    }

    @Test
    fun `an active uncancelled display remains protected from another run`() {
        assertTrue(
            isTaskDisplayOwnedByAnotherRun(
                status = TaskDisplayStatus.RUNNING,
                alreadyBoundToRun = false,
                ownerKey = "active-owner",
                runSessionKey = "other-run",
                ownerCancelled = false,
            ),
        )
    }

    @Test
    fun `explicitly ended display wins over its terminal expiry timestamp`() {
        val ended = TaskDisplayRecord(
            sessionKey = "ended-run",
            taskId = "ended-task",
            packageName = "com.example.app",
            displayId = 7,
            width = 720,
            height = 1_560,
            densityDpi = 420,
            rotation = 0,
            status = TaskDisplayStatus.ENDED,
            createdAtEpochMs = 1L,
            terminalAtEpochMs = 10L,
            expiresAtEpochMs = 10L,
        )

        val result = taskDisplayUnavailableForRecord(ended, nowEpochMs = 20L)

        assertEquals("DISPLAY_ENDED", result.code)
    }

    @Test
    fun `expired display remains expired when it is not explicitly ended`() {
        val expired = TaskDisplayRecord(
            sessionKey = "expired-run",
            taskId = "expired-task",
            packageName = "com.example.app",
            displayId = 8,
            width = 720,
            height = 1_560,
            densityDpi = 420,
            rotation = 0,
            status = TaskDisplayStatus.EXPIRED,
            createdAtEpochMs = 1L,
            terminalAtEpochMs = 10L,
            expiresAtEpochMs = 10L,
        )

        val result = taskDisplayUnavailableForRecord(expired, nowEpochMs = 20L)

        assertEquals("DISPLAY_EXPIRED", result.code)
    }

    @Test
    fun `recognizes the target app task on the requested display`() {
        val dump = """
            Display #0 (activities from top to bottom):
              mResumedActivity: ActivityRecord{a com.example.other/.Main}
            Display #7 (activities from top to bottom):
              Task{42 #42 type=standard A=com.example.target}
                ActivityRecord{b com.example.target/.MainActivity}
        """.trimIndent()

        assertEquals(
            true,
            parseDisplayTaskPresence(dump, displayId = 7, packageName = "com.example.target"),
        )
    }

    @Test
    fun `reports a listed display without the target app as missing`() {
        val dump = """
            Display #7 (activities from top to bottom):
              Task{42 #42 type=standard A=com.android.launcher}
                ActivityRecord{b com.android.launcher/.Launcher}
        """.trimIndent()

        assertEquals(
            false,
            parseDisplayTaskPresence(dump, displayId = 7, packageName = "com.example.target"),
        )
    }

    @Test
    fun `treats an unrecognizable display section as unknown`() {
        assertNull(
            parseDisplayTaskPresence(
                "Display #2 (activities from top to bottom):",
                displayId = 7,
                packageName = "com.example.target",
            ),
        )
    }

    @Test
    fun `does not match a package prefix from another app`() {
        val dump = """
            Display #7 (activities from top to bottom):
              ActivityRecord{b com.example.target.child/.MainActivity}
        """.trimIndent()

        assertEquals(
            false,
            parseDisplayTaskPresence(dump, displayId = 7, packageName = "com.example.target"),
        )
    }
}
