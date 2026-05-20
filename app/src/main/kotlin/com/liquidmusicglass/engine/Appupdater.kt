package com.liquidmusicglass.engine

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App Updater — проверка и установка обновлений через GitHub Releases.
 *
 * Как работает:
 * 1. При старте приложения проверяет GitHub API
 * 2. Сравнивает versionCode из release tag с текущим
 * 3. Если есть новая версия — показывает диалог
 * 4. Скачивает APK через DownloadManager
 * 5. Открывает установщик
 *
 * Как выкладывать обновления:
 * 1. Собери APK
 * 2. На GitHub: Releases → New Release
 * 3. Tag: "v1.0.1" (число после v = versionCode)
 * 4. Приложи APK файл
 * 5. Publish
 */
object AppUpdater {

    // ⚠️ ЗАМЕНИ НА СВОЙ РЕПОЗИТОРИЙ
    private const val GITHUB_USER = "stanislavdev987"
    private const val GITHUB_REPO = "LiquidMusicGlass"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest"

    // ── State ──
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable

    private val _latestVersion = MutableStateFlow("")
    val latestVersion: StateFlow<String> = _latestVersion

    private val _changelog = MutableStateFlow("")
    val changelog: StateFlow<String> = _changelog

    private val _downloadProgress = MutableStateFlow(-1) // -1 = not downloading
    val downloadProgress: StateFlow<Int> = _downloadProgress

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking

    private var apkUrl: String? = null
    private var downloadId: Long = -1

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val changelog: String,
        val apkUrl: String?,
        val apkSize: Long
    )

    /**
     * Проверить наличие обновлений.
     * @param currentVersionCode текущий versionCode приложения
     */
    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        _isChecking.value = true
        try {
            val connection = URL(API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (connection.responseCode != 200) {
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(response)
            val tagName = json.optString("tag_name", "") // "v1.0.1"
            val body = json.optString("body", "") // changelog
            val assets = json.optJSONArray("assets")

            // Parse version from tag: "v1.0.1" → 101, "v2" → 2
            val remoteVersionCode = parseVersionCode(tagName)
            val versionName = tagName.removePrefix("v")

            // Find APK in assets
            var apkDownloadUrl: String? = null
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            val info = UpdateInfo(
                versionName = versionName,
                versionCode = remoteVersionCode,
                changelog = body,
                apkUrl = apkDownloadUrl,
                apkSize = apkSize
            )

            if (remoteVersionCode > currentVersionCode && apkDownloadUrl != null) {
                _updateAvailable.value = true
                _latestVersion.value = versionName
                _changelog.value = body
                apkUrl = apkDownloadUrl
            } else {
                _updateAvailable.value = false
            }

            info
        } catch (_: Exception) {
            null
        } finally {
            _isChecking.value = false
        }
    }

    /**
     * Скачать и установить обновление.
     */
    fun downloadAndInstall(context: Context) {
        val url = apkUrl ?: return

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "LiquidMusicGlass_${_latestVersion.value}.apk"

        // Удалить старый файл если есть
        val oldFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        if (oldFile.exists()) oldFile.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("LiquidMusicGlass Update")
            .setDescription("Downloading v${_latestVersion.value}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        downloadId = downloadManager.enqueue(request)
        _downloadProgress.value = 0

        // Register receiver for download complete
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    _downloadProgress.value = 100
                    ctx.unregisterReceiver(this)
                    installApk(ctx, fileName)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /**
     * Открыть установщик APK.
     */
    private fun installApk(context: Context, fileName: String) {
        try {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)

            _downloadProgress.value = -1
            _updateAvailable.value = false
        } catch (_: Exception) {
            _downloadProgress.value = -1
        }
    }

    /**
     * Отклонить обновление.
     */
    fun dismiss() {
        _updateAvailable.value = false
    }

    /**
     * Парсит versionCode из тега.
     * "v1.0.1" → 10001, "v1.2" → 10200, "v2" → 20000
     */
    private fun parseVersionCode(tag: String): Int {
        val clean = tag.removePrefix("v").trim()
        val parts = clean.split(".")
        return try {
            when (parts.size) {
                1 -> parts[0].toInt() * 10000
                2 -> parts[0].toInt() * 10000 + parts[1].toInt() * 100
                3 -> parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt()
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }
}