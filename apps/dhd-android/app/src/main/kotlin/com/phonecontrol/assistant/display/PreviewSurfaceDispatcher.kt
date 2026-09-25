package com.phonecontrol.assistant.display

import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Orders UI callbacks before any suspendable session lookup or decoder work. */
internal class PreviewSurfaceDispatcher<S : Any, T : Any>(
    scope: CoroutineScope,
    private val attach: suspend (S, T) -> Unit,
    private val detach: suspend (S, T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val owners = IdentityHashMap<T, S>()
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    event()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onFailure(error)
                }
            }
        }
    }

    fun attach(surface: T, resolve: suspend () -> S?) {
        check(events.trySend {
            val session = resolve() ?: return@trySend
            // Record ownership before attaching: failed/partial attaches still
            // need cleanup, and a later UI recomposition may name another run.
            owners[surface] = session
            attach(session, surface)
        }.isSuccess)
    }

    fun detach(surface: T, release: () -> Unit) {
        check(events.trySend {
            try {
                owners.remove(surface)?.let { session -> detach(session, surface) }
            } finally {
                release()
            }
        }.isSuccess)
    }
}
