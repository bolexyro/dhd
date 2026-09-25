package com.phonecontrol.assistant.testing

import com.phonecontrol.assistant.core.Base64Codec
import com.phonecontrol.assistant.core.Clock
import com.phonecontrol.assistant.core.DeviceInfo
import java.util.Base64
import java.util.UUID

class MutableClock(
    var wall: Long = 1_750_000_000_000L,
    var elapsed: Long = 5_000_000L,
) : Clock {
    override fun wallMillis(): Long = wall
    override fun elapsedMillis(): Long = elapsed

    fun advance(millis: Long) {
        wall += millis
        elapsed += millis
    }
}

object JvmBase64Codec : Base64Codec {
    override fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

data class FixedDeviceInfo(
    override val manufacturer: String = "samsung",
    override val model: String = "SM-S911B",
) : DeviceInfo

class SequentialUuids(private val prefix: Long = 0x0dd0L) : () -> UUID {
    private var next = 1L

    override fun invoke(): UUID = UUID(prefix, next++)
}
