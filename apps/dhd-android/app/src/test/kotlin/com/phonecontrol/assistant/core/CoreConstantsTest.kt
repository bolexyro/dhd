package com.phonecontrol.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `tool names stay byte identical to the companion contract`() {
        assertEquals("dhd_list_allowed_apps", ToolNames.LIST_ALLOWED_APPS)
        assertEquals("dhd_get_foreground_app", ToolNames.FOREGROUND_APP)
        assertEquals("dhd_execute", ToolNames.EXECUTE)
        assertEquals("dhd_execute_sequence", ToolNames.EXECUTE_SEQUENCE)
        assertEquals("dhd_open_app", ToolNames.OPEN_APP)
        assertEquals("dhd_browse_app", ToolNames.BROWSE_APP)
        assertEquals("dhd_set_app_display_layout", ToolNames.SET_APP_DISPLAY_LAYOUT)
        assertEquals("dhd_observe", ToolNames.OBSERVE)
        assertEquals("dhd_request_attention", ToolNames.REQUEST_ATTENTION)
        assertEquals("dhd_close_display", ToolNames.CLOSE_DISPLAY)
        assertEquals("close_display", ToolNames.LEGACY_CLOSE_DISPLAY)
    }

    @Test
    fun `both close display spellings are recognised ignoring case`() {
        assertTrue(ToolNames.isCloseDisplay("DHD_close_display"))
        assertTrue(ToolNames.isCloseDisplay("Close_Display"))
        assertFalse(ToolNames.isCloseDisplay("dhd_observe"))
        assertFalse(ToolNames.isCloseDisplay(null))
    }
}
