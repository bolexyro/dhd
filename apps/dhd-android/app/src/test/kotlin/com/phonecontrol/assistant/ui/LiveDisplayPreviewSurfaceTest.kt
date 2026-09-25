package com.phonecontrol.assistant.ui

import com.phonecontrol.assistant.ui.displays.surface.PreviewSurfaceLease
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDisplayPreviewSurfaceTest {
    @Test
    fun `surface lease releases resources once when destruction callbacks race`() {
        val releaseCount = AtomicInteger()
        val lease = PreviewSurfaceLease { releaseCount.incrementAndGet() }
        assertEquals(0, releaseCount.get())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                start.await()
                lease.release()
            }
            val second = executor.submit {
                start.await()
                lease.release()
            }
            start.countDown()
            first.get()
            second.get()
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, releaseCount.get())
    }
}
