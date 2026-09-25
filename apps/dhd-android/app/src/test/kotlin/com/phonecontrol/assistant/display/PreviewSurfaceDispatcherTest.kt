package com.phonecontrol.assistant.display

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewSurfaceDispatcherTest {
    @Test
    fun `destroy waits for pending session lookup and decoder teardown`() = runTest {
        val lookup = CompletableDeferred<String>()
        val stopped = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val surface = Any()
        val dispatcher = PreviewSurfaceDispatcher<String, Any>(
            backgroundScope,
            attach = { owner, _ -> events += "attach $owner" },
            detach = { owner, _ -> events += "detach $owner"; stopped.await() },
            onFailure = { throw AssertionError(it) },
        )
        dispatcher.attach(surface) { lookup.await() }
        dispatcher.detach(surface) { events += "release" }
        runCurrent()
        assertEquals(emptyList<String>(), events)
        lookup.complete("original")
        runCurrent()
        assertEquals(listOf("attach original", "detach original"), events)
        stopped.complete(Unit)
        runCurrent()
        assertEquals(listOf("attach original", "detach original", "release"), events)
    }

    @Test
    fun `fullscreen handoff and late destruction keep original surface owners`() = runTest {
        val events = mutableListOf<String>()
        val inline = Any()
        val fullscreen = Any()
        val dispatcher = PreviewSurfaceDispatcher<String, Any>(
            backgroundScope,
            attach = { owner, _ -> events += "attach $owner" },
            detach = { owner, _ -> events += "detach $owner" },
            onFailure = { throw AssertionError(it) },
        )
        dispatcher.attach(inline) { "old-run" }
        dispatcher.attach(fullscreen) { "new-run" }
        dispatcher.detach(inline) { events += "release inline" }
        dispatcher.detach(fullscreen) { events += "release fullscreen" }
        runCurrent()
        assertEquals(listOf("attach old-run", "attach new-run", "detach old-run",
            "release inline", "detach new-run", "release fullscreen"), events)
    }

    @Test
    fun `failed partial attach is cleaned up and does not stop later viewers`() = runTest {
        val events = mutableListOf<String>()
        val surface = Any()
        val dispatcher = PreviewSurfaceDispatcher<String, Any>(
            backgroundScope,
            attach = { owner, _ ->
                events += "attach $owner"
                if (owner == "failed") error("codec failed")
            },
            detach = { owner, _ -> events += "detach $owner" },
            onFailure = { events += "failure" },
        )
        dispatcher.attach(surface) { "failed" }
        dispatcher.detach(surface) { events += "release" }
        dispatcher.attach(Any()) { "next" }
        runCurrent()
        assertEquals(listOf("attach failed", "failure", "detach failed", "release", "attach next"), events)
    }

    @Test
    fun `missing session still releases surface without detaching another session`() = runTest {
        val events = mutableListOf<String>()
        val surface = Any()
        val dispatcher = PreviewSurfaceDispatcher<String, Any>(
            backgroundScope,
            attach = { _, _ -> events += "attach" },
            detach = { _, _ -> events += "detach" },
            onFailure = { throw AssertionError(it) },
        )
        dispatcher.attach(surface) { null }
        dispatcher.detach(surface) { events += "release" }
        runCurrent()
        assertEquals(listOf("release"), events)
    }
}
