package com.phonecontrol.assistant.developer

import com.phonecontrol.assistant.execution.PhoneProcessResult
import java.io.DataInputStream
import java.io.DataOutputStream

internal object DhdMaintenanceWire {
    fun writeRequest(output: DataOutputStream, token: String, command: List<String>, binaryOutput: Boolean) {
        DhdMaintenanceProtocol.writeRequest(output, token, command, binaryOutput)
    }

    fun readResult(input: DataInputStream): PhoneProcessResult {
        val response = DhdMaintenanceProtocol.readResponse(input)
        return PhoneProcessResult(
            exitCode = response.exitCode.takeUnless { it == DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE },
            stdout = response.stdout,
            stderr = String(response.stderr, Charsets.UTF_8).trim(),
            timedOut = response.timedOut,
        )
    }
}
