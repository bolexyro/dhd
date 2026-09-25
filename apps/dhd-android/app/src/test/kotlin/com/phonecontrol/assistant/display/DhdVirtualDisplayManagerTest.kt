package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.execution.PhoneProcessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DhdVirtualDisplayManagerTest {
    private class CancellableDaemon : DisplayCommandExecutor {
        val commands = mutableListOf<List<String>>()
        val createStarted = CompletableDeferred<Unit>()
        var closeGate: CompletableDeferred<Unit>? = null
        val completedCloses = mutableListOf<String>()

        override suspend fun execute(command: List<String>, binaryOutput: Boolean): PhoneProcessResult {
            currentCoroutineContext().ensureActive()
            commands += command
            return when (command[1]) {
                DhdVirtualDisplayProtocol.LIST -> ok("""{"type":"sessions","sessions":[]}""")
                DhdVirtualDisplayProtocol.CREATE -> {
                    createStarted.complete(Unit)
                    awaitCancellation()
                }
                DhdVirtualDisplayProtocol.CLOSE -> {
                    closeGate?.await()
                    completedCloses += command[2]
                    ok("{}")
                }
                else -> ok("{}")
            }
        }

        fun closes(): List<String> = commands.filter { it[1] == DhdVirtualDisplayProtocol.CLOSE }.map { it[2] }

        private fun ok(stdout: String) = PhoneProcessResult(0, stdout.toByteArray(), "")
    }

    @Test
    fun `a create cancelled by stop still closes the display by key`() = runTest {
        val daemon = CancellableDaemon()
        val manager = DhdVirtualDisplayManager(daemon)
        val create = launch { manager.create("run-1", "com.example.shop") }
        daemon.createStarted.await()

        manager.cancel("run-1")
        create.cancel()
        create.join()

        assertEquals(listOf("run-1"), daemon.closes())
    }

    @Test
    fun `close finishes the native close when its caller is cancelled midway`() = runTest {
        val daemon = CancellableDaemon().apply { closeGate = CompletableDeferred() }
        val manager = DhdVirtualDisplayManager(daemon)
        val close = launch { manager.close("run-1") }
        runCurrent()

        close.cancel()
        daemon.closeGate!!.complete(Unit)
        close.join()

        assertEquals(listOf("run-1"), daemon.completedCloses)
    }
}
