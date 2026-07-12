package com.liquidmusicglass.data.wave

/**
 * Process-global memory of track IDs that ICM's `POST /track` reported as
 * permanently unavailable (`404 track_not_found`).
 *
 * Зачем: ICM-каталог (`/wave`, `/search`) отдаёт айди, которых нет в стрим-слое —
 * клиент бьётся об `404 track_not_found`, а волна выдаёт тот же битый айди
 * повторно в одной сессии (по полевым логам — один айди по 3 раза). Каждая
 * попытка жжёт запрос и роняет тихую дыру в очередь. Пока ICM не починит каталог
 * у себя, заносим такие айди сюда, и волна перестаёт их предлагать.
 *
 * Регион в ключ НЕ входит: те же числовые айди продолжали давать 404 уже ПОСЛЕ
 * снятия хардкода региона (`tr` / `/me/region.current`), т.е. это не
 * регион-мисматч — айди мёртв для этого рана независимо от региона/режима/сессии.
 *
 * Ограничен по размеру (FIFO-вытеснение старейших), чтобы длинная сессия не
 * растила его без предела. Живёт только до смерти процесса — трек, который
 * вернётся в каталог после серверного фикса, «воскресает» на следующем холодном
 * старте. Это верный ритм для серверной починки.
 */
object DeadTrackRegistry {
    private const val MAX_ENTRIES = 500

    // Insertion-ordered → вытесняем старейший занесённый айди. Доступ под своим
    // же монитором (@Synchronized): реестр читается из потоков волны и пишется из
    // резолва стрима (IO), гонки недопустимы.
    private val dead = LinkedHashSet<String>()

    @Synchronized
    fun markDead(trackId: String?) {
        val key = trackId.normalizedIdKey() ?: return
        if (!dead.add(key)) return
        while (dead.size > MAX_ENTRIES) {
            val oldest = dead.iterator().next()
            dead.remove(oldest)
        }
    }

    @Synchronized
    fun isDead(trackId: String?): Boolean {
        val key = trackId.normalizedIdKey() ?: return false
        return key in dead
    }

    @Synchronized
    fun snapshot(): Set<String> = LinkedHashSet(dead)

    @Synchronized
    fun size(): Int = dead.size

    /** Только для тестов: сброс между кейсами. */
    @Synchronized
    internal fun clearForTest() = dead.clear()
}
