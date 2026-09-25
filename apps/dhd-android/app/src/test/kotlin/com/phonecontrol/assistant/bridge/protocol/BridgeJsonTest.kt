package com.phonecontrol.assistant.bridge.protocol

import com.phonecontrol.assistant.apps.InstalledUserApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class BridgeJsonTest {
    @Test
    fun `full access returns capability without enumerating apps`() {
        val response = buildAllowedAppsResponse(
            requestId = "request-1",
            fullAccess = true,
            allowedPackages = setOf("com.example.spotify", "com.example.mail"),
        )

        assertTrue(response.getBoolean("fullAccess"))
        assertEquals("full_access", response.getString("accessMode"))
        assertTrue(response.getBoolean("canListAllApps"))
        assertEquals(
            "Full Access is enabled. You can use any launchable app on the phone.",
            response.getString("message"),
        )
        assertFalse(response.has("allowedPackages"))
        assertFalse(response.has("count"))
    }

    @Test
    fun `restricted access returns the sorted explicit allowlist`() {
        val response = buildAllowedAppsResponse(
            requestId = "request-2",
            fullAccess = false,
            allowedPackages = setOf("com.example.zed", "com.example.alfred"),
        )

        assertFalse(response.getBoolean("fullAccess"))
        assertEquals("allowlist", response.getString("accessMode"))
        assertEquals(2, response.getInt("count"))
        assertEquals(
            "[\"com.example.alfred\",\"com.example.zed\"]",
            response.getJSONArray("allowedPackages").toString(),
        )
        assertFalse(response.has("message"))
    }

    @Test
    fun `full access returns all apps only when explicitly requested`() {
        val response = buildAllowedAppsResponse(
            requestId = "request-3",
            fullAccess = true,
            allowedPackages = emptySet(),
            includeAll = true,
            apps = listOf(
                InstalledUserApp(packageName = "com.example.zed", label = "Zed"),
                InstalledUserApp(packageName = "com.example.alfred", label = "Alfred"),
            ),
        )

        assertTrue(response.getBoolean("fullAccess"))
        assertTrue(response.getBoolean("canListAllApps"))
        assertEquals(2, response.getInt("count"))
        assertEquals(
            "[{\"appLabel\":\"Zed\",\"packageName\":\"com.example.zed\"},{\"appLabel\":\"Alfred\",\"packageName\":\"com.example.alfred\"}]",
            response.getJSONArray("apps").toString(),
        )
        assertFalse(response.has("icon"))
    }

    @Test
    fun `restricted access returns all requested launchable apps in the allowlist`() {
        val response = buildAllowedAppsResponse(
            requestId = "request-5",
            fullAccess = false,
            allowedPackages = setOf("com.example.alfred"),
            includeAll = true,
            apps = listOf(
                InstalledUserApp(packageName = "com.example.alfred", label = "Alfred"),
            ),
        )

        assertFalse(response.getBoolean("fullAccess"))
        assertEquals("allowlist", response.getString("accessMode"))
        assertFalse(response.getBoolean("canListAllApps"))
        assertEquals(1, response.getInt("count"))
        assertEquals(
            "[{\"appLabel\":\"Alfred\",\"packageName\":\"com.example.alfred\"}]",
            response.getJSONArray("apps").toString(),
        )
        assertEquals(
            "Restricted access is enabled. Returned all launchable apps in the allowlist.",
            response.getString("message"),
        )
        assertFalse(response.has("allowedPackages"))
    }

    @Test
    fun `browse response includes whether each app can be used`() {
        val response = buildBrowseAppsResponse(
            requestId = "request-4",
            query = "alfred",
            fullAccess = true,
            apps = listOf(InstalledUserApp(packageName = "com.example.alfred", label = "Alfred")),
            truncated = false,
        )

        assertEquals("browse_apps", response.getString("type"))
        assertEquals("alfred", response.getString("query"))
        assertEquals("Alfred", response.getJSONArray("apps").getJSONObject(0).getString("appLabel"))
        assertEquals("com.example.alfred", response.getJSONArray("apps").getJSONObject(0).getString("packageName"))
        assertTrue(response.getJSONArray("apps").getJSONObject(0).getBoolean("canUse"))
        assertFalse(response.has("icon"))
    }

    @Test
    fun `restricted browse returns matching apps with per-app access`() {
        val response = buildBrowseAppsResponse(
            requestId = "request-6",
            query = "app",
            fullAccess = false,
            allowedPackages = setOf("com.example.allowed"),
            apps = listOf(
                InstalledUserApp(packageName = "com.example.allowed", label = "Allowed App"),
                InstalledUserApp(packageName = "com.example.blocked", label = "Blocked App"),
            ),
            truncated = false,
        )

        val apps = response.getJSONArray("apps")
        assertEquals(2, apps.length())
        assertTrue(apps.getJSONObject(0).getBoolean("canUse"))
        assertFalse(apps.getJSONObject(1).getBoolean("canUse"))
    }

    @Test
    fun `display limit recovery includes displays and actionable reuse instructions`() {
        val response = addDisplayLimitRecovery(
            response = JSONObject()
                .put("type", "completed")
                .put("ok", false)
                .put("code", "DISPLAY_LIMIT_REACHED"),
            packageName = "com.example.shop",
            displays = listOf(
                JSONObject()
                    .put("displayRef", "dsp_0123456789abcd")
                    .put("status", "completed")
                    .put("lastPurpose", "Previous task"),
            ),
        )

        assertEquals(1, response.getInt("count"))
        assertEquals(
            "dsp_0123456789abcd",
            response.getJSONArray("displays").getJSONObject(0).getString("displayRef"),
        )
        assertFalse(response.getJSONArray("displays").getJSONObject(0).has("displayId"))
        val message = response.getString("message")
        assertTrue(message.contains("dhd_close_display"))
        assertTrue(message.contains("exact displayRef"))
        assertTrue(message.contains("pass its displayRef to dhd_open_app"))
    }

    @Test
    fun `app display layout response makes next-open semantics explicit`() {
        val response = buildAppDisplayLayoutResponse(
            requestId = "request-layout",
            packageName = "com.example.chowdeck",
            appLabel = "Chowdeck",
            layout = "full_size",
            changed = true,
        )

        assertEquals("app_display_layout_updated", response.getString("type"))
        assertEquals("full_size", response.getString("layout"))
        assertTrue(response.getBoolean("fullSizeLayoutEnabled"))
        assertTrue(response.getBoolean("appliesNextOpen"))
        assertTrue(response.getBoolean("requiresFreshDisplay"))
        assertTrue(response.getBoolean("currentDisplayUnchanged"))
        assertTrue(response.getBoolean("displayGeometryUnchanged"))
        assertTrue(response.getString("message").contains("Chowdeck"))
    }
}
