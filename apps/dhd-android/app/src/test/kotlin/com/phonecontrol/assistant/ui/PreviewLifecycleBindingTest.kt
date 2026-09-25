package com.phonecontrol.assistant.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.phonecontrol.assistant.ui.displays.surface.PreviewLifecycleBinding
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewLifecycleBindingTest {
    private class Host : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test
    fun `retained view reattaches when activity returns without a new surface event`() {
        val host = Host()
        val events = mutableListOf<String>()
        host.lifecycle.currentState = Lifecycle.State.RESUMED
        val binding = PreviewLifecycleBinding(host.lifecycle, { events += "attach" }, { events += "detach" })
        assertEquals(listOf("attach"), events)
        // Activity goes behind another app; overlay takes the decoder.
        host.lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(listOf("attach", "detach"), events)
        // Same view and same texture survive. Lifecycle alone must reattach.
        host.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(listOf("attach", "detach", "attach"), events)
        binding.close()
        host.lifecycle.currentState = Lifecycle.State.CREATED
        host.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(listOf("attach", "detach", "attach", "detach"), events)
    }

    @Test
    fun `background composition waits for start and pause alone preserves decoder`() {
        val host = Host()
        val events = mutableListOf<String>()
        host.lifecycle.currentState = Lifecycle.State.CREATED
        val binding = PreviewLifecycleBinding(host.lifecycle, { events += "attach" }, { events += "detach" })
        assertEquals(emptyList<String>(), events)
        host.lifecycle.currentState = Lifecycle.State.RESUMED
        host.lifecycle.currentState = Lifecycle.State.STARTED
        host.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(listOf("attach"), events)
        host.lifecycle.currentState = Lifecycle.State.DESTROYED
        binding.close()
        assertEquals(listOf("attach", "detach"), events)
    }
}
