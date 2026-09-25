package com.phonecontrol.assistant.bridge.transport

import java.io.BufferedWriter
import org.json.JSONObject

internal fun interface BridgeReply {
    fun write(json: JSONObject)
}

internal class NdjsonWriter(private val writer: BufferedWriter) : BridgeReply {
    override fun write(json: JSONObject) {
        writer.write(json.toString())
        writer.newLine()
        writer.flush()
    }
}
