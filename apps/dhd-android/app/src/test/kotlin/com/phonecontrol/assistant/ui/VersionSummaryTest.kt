package com.phonecontrol.assistant.ui

import com.phonecontrol.assistant.BuildConfig
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class VersionSummaryTest {
    private val strings = File(
        System.getProperty("dhd.moduleDir") ?: error("dhd.moduleDir is not set; run the tests through Gradle."),
        "src/main/res/values/strings.xml",
    )

    private fun string(name: String): String? {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == name }
            ?.textContent
    }

    @Test
    fun `settings shows the build version and the running sdk instead of fixed text`() {
        val format = string("settings_version_summary")!!.replace("\\u2022", "•")

        assertEquals("0.1.0 • Android SDK 36", String.format(format, BuildConfig.VERSION_NAME, 36))
        assertEquals(null, string("settings_0_1_0_android_sdk_35"))
    }
}
