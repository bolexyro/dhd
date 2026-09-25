package com.phonecontrol.assistant.bridge.protocol

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeErrorCodesTest {
    @Test
    fun `every wire error code is spelled exactly like its name`() {
        val codes = BridgeErrorCodes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to it.get(null) }

        assertEquals(27, codes.size)
        codes.forEach { (name, value) -> assertEquals(name, value) }
    }
}
