package com.liquidmusicglass.security

object LcmNative {
    init {
        System.loadLibrary("lcm_native")
    }

    /** Возвращает URL твоего VPS сервера (расшифрован hardware key-ом) */
    external fun getYandexResolverUrl(): String

    /** Возвращает ICM API base URL (расшифрован hardware key-ом) */
    external fun getIcmBaseUrl(): String
}
