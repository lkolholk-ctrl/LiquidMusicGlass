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

    // Расшифрованный ключ кешируется после первого успеха: nativeGetKey каждый
    // раз гоняет полную анти-тампер проверку APK (чтение+хеш), а первый вызов ещё
    // и грузит .so. Это дорого и БЕЗ КЕША висело на главном потоке на старте
    // (ANR). Кешируем — второй и последующие вызовы мгновенные.
    @Volatile
    private var cachedKey: String? = null

    /**
     * Kotlin-facing API key accessor. Кешируется после первого непустого ответа.
     */
    fun getApiKey(context: android.content.Context): String {
        cachedKey?.let { return it }
        val key = nativeGetKey(context)
        if (key.isNotBlank()) cachedKey = key
        return key
    }

    /**
     * Kotlin-facing base URL accessor.
     */
    fun getBaseUrl(): String = nativeGetBaseUrl()
}
