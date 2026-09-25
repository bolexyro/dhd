package com.phonecontrol.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouteTest {
    @Test
    fun `a deep link route opens once and not again after the activity is recreated`() {
        val first = OpenRouteConsumption(alreadyConsumed = false)
        assertEquals("companion", first.take("companion"))
        assertNull(first.take("companion"))

        val recreated = OpenRouteConsumption(alreadyConsumed = first.consumed)
        assertNull(recreated.take("companion"))
    }

    @Test
    fun `a new deep link after a consumed one opens its route`() {
        val consumption = OpenRouteConsumption(alreadyConsumed = true)
        consumption.expectNewRoute()

        assertEquals("pairing", OpenRouteConsumption(alreadyConsumed = consumption.consumed).take("pairing"))
    }

    @Test
    fun `the consumed flag is saved under its own state key`() {
        assertEquals("open_route_consumed", STATE_OPEN_ROUTE_CONSUMED)
    }
}
