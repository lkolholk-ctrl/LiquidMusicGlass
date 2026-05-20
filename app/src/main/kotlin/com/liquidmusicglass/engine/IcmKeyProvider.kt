package com.liquidmusicglass.engine

/**
 * Native key provider — API key is stored encrypted in a .so library
 * and decrypted at runtime via JNI. This prevents extraction from
 * APK decompilation / string analysis.
 */
object IcmKeyProvider {

    init {
        System.loadLibrary("icmkey")
    }

    /**
     * Returns the decrypted ICM API key from native code.
     * Empty string if tampering is detected.
     */
    external fun nativeGetKey(context: android.content.Context): String

    /**
     * Returns the decrypted base URL from native code.
     */
    external fun nativeGetBaseUrl(): String

    /**
     * Kotlin-facing API key accessor.
     */
    fun getApiKey(context: android.content.Context): String = nativeGetKey(context)

    /**
     * Kotlin-facing base URL accessor.
     */
    fun getBaseUrl(): String = nativeGetBaseUrl()
}
