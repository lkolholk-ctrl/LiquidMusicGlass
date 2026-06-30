package com.liquidmusicglass.api.icm

import kotlinx.coroutines.delay

/**
 * Глобальный «шлюз» для ВСЕХ запросов к partner API (byicloud.online).
 *
 * Закрывает две корневые причины проблем сетевого слоя:
 *
 *  1) Залпы запросов → бан аккаунта/IP. Token-bucket держит средний темп
 *     [RATE_PER_SEC] и позволяет короткие всплески до [BURST]. Любой
 *     partner-запрос сперва берёт токен (throttle/throttleBlocking), поэтому
 *     даже если несколько подсистем одновременно решат «налить» запросов,
 *     общий темп остаётся безопасным.
 *
 *  2) После 429 / ip_temporarily_blocked приложение продолжало долбить и
 *     продлевало бан. Circuit-breaker: при 429 взводим [bannedUntilMs] (по
 *     Retry-After сервера) и до его истечения МГНОВЕННО фейлим запросы, вообще
 *     не выходя в сеть — это и есть «не хамить, пока банят».
 *
 * Один на всё приложение (object), потокобезопасен.
 */
object IcmRateGate {

    private const val RATE_PER_SEC = 5.0       // средний разрешённый темп, запр/сек
    private const val BURST = 8.0              // запас токенов на короткие всплески
    private const val DEFAULT_BAN_MS = 60_000L // если сервер не прислал Retry-After
    private const val MAX_BAN_MS = 3_600_000L  // не блокируем дольше часа

    // ─── Circuit breaker по СЕТЕВЫМ фейлам (host лежит/недоступен) ───
    // После N подряд connection/timeout-ошибок перестаём ходить в сеть на cooldown,
    // чтобы не плодить десятки висящих TLS-коннектов (они воруют CPU у RenderThread).
    private const val FAIL_THRESHOLD = 4
    private const val FAIL_COOLDOWN_MS = 15_000L

    private val lock = Any()
    private var tokens = BURST
    private var lastRefillNs = System.nanoTime()
    private var consecutiveFails = 0

    @Volatile private var bannedUntilMs = 0L

    /** Активен ли сейчас бан (circuit-breaker открыт). */
    fun isBanned(): Boolean = System.currentTimeMillis() < bannedUntilMs

    /** Сколько секунд осталось до конца бана (для сообщения/ретрая). */
    fun bannedForSeconds(): Int =
        ((bannedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L).toInt()

    /**
     * Взвести бан после 429 / rate_limited / ip_temporarily_blocked.
     * Берём максимум из текущего и нового — чтобы параллельные 429 не укорачивали паузу.
     */
    fun tripBan(retryAfterSec: Int?) {
        val ms = (retryAfterSec?.toLong()?.times(1000L)) ?: DEFAULT_BAN_MS
        val until = System.currentTimeMillis() + ms.coerceIn(1000L, MAX_BAN_MS)
        synchronized(lock) {
            if (until > bannedUntilMs) bannedUntilMs = until
        }
    }

    /** Сбросить бан (например, по кнопке/после восстановления сети). */
    fun reset() {
        synchronized(lock) { bannedUntilMs = 0L; consecutiveFails = 0 }
    }

    /** Успешный сетевой ответ получен (любой HTTP-код) → хост жив, сбрасываем счётчик. */
    fun recordSuccess() {
        synchronized(lock) { consecutiveFails = 0 }
    }

    /**
     * Сетевой ФЕЙЛ без ответа (timeout/connect/TLS). После [FAIL_THRESHOLD] подряд
     * открываем circuit-breaker на [FAIL_COOLDOWN_MS]: запросы мгновенно фейлятся,
     * новые коннекты не плодятся — даём CPU рендеру.
     */
    fun recordFailure() {
        synchronized(lock) {
            consecutiveFails++
            if (consecutiveFails >= FAIL_THRESHOLD) {
                val until = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                if (until > bannedUntilMs) bannedUntilMs = until
                consecutiveFails = 0
            }
        }
    }

    /** Возвращает 0, если токен выдан немедленно, иначе — сколько мс ждать. */
    private fun nextWaitMs(): Long = synchronized(lock) {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillNs) / 1_000_000_000.0
        lastRefillNs = now
        tokens = (tokens + elapsedSec * RATE_PER_SEC).coerceAtMost(BURST)
        if (tokens >= 1.0) {
            tokens -= 1.0
            0L
        } else {
            (((1.0 - tokens) / RATE_PER_SEC) * 1000.0).toLong().coerceAtLeast(5L)
        }
    }

    /** Suspend-троттлинг: дожидается токена (для корутинного пути execute). */
    suspend fun throttle() {
        while (true) {
            val wait = nextWaitMs()
            if (wait == 0L) return
            delay(wait)
        }
    }

    /** Блокирующий троттлинг (для sync-пути на потоке загрузчика ExoPlayer). */
    fun throttleBlocking() {
        while (true) {
            val wait = nextWaitMs()
            if (wait == 0L) return
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
        }
    }
}
