package com.phonecontrol.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreConstantsTest {
    @Test
    fun `coordinator copy matched by the ui keeps its exact text`() {
        assertEquals("Needs your attention", CoordinatorCopy.NEEDS_ATTENTION)
        assertEquals("View instructions", CoordinatorCopy.VIEW_INSTRUCTIONS)
        assertEquals("Preparing request", CoordinatorCopy.PREPARING_REQUEST)
        assertEquals("Codex is planning", CoordinatorCopy.CODEX_PLANNING)
        assertEquals("DHD is planning", CoordinatorCopy.DHD_PLANNING)
        assertEquals("Waiting for desktop Codex bridge", CoordinatorCopy.WAITING_FOR_COMPANION)
    }
}
