package com.liquidmusicglass.data.playlistimport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.liquidmusicglass.MainActivity
import com.liquidmusicglass.R
import com.liquidmusicglass.data.playlistimport.di.PlaylistImportModule
import kotlinx.coroutines.*

/**
 * Foreground Service for playlist import.
 *
 * Runs independently of the UI lifecycle — survives app minimization,
 * screen lock, and tab switches. Posts a native system notification
 * with a progress bar that updates as tracks are matched.
 *
 * Start via:
 *   val intent = Intent(context, PlaylistImportService::class.java)
 *   intent.putExtra(EXTRA_URL, url)
 *   context.startForegroundService(intent)
 */
class PlaylistImportService : Service() {

    companion object {
        const val CHANNEL_ID = "yandex_import_channel"
        const val CHANNEL_NAME = "Playlist Import Services"
        // 3001, НЕ 1001 (P1, аудит): 1001 занят медиа-нотификацией AudioService —
        // импорт замещал контролы плеера в шторке и ломал их по завершении.
        const val NOTIFICATION_ID = 3001

        const val EXTRA_URL = "extra_url"
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"

        private const val ACTION_CANCEL = "action_cancel"

        /** Convenience: start the service with a URL. */
        fun start(context: Context, url: String, playlistName: String? = null) {
            val intent = Intent(context, PlaylistImportService::class.java).apply {
                putExtra(EXTRA_URL, url)
                playlistName?.let { putExtra(EXTRA_PLAYLIST_NAME, it) }
            }
            context.startForegroundService(intent)
        }

        /** Convenience: cancel the running import. */
        fun cancel(context: Context) {
            val intent = Intent(context, PlaylistImportService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("PlaylistImportService")
    )
    private var currentJob: Job? = null
    /** Поколение импорта (P1, аудит): отмена ПЕРВОГО импорта при старте второго
     *  делала stopSelf в catch — убивая сервис со свежезапущенным импортом. */
    @Volatile private var importGeneration = 0
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelImport()
                return START_NOT_STICKY
            }
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val playlistName = intent.getStringExtra(EXTRA_PLAYLIST_NAME)

        // Start as foreground service immediately (required within 5s on Android O+)
        startForeground(NOTIFICATION_ID, buildInitialNotification())

        // Cancel any previous job
        currentJob?.cancel()
        val gen = ++importGeneration

        currentJob = serviceScope.launch {
            runImport(gen, url, playlistName)
        }

        return START_NOT_STICKY
    }

