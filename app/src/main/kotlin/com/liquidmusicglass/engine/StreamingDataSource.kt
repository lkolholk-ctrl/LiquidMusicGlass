package com.liquidmusicglass.engine

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import java.io.IOException

/**
 * Кастомный DataSource для LiquidMusicGlass.
 *
 * Поддерживает URI схемы:
 * - `liquid://track?id=TRACK_ID&url=REAL_URL` — онлайн трек с ID
 * - `file://...` — локальный файл
 * - `http://...` / `https://...` — прямой URL
 *
 * При отсутствии url в URI — лениво резолвит через PlayerController.
 * При наличии cacheFactory — использует CacheDataSource для кэширования.
 */
@OptIn(UnstableApi::class)
class StreamingDataSource private constructor(
    private val httpDataSource: DataSource,
    private val fileDataSource: DataSource,
    private val cacheDataSource: DataSource?
) : DataSource {

    private var currentDataSource: DataSource? = null
    private var currentUri: Uri? = null
    private var transferListener: TransferListener? = null

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
        httpDataSource.addTransferListener(transferListener)
        fileDataSource.addTransferListener(transferListener)
        cacheDataSource?.addTransferListener(transferListener)
    }

    companion object {
        const val SCHEME_LIQUID = "liquid"
        const val PARAM_TRACK_ID = "id"
        const val PARAM_URL = "url"

        fun create(
            httpDataSource: DefaultHttpDataSource.Factory,
            fileDataSource: FileDataSource = FileDataSource()
        ): DataSource.Factory {
            return DataSource.Factory {
                val http = httpDataSource.createDataSource()
                val file = fileDataSource
                val cache = MediaCacheManager.getCacheDataSourceFactory()?.createDataSource()
                StreamingDataSource(http, file, cache)
            }
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri

        val resolvedUri = resolveUri(uri)
        val resolvedSpec = dataSpec.withUri(resolvedUri)

        currentDataSource = when (resolvedUri.scheme) {
            "file" -> fileDataSource
            "http", "https" -> cacheDataSource ?: httpDataSource
            else -> httpDataSource
        }

        return currentDataSource!!.open(resolvedSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return currentDataSource?.read(buffer, offset, length)
            ?: throw IOException("No data source open")
    }

    override fun close() {
        currentDataSource?.close()
        currentDataSource = null
        currentUri = null
    }

    override fun getUri(): Uri? = currentDataSource?.uri ?: currentUri

    /**
     * Резолвит liquid:// URI в реальный URL.
     * Если url отсутствует — синхронно запрашивает через PlayerController.
     */
    private fun resolveUri(uri: Uri): Uri {
        if (uri.scheme != SCHEME_LIQUID) return uri

        val trackId = uri.getQueryParameter(PARAM_TRACK_ID)
        var url = uri.getQueryParameter(PARAM_URL)

        if (url.isNullOrEmpty() && trackId != null) {
            // Ленивый резолвинг URL без runBlocking
            url = PlayerController.resolveStreamUrlSync(trackId)?.toString()
        }

        if (url.isNullOrEmpty()) {
            throw IOException("Cannot resolve URL for track $trackId")
        }

        return Uri.parse(url)
    }
}
