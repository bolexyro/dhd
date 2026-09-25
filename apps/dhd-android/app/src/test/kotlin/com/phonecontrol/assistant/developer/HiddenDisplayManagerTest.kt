package com.phonecontrol.assistant.developer

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class HiddenDisplayManagerTest {
    @Test
    fun `a display whose ime policy fails is released before the failure propagates`() {
        val released = mutableListOf<Int>()
        val failure = IOException("setDisplayImePolicy is unavailable")

        val thrown = assertThrows(IOException::class.java) {
            HiddenDisplayManager.createConfigured(
                { 7 },
                { throw failure },
                { released += 7 },
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf(7), released)
    }

    @Test
    fun `a configured display is kept`() {
        val configured = mutableListOf<Int>()
        val released = mutableListOf<Int>()

        val displayId = HiddenDisplayManager.createConfigured(
            { 9 },
            { configured += it },
            { released += 9 },
        )

        assertEquals(9, displayId)
        assertEquals(listOf(9), configured)
        assertEquals(emptyList<Int>(), released)
    }
}
