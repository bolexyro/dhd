package com.phonecontrol.assistant.display

import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DisplayRecordStore(
    private val conversationStore: ConversationStore?,
) {
    val lock = Any()
    private val _records = MutableStateFlow<List<TaskDisplayRecord>>(emptyList())

    val records: StateFlow<List<TaskDisplayRecord>> = _records.asStateFlow()

    fun publish(record: TaskDisplayRecord) {
        synchronized(lock) {
            val next = _records.value
                .filterNot { it.sessionKey == record.sessionKey } + record
            _records.value = sorted(next)
            conversationStore?.upsertTaskDisplay(record)
        }
    }

    fun find(sessionKey: String): TaskDisplayRecord? = synchronized(lock) {
        _records.value.firstOrNull { it.sessionKey == sessionKey }
    }

    fun findByDisplayId(displayId: Int): TaskDisplayRecord? = synchronized(lock) {
        _records.value.firstOrNull { it.displayId == displayId }
    }

    fun findByDisplayRef(displayRef: String): TaskDisplayRecord? = synchronized(lock) {
        _records.value.firstOrNull { it.displayRef == displayRef }
    }

    fun sessionKeys(): List<String> = synchronized(lock) {
        _records.value.map { it.sessionKey }
    }

    fun restore(nowEpochMs: Long) {
        val persisted = conversationStore?.listTaskDisplays().orEmpty()
        if (persisted.isEmpty()) return
        val restored = persisted.map { record -> DisplayClaimPolicy.restored(record, nowEpochMs) }
        synchronized(lock) {
            _records.value = sorted(restored)
        }
        restored.zip(persisted).forEach { (next, previous) ->
            if (next != previous) conversationStore?.upsertTaskDisplay(next)
        }
    }

    fun deleteAll() {
        synchronized(lock) {
            _records.value = emptyList()
        }
        conversationStore?.deleteAllTaskDisplays()
    }

    fun endAll(nowEpochMs: Long) {
        val updatedRecords = synchronized(lock) {
            _records.value.map { record ->
                if (!DisplayClaimPolicy.isGone(record)) DisplayClaimPolicy.ended(record, nowEpochMs) else record
            }.also {
                _records.value = sorted(it)
            }
        }
        updatedRecords.forEach { record ->
            conversationStore?.upsertTaskDisplay(record)
        }
    }

    private fun sorted(records: List<TaskDisplayRecord>): List<TaskDisplayRecord> =
        records.sortedWith(compareByDescending<TaskDisplayRecord> { it.createdAtEpochMs }.thenBy { it.sessionKey })
}
