package com.phonecontrol.assistant

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class WindowThemeResourcesTest {
    private val resDir = File(
        System.getProperty("dhd.moduleDir") ?: error("dhd.moduleDir is not set; run the tests through Gradle."),
        "src/main/res",
    )

    private fun themeItems(qualifier: String): Map<String, String> {
        val file = File(resDir, "$qualifier/styles.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val items = document.getElementsByTagName("item")
        return (0 until items.length).associate { index ->
            val item = items.item(index) as Element
            item.getAttribute("name") to item.textContent.trim()
        }
    }

    @Test
    fun `a light system cold start uses a light window`() {
        val light = themeItems("values")
        assertEquals("#FFFFFF", light["android:windowBackground"])
        assertEquals("#FFFFFF", light["android:statusBarColor"])
        assertEquals("#FFFFFF", light["android:navigationBarColor"])
        assertEquals("true", light["android:windowLightStatusBar"])
        assertEquals("true", light["android:windowLightNavigationBar"])
    }

    @Test
    fun `a dark system cold start keeps the black window`() {
        val dark = themeItems("values-night")
        assertEquals("#000000", dark["android:windowBackground"])
        assertEquals("#000000", dark["android:statusBarColor"])
        assertEquals("#000000", dark["android:navigationBarColor"])
        assertEquals("false", dark["android:windowLightStatusBar"])
        assertEquals("false", dark["android:windowLightNavigationBar"])
    }
}
