package com.phonecontrol.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TransportInputTest {
    private fun session(appWidth: Int? = null, appHeight: Int? = null): TaskDisplaySession {
        val geometry = TaskDisplayGeometry(width = 720, height = 1560, densityDpi = 420, rotation = 0)
        return TaskDisplaySession(
            sessionKey = "task-run",
            taskId = "task-run@7",
            displayId = 7,
            geometry = geometry,
            appDisplayWidth = appWidth ?: geometry.width,
            appDisplayHeight = appHeight ?: geometry.height,
        )
    }

    @Test
    fun `input commands are unchanged when the app canvas matches the display`() {
        val command = listOf("input", "tap", "100", "200")
        assertSame(command, scaleTaskInputCommand(session(), command))
    }

    @Test
    fun `non input commands are never scaled`() {
        val command = listOf("am", "start", "100", "200")
        assertSame(command, scaleTaskInputCommand(session(945, 2048), command))
    }

    @Test
    fun `tap and swipe coordinates are scaled into the logical canvas with rounding`() {
        val canvas = session(945, 2048)
        assertEquals(
            listOf("input", "tap", "131", "263"),
            scaleTaskInputCommand(canvas, listOf("input", "tap", "100", "200")),
        )
        assertEquals(
            listOf("input", "swipe", "0", "0", "944", "2047", "300"),
            scaleTaskInputCommand(canvas, listOf("input", "swipe", "0", "0", "719", "1559", "300")),
        )
        assertEquals(
            listOf("input", "tap", "944", "2047"),
            scaleTaskInputCommand(canvas, listOf("input", "tap", "5000", "9000")),
        )
    }

    @Test
    fun `other input verbs and short commands keep their arguments`() {
        val canvas = session(945, 2048)
        assertEquals(listOf("input", "text", "100"), scaleTaskInputCommand(canvas, listOf("input", "text", "100")))
        assertEquals(listOf("input", "tap", "100"), scaleTaskInputCommand(canvas, listOf("input", "tap", "100")))
        assertEquals(
            listOf("input", "swipe", "1", "2", "3"),
            scaleTaskInputCommand(canvas, listOf("input", "swipe", "1", "2", "3")),
        )
        assertEquals(listOf("input", "keyevent", "66"), scaleTaskInputCommand(canvas, listOf("input", "keyevent", "66")))
    }

    @Test
    fun `input text escapes spaces`() {
        assertEquals("milk%sand%seggs", encodeInputText("milk and eggs"))
        assertEquals("café", encodeInputText("café"))
        assertEquals("", encodeInputText(""))
    }

    @Test
    fun `input text rejects characters the shell cannot carry`() {
        assertEquals(
            "Android input text cannot safely encode '%' in this v0 transport.",
            assertThrows(IllegalArgumentException::class.java) { encodeInputText("100%") }.message,
        )
        listOf("line\nbreak", "tab\there", "bell\u0007", "delete\u007f").forEach { text ->
            assertEquals(
                "Android input text does not accept control characters.",
                assertThrows(IllegalArgumentException::class.java) { encodeInputText(text) }.message,
            )
        }
    }
}
