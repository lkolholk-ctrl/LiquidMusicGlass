package com.liquidmusicglass.engine

import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Держит избранное скачанным, чтобы оно играло без сети.
 *
 * Работает поверх того же кэша, что и обычное воспроизведение: трек, уложенный
 * сюда, потом просто играет из кэша, отдельного хранилища для «загрузок» не
 * появляется.
 *
 * Осознанные ограничения, без которых фича вредит больше, чем помогает:
 *  - качаем по одному треку и с паузой между ними: иначе фоновая загрузка
 *    отбирает полосу у того, что человек слушает прямо сейчас;
 *  - не начинаем, пока идёт воспроизведение — ждём тишины;
 *  - уважаем выключенный кэш: если пользователь поставил размер 0, значит он
 *    не хочет, чтобы приложение занимало место.
 */
object FavoritesAutoDownloader {

    /** Пауза между треками — фоновая загрузка не должна мешать прослушиванию. */
    private const val BETWEEN_TRACKS_DELAY_MS = 3_000L

    /** Как часто проверять, не появилось ли новое избранное. */
    private const val SCAN_INTERVAL_MS = 15L * 60 * 1000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { syncOnce() }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun syncOnce() {
        if (!PlayerSettings.autoDownloadFavorites.value) return
        if (!MediaCacheManager.isCacheEnabled()) return

        val favorites = LibraryRepository.getAllFavoritesAsTracks()
            .filter { it.isOnlineTrack }
        if (favorites.isEmpty()) return

        var downloaded = 0
        for (track in favorites) {
            if (job?.isActive != true) return
            // Пока человек слушает, полосу занимать нельзя: докачаем в тишине.
            if (PlayerController.isPlaying.value) return
            if (MediaCacheManager.isFullyCached(track.id)) continue

            val url = PlayerController.resolveStreamUrlSync(track.id) ?: continue
            val ok = MediaCacheManager.preCacheTrack(track.id, url, exclusive = false)
            if (ok) downloaded++
            delay(BETWEEN_TRACKS_DELAY_MS)
        }
        if (downloaded > 0) DebugLog.add("Favorites: скачано $downloaded")
    }
}
