package com.phonecontrol.assistant.ui.settings

import com.phonecontrol.assistant.apps.InstalledUserApp
import org.junit.Assert.assertEquals
import org.junit.Test

class AppAccessFilterTest {
    private val maps = InstalledUserApp(packageName = "com.google.android.apps.maps", label = "Maps")
    private val shop = InstalledUserApp(packageName = "com.example.shop", label = "Shop")

    @Test
    fun `blank query keeps every app in order`() {
        assertEquals(listOf(maps, shop), filterAppsByQuery(listOf(maps, shop), "  "))
    }

    @Test
    fun `query matches label or package name ignoring case`() {
        assertEquals(listOf(maps), filterAppsByQuery(listOf(maps, shop), "mAP"))
        assertEquals(listOf(shop), filterAppsByQuery(listOf(maps, shop), "EXAMPLE"))
        assertEquals(emptyList<InstalledUserApp>(), filterAppsByQuery(listOf(maps, shop), "camera"))
    }
}
