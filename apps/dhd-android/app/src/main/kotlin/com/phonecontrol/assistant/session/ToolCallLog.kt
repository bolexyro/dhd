package com.phonecontrol.assistant.session

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ToolCallLog {
    private val _toolCalls = MutableStateFlow<List<DhdToolCall>>(emptyList())

    val toolCalls: StateFlow<List<DhdToolCall>> = _toolCalls.asStateFlow()

    fun begin(sessionId: String, toolName: String, purpose: String?): DhdToolCall {
        val safeToolName = toolName.trim().take(MAX_TOOL_NAME_CHARS)
            .ifBlank { "dhd_tool" }
        val safePurpose = (purpose ?: defaultDhdToolPurpose(safeToolName))
            .trim()
            .take(MAX_PURPOSE_CHARS)
            .ifBlank { defaultDhdToolPurpose(safeToolName) }
        val now = System.currentTimeMillis()
        val call = DhdToolCall(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            toolName = safeToolName,
            purpose = safePurpose,
            status = DhdToolCallStatus.RUNNING,
            startedAtEpochMs = now,
        )
        _toolCalls.value = (_toolCalls.value + call).takeLast(MAX_TOOL_CALLS)
        return call
    }

    fun finish(callId: String?, status: DhdToolCallStatus): Boolean {
        if (callId.isNullOrBlank()) return false
        val index = _toolCalls.value.indexOfFirst { it.id == callId }
        if (index < 0) return false
        val call = _toolCalls.value[index]
        if (call.status != DhdToolCallStatus.RUNNING) return false
        val updated = call.copy(
            status = status,
            endedAtEpochMs = System.currentTimeMillis(),
        )
        _toolCalls.value = _toolCalls.value.toMutableList().also { it[index] = updated }
        return true
    }

    fun clear() {
        _toolCalls.value = emptyList()
    }

    private companion object {
        const val MAX_TOOL_CALLS = 12
        const val MAX_TOOL_NAME_CHARS = 80
        const val MAX_PURPOSE_CHARS = 240
    }
}
