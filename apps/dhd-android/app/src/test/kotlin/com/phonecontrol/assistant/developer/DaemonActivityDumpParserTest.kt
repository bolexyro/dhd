package com.phonecontrol.assistant.developer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonActivityDumpParserTest {
    private val dump = """
        Display #0 (activities from top to bottom):
          mResumedActivity: ActivityRecord{a u0 com.example.home/.Home t1}
        Display #7 (activities from top to bottom):
          Task{42 #42 type=standard A=com.example.shop}
            ActivityRecord{b u0 com.example.shop/.CartActivity t42}
          topResumedActivity=ActivityRecord{c u0 com.example.shop/.CheckoutActivity t42}
    """.trimIndent()

    @Test
    fun `only a focus marker line on the requested display verifies the launch`() {
        assertTrue(DaemonActivityDumpParser.hasFocusedPackage(dump, 7, "com.example.shop"))
        assertFalse(DaemonActivityDumpParser.hasFocusedPackage(dump, 0, "com.example.shop"))
        assertFalse(DaemonActivityDumpParser.hasFocusedPackage(dump, 7, "com.example.home"))
        assertFalse(DaemonActivityDumpParser.hasFocusedPackage(dump, 8, "com.example.shop"))
    }

    @Test
    fun `a dotted child or parent package does not verify the launch`() {
        val child = "Display #7\n  mResumedActivity: ActivityRecord{a u0 com.example.shop.child/.Main t1}"
        val parent = "Display #7\n  mResumedActivity: ActivityRecord{a u0 net.com.example.shop/.Main t1}"

        assertFalse(DaemonActivityDumpParser.hasFocusedPackage(child, 7, "com.example.shop"))
        assertFalse(DaemonActivityDumpParser.hasFocusedPackage(parent, 7, "com.example.shop"))
        assertTrue(DaemonActivityDumpParser.hasFocusedPackage(child, 7, "com.example.shop.child"))
    }

    @Test
    fun `a package named at the end of the focus line verifies the launch`() {
        val taskOnly = "Display #7\n  mFocusedApp=com.example.shop"

        assertTrue(DaemonActivityDumpParser.hasFocusedPackage(taskOnly, 7, "com.example.shop"))
    }
}
