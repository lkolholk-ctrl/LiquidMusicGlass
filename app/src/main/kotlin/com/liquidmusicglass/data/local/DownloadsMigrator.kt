package com.liquidmusicglass.data.local

import android.content.Context
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ОДНОРАЗОВАЯ миграция уже скачанного из приватной папки
 * (`filesDir/downloads/`) в публичные Загрузки (`Download/LiquidMusicGlass`).
 *
 * У пользователей сотни треков (полевой кейс: ~800) — перекачивать нельзя,
 * переносим файлы на месте: копия через MediaStore → путь в БД на content:// →
 * приватный оригинал удаляется. Идемпотентно: content:// и битые файлы
 * пропускаются; флаг в prefs гарантирует один прогон; упавшая на середине
 * миграция безопасно доделает остаток на следующем запуске (флаг ставится
 * только после полного прохода).
 *
 * Прогресс — [progress] (done/total) для ненавязчивого индикатора в UI;
 * null = миграция не идёт.
 */
object DownloadsMigrator {

    private const val PREFS = "downloads_migration"
    private const val KEY_DONE = "downloads_migrated_v2"

    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Запустить, если ещё не мигрировали. Не блокирует, звать с любого потока. */
    fun migrateIfNeeded(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val ok = runCatching { migrate(app) }.isSuccess
            // Флаг — только после полного прохода: прерванная миграция
            // доделает остаток при следующем запуске (уже перенесённые
            // треки content:// и будут пропущены).
            if (ok) prefs.edit().putBoolean(KEY_DONE, true).apply()
            _progress.value = null
        }
    }

    private fun migrate(context: Context) {
        val db = FavoriteTrackDatabase.getInstance(context)
        val candidates = db.getDownloadedTracks()
            .filter { !PublicDownloads.isContentPath(it.localPath) }
        if (candidates.isEmpty()) return

        val total = candidates.size
        var done = 0
        var moved = 0
        com.liquidmusicglass.debug.DebugLog.add("MIGRATE downloads → public: старт, $total треков")

        for (e in candidates) {
            _progress.value = done to total
            done++

            val src = File(e.localPath)
            if (!src.exists() || src.length() == 0L) continue   // битый/удалённый — пропуск

            val ext = "." + src.extension.ifBlank { "mp3" }
            val name = PublicDownloads.displayName(e.artistName, e.title).ifBlank { e.trackId }
            val uri = PublicDownloads.exportAudio(context, src, name, ext) ?: continue

            // БД → content URI, затем освобождаем приватную копию.
            db.updateDownloadedLocalPath(e.trackId, uri, reload = false)
            src.delete()
            moved++
        }

        db.refreshDownloads()
        com.liquidmusicglass.debug.DebugLog.add("MIGRATE downloads → public: готово, $moved/$total перенесено")
    }
}
