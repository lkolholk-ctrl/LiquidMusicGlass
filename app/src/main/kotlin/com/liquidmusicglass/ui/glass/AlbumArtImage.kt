package com.liquidmusicglass.ui.glass

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Минимальный размер стороны чтобы считать обложку HQ */
private const val MIN_HQ_SIZE = 500

/**
 * Универсальный компонент для отображения обложки альбома.
 * Поддерживает:
 * - Локальные треки (MediaStore album art, embedded art)
 * - Онлайн треки (coverUrl из ICM API через Coil)
 */
@Composable
fun AlbumArtImage(
    uri: Uri?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    audioFileUri: Uri? = null,
    albumId: Long = -1L,
    coverUrl: String? = null
) {
    // Онлайн-обложка: используем Coil AsyncImage
    if (!coverUrl.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(coverUrl)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
        return
    }


    val context = LocalContext.current
    var bitmap by remember(uri, audioFileUri, albumId) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(uri, audioFileUri, albumId) { mutableStateOf(false) }

    LaunchedEffect(uri, audioFileUri, albumId) {
        bitmap = null
        loadFailed = false
        if (uri == null && audioFileUri == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            // Собираем все кандидаты, берём самый крупный
            val candidates = mutableListOf<Bitmap>()

            // 1) MMR — embedded picture из аудиофайла (обычно оригинал)
            audioFileUri?.let { fileUri ->
                try {
                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(pfd.fileDescriptor)
                            retriever.embeddedPicture?.let { bytes ->
                                val opts = BitmapFactory.Options().apply {
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                    ?.let { candidates.add(it) }
                            }
                        } finally {
                            retriever.release()
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2) loadThumbnail на audio content URI
            audioFileUri?.let { fileUri ->
                try {
                    val bmp = context.contentResolver
                        .loadThumbnail(fileUri, Size(2048, 2048), null)
                    candidates.add(bmp)
                } catch (_: Exception) {}
            }

            // 3) loadThumbnail на albums URI
            if (albumId > 0) {
                try {
                    val albumUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId
                    )
                    val bmp = context.contentResolver
                        .loadThumbnail(albumUri, Size(2048, 2048), null)
                    candidates.add(bmp)
                } catch (_: Exception) {}
            }

            // 4) Legacy albumart URI
            uri?.let { artUri ->
                try {
                    context.contentResolver.openInputStream(artUri)?.use { stream ->
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeStream(stream, null, opts)
                            ?.let { candidates.add(it) }
                    }
                } catch (_: Exception) {}
            }

            // Берём самый большой по площади
            val best = candidates.maxByOrNull { it.width * it.height }

            // Recycle остальных
            candidates.forEach { if (it !== best) it.recycle() }

            best?.asImageBitmap()
        }
        if (result != null) {
            bitmap = result
        } else {
            loadFailed = true
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            filterQuality = FilterQuality.High
        )
    } else if (loadFailed) {
        PlaceholderArt(modifier = modifier)
    }
}

@Composable
private fun PlaceholderArt(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF2A2A3E),
                        Color(0xFF1A1A2E),
                        Color(0xFF0F0F1A)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.30f),
            modifier = Modifier.fillMaxSize(0.4f)
        )
    }
}
