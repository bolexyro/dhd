package com.phonecontrol.assistant

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class ManifestComponentsTest {
    private val moduleDir = File(
        System.getProperty("dhd.moduleDir") ?: error("dhd.moduleDir is not set; run the tests through Gradle."),
    )

    @Test
    fun `manifest component class names stay stable across upgrades`() {
        assertEquals(
            listOf(
                "application" to "com.phonecontrol.assistant.PhoneControlApplication",
                "activity" to "com.phonecontrol.assistant.MainActivity",
                "service" to "com.phonecontrol.assistant.session.AssistantForegroundService",
                "service" to "com.phonecontrol.assistant.developer.DhdAdbPairingService",
            ),
            declaredComponents(),
        )
    }

    @Test
    fun `every manifest component resolves to a class with that exact name`() {
        declaredComponents().forEach { (_, className) ->
            assertEquals(className, Class.forName(className, false, javaClass.classLoader).name)
        }
    }

    private fun declaredComponents(): List<Pair<String, String>> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File(moduleDir, "src/main/AndroidManifest.xml"))
        val application = document.getElementsByTagName("application").item(0) as Element
        val components = mutableListOf("application" to resolve(application.androidName()))
        val children = application.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.tagName in COMPONENT_TAGS) {
                components += child.tagName to resolve(child.androidName())
            }
        }
        return components
    }

    private fun Element.androidName(): String = getAttributeNS(ANDROID_NAMESPACE, "name")

    private fun resolve(name: String): String = if (name.startsWith(".")) NAMESPACE + name else name

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val NAMESPACE = "com.phonecontrol.assistant"
        val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")
    }
}
