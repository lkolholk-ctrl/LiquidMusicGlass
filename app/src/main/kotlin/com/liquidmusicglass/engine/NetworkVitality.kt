package com.liquidmusicglass.engine

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Центр «оживления» сети. Транспорт-агностичен: не знает и не спрашивает,
 * какой транспорт под ногами — реагирует только на два ФАКТА:
 *  1) система сообщила, что дефолтная сеть сменилась;
 *  2) подряд сыплются сетевые фейлы без ответа сервера.
 *
 * Зачем: OkHttp держит keep-alive сокеты. После пересоздания маршрута
 * (смена Wi-Fi↔моб., перезагрузка роутера, реконнект туннеля — причём
 * иногда С ТЕМ ЖЕ Network id, когда системный колбэк молчит) эти сокеты
 * мертвы, но пул об этом не знает: запросы уходят в трупы и «сеть пропала
 * до перезапуска приложения». Лечение — принудительный evict всех пулов.
 */
object NetworkVitality {

    private const val FAIL_STREAK_THRESHOLD = 3
    private const val REVIVE_COOLDOWN_MS = 15_000L

    private val revivers = CopyOnWriteArrayList<Pair<String, () -> Unit>>()
    private val failStreak = AtomicInteger(0)
    private val lastReviveAt = AtomicLong(0L)

    /** Зарегистрировать «оживитель» (обычно evict пула конкретного клиента). */
    fun registerReviver(name: String, revive: () -> Unit) {
        revivers += name to revive
    }

    /** Система сообщила о смене дефолтной сети — оживляем без условий. */
    fun onDefaultNetworkChanged() = reviveAll("network-changed")

    /** Успешный сетевой обмен (любой HTTP-ответ) — маршрут жив. */
    fun onRequestSuccess() {
        failStreak.set(0)
    }

    /**
     * Сетевой фейл БЕЗ ответа сервера (таймаут/коннект/reset). Ответ с любым
     * HTTP-кодом фейлом не считается — сокеты в порядке. N подряд при
     * формально живой сети = маршрут протух тихо (реконнект туннеля без
     * смены Network id) → оживляем сами, не дожидаясь системы.
     */
    fun onRequestNetworkError() {
        if (failStreak.incrementAndGet() < FAIL_STREAK_THRESHOLD) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReviveAt.get() < REVIVE_COOLDOWN_MS) return
        lastReviveAt.set(now)
        failStreak.set(0)
        reviveAll("fail-streak")
    }

    private fun reviveAll(reason: String) {
        for ((_, revive) in revivers) {
            try {
                revive()
            } catch (_: Throwable) {
                // оживление не имеет права ронять приложение
            }
        }
        com.liquidmusicglass.debug.DebugLog.add("NET revive ($reason): ${revivers.size} pools")
    }
}
