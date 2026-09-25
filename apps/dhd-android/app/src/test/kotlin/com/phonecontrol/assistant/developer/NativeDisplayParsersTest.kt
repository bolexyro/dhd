package com.phonecontrol.assistant.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeDisplayParsersTest {
    private val taskUniqueId = "virtual:com.android.shell,2000,dhd-task-abc,0"

    private val getDisplaysSingleLine = """
        Displays:
          Display id 0: DisplayInfo{"Built-in Screen", displayId 0, uniqueId "local:4619827259835644672", app 1080 x 2340}
          Display id 7: DisplayInfo{"dhd-task-abc", displayId 7, uniqueId "$taskUniqueId", app 720 x 1560}
          Display id 12: DisplayInfo{"other", displayId 12, uniqueId "virtual:com.other,1000,other,0", app 720 x 1560}
    """.trimIndent()

    private val getDisplaysMultiLine = """
        Display id 7:
          mDisplayId=7
          uniqueId="$taskUniqueId"
        Display id 8:
          mDisplayId=8
          uniqueId=virtual:com.android.shell,2000,dhd-task-def,0
    """.trimIndent()

    @Test
    fun `logical unique id is read from the requested display and unquoted ids stop at a comma`() {
        assertEquals(taskUniqueId, SurfaceFlingerIds.findLogicalUniqueId(getDisplaysSingleLine, 7))
        assertEquals("local:4619827259835644672", SurfaceFlingerIds.findLogicalUniqueId(getDisplaysSingleLine, 0))
        assertEquals(taskUniqueId, SurfaceFlingerIds.findLogicalUniqueId(getDisplaysMultiLine, 7))
        assertEquals(
            "virtual:com.android.shell",
            SurfaceFlingerIds.findLogicalUniqueId(getDisplaysMultiLine, 8),
        )
        assertNull(SurfaceFlingerIds.findLogicalUniqueId(getDisplaysSingleLine, 9))
        assertNull(SurfaceFlingerIds.findLogicalUniqueId(null, 7))
        assertNull(SurfaceFlingerIds.findLogicalUniqueId("Display id 7: DisplayInfo{no id here}", 7))
    }

    @Test
    fun `surface flinger id is the display section that mentions the unique id`() {
        val dump = """
            Display 4619827259835644672 (HWC display 0): port=0 displayName="Built-in Screen"
            Display 11529215046068469761 (virtual): displayName="dhd-task-abc"
               uniqueId="$taskUniqueId" layerStack=7
            Virtual Display 11529215046068469762 (virtual): displayName="other"
        """.trimIndent()
        assertEquals("11529215046068469761", SurfaceFlingerIds.findSurfaceFlingerId(dump, 7, taskUniqueId))
        assertNull(SurfaceFlingerIds.findSurfaceFlingerId(dump, 7, "virtual:missing"))
        assertNull(SurfaceFlingerIds.findSurfaceFlingerId(dump, 7, null))
        assertNull(SurfaceFlingerIds.findSurfaceFlingerId(dump, 7, ""))
        assertNull(SurfaceFlingerIds.findSurfaceFlingerId(null, 7, taskUniqueId))
        assertNull(SurfaceFlingerIds.findSurfaceFlingerId("uniqueId=\"$taskUniqueId\"", 7, taskUniqueId))
    }

    @Test
    fun `surface flinger id is refused when two displays mention the unique id`() {
        val dump = """
            Display 101 (virtual): uniqueId="$taskUniqueId"
            Display 102 (virtual): uniqueId="$taskUniqueId"
        """.trimIndent()
        assertNull(SurfaceFlingerIds.findSurfaceFlingerId(dump, 7, taskUniqueId))
        val repeated = """
            Display 101 (virtual): uniqueId="$taskUniqueId"
               mirror of uniqueId="$taskUniqueId"
        """.trimIndent()
        assertEquals("101", SurfaceFlingerIds.findSurfaceFlingerId(repeated, 7, taskUniqueId))
    }

    @Test
    fun `virtual display id is found by its unique display name`() {
        val singleLine = """
            Display 200 (physical) displayName="Built-in Screen"
            Display 201 Virtual display displayName="dhd-task-abc"
            Display 202 DisplayDevice name="other"
        """.trimIndent()
        assertEquals("201", SurfaceFlingerIds.findUniqueSurfaceFlingerVirtualDisplayId(singleLine, "DHD-TASK-ABC"))

        val headerInline = "Display 301 (virtual) displayName=\"dhd-task-abc\""
        assertEquals("301", SurfaceFlingerIds.findUniqueSurfaceFlingerVirtualDisplayId(headerInline, "dhd-task-abc"))

        val multiLine = """
            Display 401 (virtual)
               name="dhd-task-abc"
            Display 402 (virtual)
               name="other"
        """.trimIndent()
        assertEquals("401", SurfaceFlingerIds.findUniqueSurfaceFlingerVirtualDisplayId(multiLine, "dhd-task-abc"))
    }

    @Test
    fun `virtual display name lookup refuses ambiguous or missing names`() {
        val ambiguous = """
            Display 501 (virtual) displayName="dhd-task-abc"
            Display 502 (virtual) displayName="dhd-task-abc"
        """.trimIndent()
        assertNull(SurfaceFlingerIds.findUniqueSurfaceFlingerVirtualDisplayId(ambiguous, "dhd-task-abc"))
        val nameAfterNextHeader = """
            Display 601 (virtual)
            Display 602 (physical)
               name="dhd-task-abc"
        """.trimIndent()
        assertNull(SurfaceFlingerIds.findUniqueSurfaceFlingerVirtualDisplayId(nameAfterNextHeader, "dhd-task-abc"))
        assertNull(SurfaceFlingerIds.findUniqueSurfaceFlingerVirtualDisplayId(ambiguous, ""))
        assertNull(SurfaceFlingerIds.findUniqueSurfaceFlingerVirtualDisplayId(ambiguous, null))
        assertNull(SurfaceFlingerIds.findUniqueSurfaceFlingerVirtualDisplayId(null, "dhd-task-abc"))
    }

    @Test
    fun `first stream preserves startup queue while reconnect resets it`() {
        // The native encoder may have only one static keyframe available when
        // the first TextureView surface attaches. That frame must survive the
        // initial handshake; subsequent decoder handoffs require a fresh IDR.
        assertEquals(false, StreamServer.shouldResetStreamQueue(false))
        assertEquals(true, StreamServer.shouldResetStreamQueue(true))
    }

    @Test
    fun `display commands validate their shape before touching the platform`() {
        val service = DhdNativeDisplayService()
        fun stderr(vararg command: String): String =
            service.execute(command.toList(), false).also {
                assertEquals(DhdMaintenanceProtocol.EXIT_CODE_COMMAND_FAILED, it.exitCode)
            }.stderr
        assertEquals("DHD display command is invalid.", service.execute(null, false).stderr)
        assertEquals("DHD display command is invalid.", stderr("dhd-display"))
        assertEquals("DHD display command is invalid.", stderr("other", "create"))
        assertEquals("DHD display operation is unsupported: resize", stderr("dhd-display", "resize"))
    }

    @Test
    fun `display create accepts nine ten or twelve arguments`() {
        val service = DhdNativeDisplayService()
        val arityMessage = "dhd-display create requires sessionKey, packageName, width, height, densityDpi, frameRate, bitRate, appDensityDpi, appDisplayWidth, appDisplayHeight."
        val sessionKeyMessage = "DHD display session key is invalid."
        val expected = mapOf(
            8 to arityMessage,
            9 to sessionKeyMessage,
            10 to sessionKeyMessage,
            11 to arityMessage,
            12 to sessionKeyMessage,
            13 to arityMessage,
        )
        expected.forEach { (size, message) ->
            val command = listOf("dhd-display", "create", "!invalid") + List(size - 3) { "1" }
            assertEquals("size $size", message, service.execute(command, false).stderr)
        }
    }

    @Test
    fun `display create validates identity and ranges`() {
        val service = DhdNativeDisplayService()
        fun create(vararg values: String): String =
            service.execute(listOf("dhd-display", "create") + values.toList(), false).stderr
        assertEquals(
            "DHD display package name is invalid.",
            create("task-1", "shop", "720", "1560", "420", "30", "2000000"),
        )
        assertEquals(
            "DHD display operation failed: width is out of range.",
            create("task-1", "com.example.shop", "100", "1560", "420", "30", "2000000"),
        )
        assertEquals(
            "DHD display operation failed: bitRate is not an integer.",
            create("task-1", "com.example.shop", "720", "1560", "420", "30", "fast"),
        )
        assertEquals(
            "DHD display operation failed: appDensityDpi is out of range.",
            create("task-1", "com.example.shop", "720", "1560", "420", "30", "2000000", "100"),
        )
        assertEquals(
            "DHD display operation failed: appDisplayWidth is out of range.",
            create("task-1", "com.example.shop", "720", "1560", "420", "30", "2000000", "100", "5000", "1000"),
        )
    }
}
