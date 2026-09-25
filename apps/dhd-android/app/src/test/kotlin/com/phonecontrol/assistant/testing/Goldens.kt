package com.phonecontrol.assistant.testing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

object Goldens {
    private val contractsDir: File by lazy {
        val path = System.getProperty("dhd.contractsDir")
            ?: error("dhd.contractsDir is not set; run the tests through Gradle.")
        File(path).also { dir ->
            assertTrue("Contracts directory is missing: ${dir.absolutePath}", dir.isDirectory)
        }
    }

    private val updating: Boolean
        get() = System.getProperty("dhd.updateGoldens") == "true"

    fun file(relativePath: String): File = File(contractsDir, relativePath)

    fun assertMatches(relativePath: String, actual: String) {
        val file = file(relativePath)
        if (updating) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        assertTrue(
            "Missing golden ${file.absolutePath}. Run ./gradlew :app:testDebugUnitTest -PupdateGoldens=true to create it.",
            file.isFile,
        )
        assertEquals("Golden $relativePath changed", file.readText(), actual)
    }
}
