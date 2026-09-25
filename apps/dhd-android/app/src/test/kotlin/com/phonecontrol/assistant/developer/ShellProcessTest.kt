package com.phonecontrol.assistant.developer

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProcessTest {
    @Before
    fun requirePosixShell() {
        assumeTrue("ShellProcess tests require /bin/sh", File("/bin/sh").canExecute())
    }

    private fun sh(script: String, timeoutMs: Long = 5_000L, maxStderrBytes: Int = 1024): ShellProcess.Result =
        ShellProcess.run(listOf("/bin/sh", "-c", script), timeoutMs, maxStderrBytes, "test-out", "test-err")

    @Test
    fun `collects the exit code, raw stdout and trimmed stderr`() {
        val result = sh("printf 'out\\n'; printf '  err  \\n' >&2; exit 3")

        assertEquals(3, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.overflowed)
        assertArrayEquals("out\n".toByteArray(), result.stdout)
        assertEquals("err", result.stderr)
    }

    @Test
    fun `a command that outlives its timeout is killed and reported as unavailable`() {
        val result = sh("sleep 5", timeoutMs = 100L)

        assertTrue(result.timedOut)
        assertEquals(DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE, result.exitCode)
    }

    @Test
    fun `output beyond the stderr cap is drained and flagged`() {
        val result = sh("head -c 4096 /dev/zero >&2", maxStderrBytes = 16)

        assertEquals(0, result.exitCode)
        assertTrue(result.overflowed)
        assertEquals("", result.stderr)
    }
}
