package com.phonecontrol.assistant.core

import android.util.Base64

interface Base64Codec {
    fun encode(bytes: ByteArray): String
}

object AndroidBase64Codec : Base64Codec {
    override fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
