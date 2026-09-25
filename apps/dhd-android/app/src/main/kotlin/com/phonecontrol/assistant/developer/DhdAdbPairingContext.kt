package com.phonecontrol.assistant.developer

import android.util.Log

private const val TAG = "DhdAdbPairing"

internal class DhdAdbPairingContext private constructor(private val nativePtr: Long) {
    val message: ByteArray
        get() = nativeMessage(nativePtr)

    fun initCipher(theirMessage: ByteArray): Boolean =
        nativeInitCipher(nativePtr, theirMessage)

    fun encrypt(input: ByteArray): ByteArray? = nativeEncrypt(nativePtr, input)

    fun decrypt(input: ByteArray): ByteArray? = nativeDecrypt(nativePtr, input)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMessage(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMessage: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, input: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, input: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        init {
            try {
                Log.i(TAG, "Loading native ADB pairing library")
                System.loadLibrary("dhd_adb")
                Log.i(TAG, "Native ADB pairing library loaded")
            } catch (error: Throwable) {
                Log.e(TAG, "Native ADB pairing library could not load", error)
                throw error
            }
        }

        fun create(password: ByteArray): DhdAdbPairingContext? {
            val pointer = nativeConstructor(true, password)
            if (pointer == 0L) Log.e(TAG, "Native ADB pairing context creation returned null")
            return pointer.takeIf { it != 0L }?.let(::DhdAdbPairingContext)
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}
