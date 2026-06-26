package com.liquidmusicglass.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Лёгкий монитор активной сети: различает безлимит (Wi-Fi/Ethernet) и сотовую
 * (metered). Нужен для гейтинга «сотовые данные / стриминг / загрузки» и эконома
 * трафика. Значения читаются СИНХРОННО (volatile) — поведенческий код вызывает
 * [isUnmetered]/[isMetered] на любом потоке без блокировки.
 *
 * Регистрирует один defaultNetworkCallback и обновляет capabilities при сменах
 * сети (Wi-Fi↔сотовая, VPN). До первого колбэка считаем сеть безлимитной
 * (не блокируем пользователя на старте).
 */
object NetworkMonitor {

    @Volatile private var unmetered: Boolean = true
    @Volatile private var validated: Boolean = true

    private var cm: ConnectivityManager? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // NOT_METERED — безлимит (обычно Wi-Fi/Ethernet). На сотовой обычно metered.
            unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        override fun onLost(network: Network) {
            // Сеть пропала — не знаем тип следующей; не блокируем (вернёмся к дефолту).
            unmetered = true
            validated = false
        }
    }

    fun init(context: Context) {
        if (registered) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm = manager
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
            // Подхватываем текущее состояние сразу (колбэк может задержаться).
            refreshNow()
        } catch (_: Exception) {
            // На отказ регистрации остаёмся в безопасном дефолте (unmetered=true).
        }
    }

    /** Принудительно перечитать capabilities активной сети. */
    fun refreshNow() {
        val manager = cm ?: return
        try {
            val active = manager.activeNetwork
            val caps = active?.let { manager.getNetworkCapabilities(it) }
            if (caps != null) {
                unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        } catch (_: Exception) {}
    }

    /** Текущая сеть безлимитна (Wi-Fi/Ethernet) — сотовый гейтинг неприменим. */
    fun isUnmetered(): Boolean = unmetered

    /** Текущая сеть лимитирована (сотовая) — применяем сотовые ограничения. */
    fun isMetered(): Boolean = !unmetered
}
