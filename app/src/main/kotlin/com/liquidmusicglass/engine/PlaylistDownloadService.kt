package com.liquidmusicglass.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.liquidmusicglass.MainActivity
import com.liquidmusicglass.R
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.data.local.db.DownloadedTrackEntity
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight metadata holder for a track to be downloaded.
 * Passed into the service intent as JSON-serialized extras.
 */
data class DownloadTrackMeta(
    val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val durationMs: Long = 0
)

/**
 * Foreground Service for batch playlist downloading (600–1000+ tracks).
 *
 * - Saves to public Downloads/LiquidMusicGlass
 * - Semaphore(3) for max 3 concurrent downloads
 * - Skips existing files (resume support)
 * - Native progress notification with cancel action
 * - Survives app minimization / background kills on Android 14–16
 */
class PlaylistDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "playlist_download_channel"
        const val CHANNEL_NAME = "Playlist Download Services"
        const val NOTIFICATION_ID = 2001

        const val EXTRA_TRACK_IDS = "extra_track_ids"
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"
        const val EXTRA_TRACK_META_JSON = "extra_track_meta_json"

        private const val ACTION_CANCEL = "action_cancel_download"
        private const val CONCURRENCY = 3

        /** Start downloading a batch of tracks with full metadata. */
        fun start(context: Context, tracks: List<Track>, playlistName: String? = null) {
            if (tracks.isEmpty()) return
            val trackIds = tracks.map { it.id }
            // Serialize metadata as simple JSON array
            val metaJson = tracks.joinToString(prefix = "[", postfix = "]") { track ->
                val cover = track.coverUrl?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
                """{"id":"${track.id}","title":"${track.title.replace("\"", "\\\"")}","artist":"${track.artist.replace("\"", "\\\"")}","coverUrl":$cover,"durationMs":${track.durationMs}}"""
            }
            val intent = Intent(context, PlaylistDownloadService::class.java).apply {
                putStringArrayListExtra(EXTRA_TRACK_IDS, ArrayList(trackIds))
                putExtra(EXTRA_TRACK_META_JSON, metaJson)
                playlistName?.let { putExtra(EXTRA_PLAYLIST_NAME, it) }
            }
            context.startForegroundService(intent)
        }

        /** Cancel the running download. */
        fun cancel(context: Context) {
            val intent = Intent(context, PlaylistDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("PlaylistDownloadService")
    )
    private var currentJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    @Volatile
    private var isCancelled = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                isCancelled = true
                currentJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        val trackIds = intent?.getStringArrayListExtra(EXTRA_TRACK_IDS)
        if (trackIds.isNullOrEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val playlistName = intent.getStringExtra(EXTRA_PLAYLIST_NAME)
        val metaJson = intent.getStringExtra(EXTRA_TRACK_META_JSON)
        val metaMap = parseMetaJson(metaJson, trackIds)

        startForeground(NOTIFICATION_ID, buildInitialNotification(trackIds.size))

        currentJob?.cancel()
        isCancelled = false

        currentJob = serviceScope.launch {
            runDownload(trackIds, metaMap, playlistName)
        }

        return START_NOT_STICKY
    }

    private suspend fun runDownload(
        trackIds: List<String>,
        metaMap: Map<String, DownloadTrackMeta>,
        playlistName: String?
    ) {
        val targetDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LiquidMusicGlass"
        )
        if (!targetDir.exists()) targetDir.mkdirs()

        val coversDir = File(targetDir, ".covers")
        if (!coversDir.exists()) coversDir.mkdirs()

        val db = FavoriteTrackDatabase.getInstance(this)
        val quality = IcmAuthRepository.maxQuality.value ?: "256K"
        val ext = if (quality.uppercase() == "ALAC") ".m4a" else ".mp3"
        val semaphore = Semaphore(CONCURRENCY)

        val totalCount = trackIds.size
        val processed = java.util.concurrent.atomic.AtomicInteger(0)
        val skipped = java.util.concurrent.atomic.AtomicInteger(0)
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)

        var lastNotifiedProgress = -1
        fun maybeUpdateNotification() {
            val current = processed.get()
            if (current - lastNotifiedProgress >= 5 || current == totalCount || current == 0) {
                lastNotifiedProgress = current
                updateNotification(
                    title = "Downloading Your Playlist",
                    content = "Downloading: $current / $totalCount tracks",
                    progress = current,
                    max = totalCount
                )
            }
        }

        updateNotification(
            title = "Downloading Your Playlist",
            content = "Downloading: 0 / $totalCount tracks",
            progress = 0,
            max = totalCount
        )

        try {
            supervisorScope {
                trackIds.map { trackId ->
                    async {
                        // ── Isolated try-catch per track ──
                        // A failure here must NEVER cancel sibling coroutines or the supervisorScope
                        try {
                            if (isCancelled) return@async

                            semaphore.withPermit {
                                if (isCancelled) return@withPermit

                                val meta = metaMap[trackId]
                                val safeName = buildSanitizedFileName(meta?.artist, meta?.title, ext)
                                val targetFile = File(targetDir, safeName)
                                val coverFile = File(coversDir, "$trackId.jpg")

                                // ── Idempotency: skip if BOTH audio + cover exist ──
                                val audioExists = targetFile.exists() && targetFile.length() > 1024
                                val coverExists = coverFile.exists() && coverFile.length() > 128
                                if (audioExists && coverExists) {
                                    skipped.incrementAndGet()
                                    processed.incrementAndGet()
                                    maybeUpdateNotification()
                                    return@withPermit
                                }

                                // Also skip if fully recorded in DB with both paths
                                val existing = db.getDownloadedTracks().find { it.trackId == trackId }
                                if (existing != null &&
                                    File(existing.localPath).exists() &&
                                    existing.localCoverPath != null &&
                                    File(existing.localCoverPath).exists()
                                ) {
                                    skipped.incrementAndGet()
                                    processed.incrementAndGet()
                                    maybeUpdateNotification()
                                    return@withPermit
                                }

                                val success = downloadSingleTrack(
                                    trackId = trackId,
                                    targetFile = targetFile,
                                    coverFile = coverFile,
                                    quality = quality,
                                    ext = ext,
                                    meta = meta
                                )
                                processed.incrementAndGet()
                                if (success) successCount.incrementAndGet() else failCount.incrementAndGet()
                                maybeUpdateNotification()
                            }
                        } catch (e: Exception) {
                            // Single-track failure is isolated — log and continue
                            android.util.Log.e(
                                "PlaylistDownloadService",
                                "Track $trackId failed with ${e.javaClass.simpleName}: ${e.message}"
                            )
                            processed.incrementAndGet()
                            failCount.incrementAndGet()
                            maybeUpdateNotification()
                        }
                    }
                }.awaitAll()
            }

            if (isCancelled) {
                showCancelledNotification(processed.get(), totalCount)
            } else {
                showCompletionNotification(
                    successCount = successCount.get(),
                    failCount = failCount.get(),
                    skippedCount = skipped.get(),
                    totalCount = totalCount
                )
            }

        } catch (e: CancellationException) {
            showCancelledNotification(processed.get(), totalCount)
        } catch (e: Exception) {
            // This should only fire if supervisorScope itself fails (extremely unlikely)
            android.util.Log.e("PlaylistDownloadService", "Supervisor scope failed: ${e.message}")
            showCompletionNotification(
                successCount = successCount.get(),
                failCount = failCount.get(),
                skippedCount = skipped.get(),
                totalCount = totalCount
            )
        }
    }

    private suspend fun downloadSingleTrack(
        trackId: String,
        targetFile: File,
        coverFile: File,
        quality: String,
        ext: String,
        meta: DownloadTrackMeta?
    ): Boolean = withContext(Dispatchers.IO) {
        val tempAudio = File(targetFile.parent, "${trackId}.temp")
        val tempCover = File(coverFile.parent, "${trackId}.cover.temp")

        var audioSuccess = false
        var coverSuccess = false

        try {
            // ═══════════════════════════════════════════════════════════════
            //  1. Download audio
            // ═══════════════════════════════════════════════════════════════
            if (tempAudio.exists()) tempAudio.delete()

            val resolvedUri = PlayerController.resolveStreamUrlSync(trackId)
                ?: return@withContext false

            val url = URL(resolvedUri.toString())
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext false
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tempAudio)
            val buffer = ByteArray(8192)
            var count: Int

            while (inputStream.read(buffer).also { count = it } != -1) {
                if (isCancelled) {
                    outputStream.close()
                    inputStream.close()
                    connection.disconnect()
                    tempAudio.delete()
                    tempCover.delete()
                    return@withContext false
                }
                outputStream.write(buffer, 0, count)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            if (targetFile.exists()) targetFile.delete()
            audioSuccess = tempAudio.renameTo(targetFile)

            // ═══════════════════════════════════════════════════════════════
            //  2. Download cover art
            // ═══════════════════════════════════════════════════════════════
            val coverUrl = meta?.coverUrl
            if (!coverUrl.isNullOrBlank()) {
                try {
                    if (tempCover.exists()) tempCover.delete()

                    val coverConn = URL(coverUrl).openConnection() as HttpURLConnection
                    coverConn.connectTimeout = 15000
                    coverConn.readTimeout = 15000
                    coverConn.requestMethod = "GET"
                    coverConn.connect()

                    if (coverConn.responseCode == HttpURLConnection.HTTP_OK) {
                        val coverIn = coverConn.inputStream
                        val coverOut = FileOutputStream(tempCover)
                        val coverBuf = ByteArray(4096)
                        var cCount: Int
                        while (coverIn.read(coverBuf).also { cCount = it } != -1) {
                            if (isCancelled) break
                            coverOut.write(coverBuf, 0, cCount)
                        }
                        coverOut.flush()
                        coverOut.close()
                        coverIn.close()

                        if (coverFile.exists()) coverFile.delete()
                        coverSuccess = tempCover.renameTo(coverFile)
                    }
                    coverConn.disconnect()
                } catch (e: Exception) {
                    android.util.Log.w("PlaylistDownloadService", "Cover download failed for $trackId: ${e.message}")
                    coverSuccess = false
                }
            }

            // ═══════════════════════════════════════════════════════════════
            //  3. Inject ID3 tags (title, artist, cover art)
            // ═══════════════════════════════════════════════════════════════
            if (audioSuccess && ext == ".mp3") {
                injectId3Tags(targetFile, meta, coverFile.takeIf { coverSuccess })
            }

            // ═══════════════════════════════════════════════════════════════
            //  4. Persist to database with full metadata
            // ═══════════════════════════════════════════════════════════════
            if (audioSuccess) {
                val db = FavoriteTrackDatabase.getInstance(this@PlaylistDownloadService)
                db.insertDownloaded(
                    DownloadedTrackEntity(
                        trackId = trackId,
                        title = meta?.title ?: trackId,
                        artistName = meta?.artist,
                        albumTitle = null,
                        durationMs = meta?.durationMs ?: 0L,
                        imageUrl = meta?.coverUrl,
                        localPath = targetFile.absolutePath,
                        localCoverPath = if (coverSuccess) coverFile.absolutePath else null,
                        quality = quality,
                        downloadedAt = System.currentTimeMillis()
                    )
                )
            }

            audioSuccess
        } catch (e: Exception) {
            android.util.Log.e("PlaylistDownloadService", "Download failed for $trackId: ${e.message}")
            if (tempAudio.exists()) tempAudio.delete()
            if (tempCover.exists()) tempCover.delete()
            false
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Filename Sanitization
    // ═════════════════════════════════════════════════════════════════

    /**
     * Build a human-readable, filesystem-safe filename.
     * Format: "[Artist] - [Title].ext"
     * Strips illegal chars: \ / : * ? " < > |
     */
    private fun buildSanitizedFileName(artist: String?, title: String?, ext: String): String {
        val safeArtist = sanitizeFileName(artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist")
        val safeTitle = sanitizeFileName(title?.takeIf { it.isNotBlank() } ?: "Unknown Track")
        return "$safeArtist - $safeTitle$ext"
    }

    private fun sanitizeFileName(input: String): String {
        // Replace illegal filesystem characters
        val illegal = Regex("""[\\/:*?"<>|]""")
        var sanitized = input.replace(illegal, "")
        // Collapse multiple spaces
        sanitized = sanitized.replace(Regex("""\s+"""), " ").trim()
        // Limit length to avoid path-too-long errors (leave room for path prefix)
        if (sanitized.length > 120) {
            sanitized = sanitized.substring(0, 120).trim()
        }
        // Fallback if everything was stripped
        return sanitized.takeIf { it.isNotBlank() } ?: "track"
    }

    // ═════════════════════════════════════════════════════════════════
    //  ID3 Tag Injection
    // ═════════════════════════════════════════════════════════════════

    /**
     * Inject ID3v2.3 tags into an MP3 file using mp3agic.
     * Writes title, artist, and embeds cover artwork directly into the file.
     */
    private fun injectId3Tags(
        mp3File: File,
        meta: DownloadTrackMeta?,
        coverFile: File?
    ) {
        try {
            val mp3 = com.mpatric.mp3agic.Mp3File(mp3File.absolutePath)
            val id3v2 = if (mp3.hasId3v2Tag()) {
                mp3.id3v2Tag
            } else {
                com.mpatric.mp3agic.ID3v24Tag()
            }

            meta?.title?.takeIf { it.isNotBlank() }?.let { id3v2.title = it }
            meta?.artist?.takeIf { it.isNotBlank() }?.let { id3v2.artist = it }

            // Embed cover artwork
            if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                val imageData = coverFile.readBytes()
                val mimeType = when {
                    coverFile.name.endsWith(".png", ignoreCase = true) -> "image/png"
                    else -> "image/jpeg"
                }
                id3v2.setAlbumImage(imageData, mimeType)
            }

            mp3.id3v2Tag = id3v2
            val tempTagged = File(mp3File.parent, "${mp3File.name}.tagged")
            mp3.save(tempTagged.absolutePath)

            // Atomic replace
            if (mp3File.exists()) mp3File.delete()
            tempTagged.renameTo(mp3File)
        } catch (e: Exception) {
            android.util.Log.w("PlaylistDownloadService", "ID3 tag injection failed for ${mp3File.name}: ${e.message}")
            // Non-fatal: file is still playable without tags
        }
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
                description = "Shows progress when downloading playlists for offline listening"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildInitialNotification(total: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Downloading Your Playlist")
            .setContentText("Downloading: 0 / $total tracks")
            .setOngoing(true)
            .setProgress(total, 0, false)
            .setContentIntent(buildMainActivityPendingIntent())
            .addAction(buildCancelAction())
            .build()
    }

    private fun updateNotification(title: String, content: String, progress: Int, max: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .setOnlyAlertOnce(true)
            .setContentIntent(buildMainActivityPendingIntent())
            .addAction(buildCancelAction())
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(
        successCount: Int,
        failCount: Int,
        skippedCount: Int,
        totalCount: Int
    ) {
        stopForeground(STOP_FOREGROUND_DETACH)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Downloads finished!")
            .setContentText(
                "Successfully saved: $successCount, Failed/Skipped: ${failCount + skippedCount} out of $totalCount tracks."
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(buildMainActivityPendingIntent())
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun showCancelledNotification(processed: Int, total: Int) {
        stopForeground(STOP_FOREGROUND_DETACH)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Download cancelled")
            .setContentText("Stopped at $processed / $total tracks")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(buildMainActivityPendingIntent())
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun showErrorNotification(message: String) {
        stopForeground(STOP_FOREGROUND_DETACH)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Download failed")
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
            this, 10, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildCancelAction(): NotificationCompat.Action {
        val intent = Intent(this, PlaylistDownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val pendingIntent = PendingIntent.getService(
            this, 11, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_launcher,
            "Cancel",
            pendingIntent
        ).build()
    }

    /**
     * Parse the JSON metadata array into a map keyed by trackId.
     * Falls back to trackId as title if parsing fails.
     */
    private fun parseMetaJson(json: String?, trackIds: List<String>): Map<String, DownloadTrackMeta> {
        if (json.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, DownloadTrackMeta>()
        try {
            // Simple manual JSON parsing — avoids adding JSON dependency to service
            val trimmed = json.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyMap()
            val inner = trimmed.substring(1, trimmed.length - 1)
            if (inner.isBlank()) return emptyMap()

            var depth = 0
            val objects = mutableListOf<String>()
            val current = StringBuilder()
            for (ch in inner) {
                when (ch) {
                    '{' -> { depth++; current.append(ch) }
                    '}' -> { depth--; current.append(ch); if (depth == 0) { objects.add(current.toString()); current.clear() } }
                    ',' -> {
                        if (depth == 0) {
                            if (current.isNotBlank()) { objects.add(current.toString()); current.clear() }
                        } else {
                            current.append(ch)
                        }
                    }
                    else -> current.append(ch)
                }
            }
            if (current.isNotBlank() && depth == 0) objects.add(current.toString())

            for (obj in objects) {
                val id = extractJsonField(obj, "id") ?: continue
                val title = extractJsonField(obj, "title") ?: id
                val artist = extractJsonField(obj, "artist") ?: "Unknown Artist"
                val coverUrl = extractJsonField(obj, "coverUrl")
                val durationMs = extractJsonLong(obj, "durationMs")
                result[id] = DownloadTrackMeta(
                    trackId = id,
                    title = title,
                    artist = artist,
                    coverUrl = coverUrl,
                    durationMs = durationMs
                )
            }
        } catch (_: Exception) {
            // Fallback: empty map, service will use trackId as title
        }
        return result
    }

    private fun extractJsonField(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*(?:\"([^\"]*)\"|(null))".toRegex()
        val match = pattern.find(json) ?: return null
        return if (match.groupValues[2] == "null") null else match.groupValues[1]
    }

    private fun extractJsonLong(json: String, key: String): Long {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        val match = pattern.find(json) ?: return 0L
        return match.groupValues[1].toLongOrNull() ?: 0L
    }

    override fun onDestroy() {
        super.onDestroy()
        isCancelled = true
        currentJob?.cancel()
        serviceScope.cancel("Service destroyed")
    }

    // Android 14/15: dataSync-FGS бюджет 6ч/сутки — см. PlaylistImportService.
    // Многочасовая загрузка 600-1000+ треков на медленной сети реально
    // упирается в лимит; без обработчика система убивала процесс.
    override fun onTimeout(startId: Int, fgsType: Int) {
        isCancelled = true
        currentJob?.cancel()
        stopSelf()
    }
}
