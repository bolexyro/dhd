package com.phonecontrol.assistant.testing

import org.json.JSONArray
import org.json.JSONObject

object CanonicalJson {
    fun render(value: Any?): String = buildString { write(value, 0) }.plus("\n")

    private fun StringBuilder.write(value: Any?, depth: Int) {
        when (value) {
            null, JSONObject.NULL -> append("null")
            is JSONObject -> writeObject(value, depth)
            is JSONArray -> writeArray(value, depth)
            is String -> writeString(value)
            is Boolean, is Number -> append(value.toString())
            else -> error("Unsupported JSON value ${value::class.java.name}")
        }
    }

    private fun StringBuilder.writeObject(json: JSONObject, depth: Int) {
        val keys = json.keys().asSequence().toList().sorted()
        if (keys.isEmpty()) {
            append("{}")
            return
        }
        append("{\n")
        keys.forEachIndexed { index, key ->
            indent(depth + 1)
            writeString(key)
            append(": ")
            write(json.get(key), depth + 1)
            if (index < keys.lastIndex) append(",")
            append("\n")
        }
        indent(depth)
        append("}")
    }

    private fun StringBuilder.writeArray(array: JSONArray, depth: Int) {
        if (array.length() == 0) {
            append("[]")
            return
        }
        append("[\n")
        for (index in 0 until array.length()) {
            indent(depth + 1)
            write(array.get(index), depth + 1)
            if (index < array.length() - 1) append(",")
            append("\n")
        }
        indent(depth)
        append("]")
    }

    private fun StringBuilder.writeString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun StringBuilder.indent(depth: Int) {
        repeat(depth) { append("  ") }
    }
}