    private suspend fun runImport(gen: Int, url: String, playlistName: String?) {
        val logger = ImportFileLogger(this)
        logger.clear()
        logger.log("I", "Service", "Starting import for: $url")

        val repository = PlaylistImportModule.providePlaylistImportRepository()

        // Само-троттлинг scrape-источников (Яндекс/Spotify): пауза + дневной
        // лимит, чтобы не дёргать чужой сервис часто и не словить блок по
        // velocity. Apple (официальный ICM) не троттлим.
        val source = repository.detectSourceType(url)
        val throttleKey = when (source) {
            PlaylistSourceType.YANDEX -> "yandex"
            PlaylistSourceType.SPOTIFY -> "spotify"
            else -> null
        }
        if (throttleKey != null) {
            val blockMsg = ImportRateGate.check(this, throttleKey)
            if (blockMsg != null) {
                logger.log("W", "Service", "Import throttled ($throttleKey): $blockMsg")
                PlaylistImportManager.publishState(ImportState.Error(blockMsg))
                showErrorNotification(blockMsg)
                return
            }
        }

        // UI: подсветить карточку сервиса, в который сейчас идёт импорт.
        // Apple (официальный ICM) тоже показываем, но source-строку берём из типа.
        PlaylistImportManager.setImportingSource(
            throttleKey ?: when (source) {
                PlaylistSourceType.APPLE -> "apple"
                else -> null
            }
        )

        try {
            // Phase 1: Resolving
            updateNotification(
                title = "Importing Your Playlist",
                content = "Initializing... Please wait.",
                progress = 0,
                max = 0,
                indeterminate = true
            )

            // Отмечаем факт импорта ДО резолва — даже неудачная попытка дёрнула
            // источник, поэтому в счётчик частоты она должна попасть.
            throttleKey?.let { ImportRateGate.record(this, it) }

            val result = repository.importPlaylist(
                url = url,
                onState = { state ->
                    // Пушим в менеджер — чтобы UI в приложении показывал прогресс
                    // (X из Y смэтчено) прямо на карточке сервиса, не только в шторке.
                    PlaylistImportManager.publishState(state)
                    when (state) {
                        is ImportState.Loading -> {
                            val phaseText = when (state.phase) {
                                LoadingPhase.RESOLVING -> "Fetching playlist..."
                                LoadingPhase.MATCHING -> "Matching tracks against catalog: ${state.processedTracks} / ${state.totalTracks}"
                                LoadingPhase.SAVING -> "Saving to your library..."
                            }
                            updateNotification(
                                title = "Importing Your Playlist",
                                content = phaseText,
                                progress = state.processedTracks,
                                max = state.totalTracks.coerceAtLeast(1),
                                indeterminate = false
                            )
                        }
                        else -> {} // Success/Error handled after importPlaylist returns
                    }
                },
                logger = logger
            )

            // Phase 2: Saving
            updateNotification(
                title = "Importing Your Playlist",
                content = "Saving to your library...",
                progress = result.matchedTracks.size,
                max = result.totalTracks.coerceAtLeast(1),
                indeterminate = false
            )

            val localPlaylistId = repository.saveToLocalPlaylist(result, playlistName)
            val savedPlaylistName = com.liquidmusicglass.engine.PlaylistManager.getById(localPlaylistId)?.name
                ?: "Imported Playlist"

            logger.log("I", "Service", "Import complete: ${result.matchedTracks.size} tracks saved to $savedPlaylistName")

            PlaylistImportManager.publishState(
                ImportState.Success(
                    insertedCount = result.matchedTracks.size,
                    playlistId = localPlaylistId,
                    playlistName = savedPlaylistName
                )
            )

            // Final success notification (non-ongoing, dismissible)
            showCompletionNotification(
                title = "Import completed successfully!",
                content = "Total tracks added: ${result.matchedTracks.size}",
                playlistId = localPlaylistId
            )

        } catch (e: CancellationException) {
            logger.log("I", "Service", "Import cancelled")
            // stopSelf только если МЫ всё ещё актуальный импорт: иначе нас
            // вытеснил новый — сервис должен жить дальше (P1, аудит).
            if (gen == importGeneration) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            throw e
        } catch (e: PlaylistImportException) {
            logger.log("E", "Service", "Import failed: ${e.message}")
            showErrorNotification(e.message ?: "Import failed")
        } catch (e: Exception) {
            val msg = "Import failed: ${e.javaClass.simpleName}: ${e.message}"
            logger.log("E", "Service", msg)
            showErrorNotification(msg)
        }
    }

    private fun cancelImport() {
        currentJob?.cancel()
        currentJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ═════════════════════════════════════════════════════════════════
    //  Notification Helpers
    // ═════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress when importing playlists from external services"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Importing Your Playlist")
            .setContentText("Initializing... Please wait.")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(buildMainActivityPendingIntent())
            .addAction(buildCancelAction())
            .build()
    }

    private fun updateNotification(
        title: String,
        content: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean
    ) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setProgress(max, progress, indeterminate)
            .setOnlyAlertOnce(true)
            .setContentIntent(buildMainActivityPendingIntent())
            .addAction(buildCancelAction())
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(title: String, content: String, playlistId: String) {
        stopForeground(STOP_FOREGROUND_DETACH)

        val openPlaylistIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to_playlist", playlistId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2, openPlaylistIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun showErrorNotification(message: String) {
        stopForeground(STOP_FOREGROUND_DETACH)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Import failed")
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(buildMainActivityPendingIntent())
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun buildMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildCancelAction(): NotificationCompat.Action {
        val intent = Intent(this, PlaylistImportService::class.java).apply {
            action = ACTION_CANCEL
        }
        val pendingIntent = PendingIntent.getService(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_launcher,
            "Cancel",
            pendingIntent
        ).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        serviceScope.cancel("Service destroyed")
    }

    // Android 14/15: dataSync-FGS имеет бюджет 6ч/сутки; по исчерпании система
    // зовёт onTimeout, и если сервис за секунды не остановится — процесс
    // убивается ForegroundServiceDidNotStopInTimeException (P1, аудит).
    override fun onTimeout(startId: Int, fgsType: Int) {
        currentJob?.cancel()
        stopSelf()
    }
}
