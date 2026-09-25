package com.phonecontrol.assistant.display

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

internal class RunBindingRegistry {
    private val lock = Any()
    /** Coordinator run key -> native display owner keys claimed by that run. */
    private val runBindings = mutableMapOf<String, MutableSet<String>>()
    private val cancelledKeys = ConcurrentHashMap.newKeySet<String>()
    private val operationLocks = ConcurrentHashMap<String, Mutex>()

    fun operationLock(key: String): Mutex = operationLocks.getOrPut(key) { Mutex() }

    fun isCancelled(key: String): Boolean = cancelledKeys.contains(key)

    fun markCancelled(key: String) {
        cancelledKeys.add(key)
    }

    fun clearCancelled(key: String) {
        cancelledKeys.remove(key)
    }

    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    fun reserveOwner(runKey: String, ownerKey: String): Boolean = synchronized(lock) {
        if (cancelledKeys.contains(runKey)) return false
        // Register before native creation so Stop can tombstone this exact
        // owner even if the daemon has not returned a display yet.
        runBindings.getOrPut(runKey) { linkedSetOf() }.add(ownerKey)
        true
    }

    fun claimState(runKey: String, ownerKey: String): Pair<Boolean, Boolean> = synchronized(lock) {
        (runBindings[runKey]?.contains(ownerKey) == true) to
            cancelledKeys.contains(ownerKey)
    }

    fun isBound(runKey: String, ownerKey: String): Boolean = synchronized(lock) {
        runBindings[runKey]?.contains(ownerKey) == true
    }

    fun cancelRun(runKey: String): List<String> = synchronized(lock) {
        ownerKeysForRunLocked(runKey).also { keys ->
            // Mark the tombstones while holding the same lock used by
            // claimDisplayForRun. A continuation either observes this
            // cancellation and reclaims the display, or observes that a
            // different run already claimed it and leaves it alone.
            keys.forEach { ownerKey -> cancelledKeys.add(ownerKey) }
        }
    }

    /** Keep one native owner associated with at most one logical run. */
    fun bind(runKey: String, ownerKey: String) {
        synchronized(lock) {
            runBindings.forEach { (boundRunKey, owners) ->
                if (boundRunKey != runKey) owners.remove(ownerKey)
            }
            runBindings.values.removeAll { it.isEmpty() }
            runBindings.getOrPut(runKey) { linkedSetOf() }.add(ownerKey)
        }
    }

    private fun ownerKeysForRunLocked(runKey: String): List<String> {
        val owners = runBindings[runKey].orEmpty()
        return if (owners.isNotEmpty()) {
            owners.toList()
        } else if (runBindings.any { (otherRunKey, boundOwners) ->
                otherRunKey != runKey && runKey in boundOwners
            }
        ) {
            // This key is the native owner of a display that has already been
            // claimed by another run. A late stop from the old run must not
            // cancel the new run's display.
            emptyList()
        } else {
            // Preserve the create-before-bind cancellation race: a run that
            // has not published a display still needs a tombstone by its own
            // key so a late native create is closed safely.
            listOf(runKey)
        }
    }

    fun ensureOwnerBinding(ownerKey: String) {
        if (runBindings.values.any { ownerKey in it }) return
        runBindings.getOrPut(ownerKey) { linkedSetOf() }.add(ownerKey)
    }

    fun isOwnerBoundToDifferentRun(ownerKey: String): Boolean = synchronized(lock) {
        runBindings.any { (runKey, owners) -> runKey != ownerKey && ownerKey in owners }
    }

    fun boundOwnerKeys(runKey: String): List<String> = synchronized(lock) {
        runBindings[runKey]?.toList().orEmpty()
    }

    fun unbind(ownerKey: String) {
        synchronized(lock) {
            runBindings.values.forEach { it.remove(ownerKey) }
            runBindings.values.removeAll { it.isEmpty() }
        }
    }

    fun clear() {
        synchronized(lock) {
            runBindings.clear()
        }
    }
}
