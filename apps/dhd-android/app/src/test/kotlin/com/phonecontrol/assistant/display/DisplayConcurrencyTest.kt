package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayConcurrencyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pool = Executors.newFixedThreadPool(THREADS)

    @After
    fun tearDown() {
        pool.shutdownNow()
        scope.cancel()
    }

    private fun concurrently(count: Int, action: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(count)
        repeat(count) { index ->
            pool.execute {
                start.await()
                try {
                    action(index)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        check(done.await(10, TimeUnit.SECONDS))
    }

    private fun record(index: Int, expiresAt: Long) = TaskDisplayRecord(
        sessionKey = "run-$index",
        taskId = "task-$index",
        packageName = "com.example.shop",
        displayId = 7,
        width = 720,
        height = 1560,
        densityDpi = 420,
        rotation = 0,
        status = TaskDisplayStatus.COMPLETED,
        createdAtEpochMs = 0L,
        expiresAtEpochMs = expiresAt,
    )

    @Test
    fun `retention jobs scheduled from many threads can all be cancelled`() {
        val expired = AtomicInteger()
        val scheduler = RetentionScheduler(scope, System::currentTimeMillis) { _, _ -> expired.incrementAndGet() }
        val expiresAt = System.currentTimeMillis() + 300L

        concurrently(KEYS) { index -> scheduler.schedule(record(index, expiresAt)) }
        concurrently(KEYS) { index -> scheduler.cancel("run-$index") }
        Thread.sleep(600L)

        assertEquals(0, expired.get())
    }

    @Test
    fun `preview states published from many threads are all kept`() {
        val registry = LivePreviewRegistry(scope, Mutex(), activeSessionKey = { null })

        concurrently(KEYS) { index -> registry.publish("run-$index", TaskPreviewState.Error("run-$index", "failed")) }

        assertEquals(KEYS, registry.previewStates.value.size)
    }

    @Test
    fun `owner bindings ensured while other runs bind and unbind are all kept`() {
        val bindings = RunBindingRegistry()

        concurrently(KEYS) { index ->
            if (index % 2 == 0) {
                bindings.ensureOwnerBinding("owner-$index")
            } else {
                bindings.bind("run-$index", "other-$index")
                bindings.unbind("other-$index")
            }
        }

        val missing = (0 until KEYS step 2).filterNot { bindings.isBound("owner-$it", "owner-$it") }
        assertEquals(emptyList<Int>(), missing)
    }

    private companion object {
        const val THREADS = 8
        const val KEYS = 2_000
    }
}
