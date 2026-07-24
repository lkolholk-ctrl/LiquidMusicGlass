package com.liquidmusicglass.ui.lyrics

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.icons.LiquidGlyphs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Оверлей «поделиться строкой лирики» (как у Apple Music — карточка с текстом
 * и обложкой). Долгий тап по строке в [LyricsScreen] открывает это. Карточка
 * рендерится в GraphicsLayer, захватывается в PNG и шарится через FileProvider.
 */
@Composable
fun LyricShareOverlay(
    lineText: String,
    trackTitle: String,
    trackArtist: String,
    albumArtUri: Uri?,
    coverUrl: String?,
    albumColors: AlbumColors?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()
    val accent = albumColors?.vibrant ?: Color.White

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                // Клик по контенту не закрывает.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Сама карточка (её и захватываем) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
                    .clip(RoundedCornerShape(24.dp))
                    .drawWithContent {
                        // Записываем содержимое в offscreen-слой для захвата в PNG
                        // и одновременно рисуем его на экран (без drawLayer — его
                        // нет в нашей версии Compose как DrawScope-расширения).
                        graphicsLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    }
            ) {
                AlbumArtImage(
                    uri = albumArtUri,
                    coverUrl = coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Затемняющий градиент, чтобы текст читался.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.15f),
                                0.5f to Color.Black.copy(alpha = 0.45f),
                                1f to Color.Black.copy(alpha = 0.88f)
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(26.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("LMG", color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Column {
                        Text(
                            lineText,
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 32.sp
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))) {
                                AlbumArtImage(
                                    uri = albumArtUri, coverUrl = coverUrl,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    trackTitle, color = Color.White, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    trackArtist, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Кнопки: Поделиться / Закрыть ──
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accent)
                        .liquidClickable {
                            scope.launch {
                                val ok = captureAndShare(context, graphicsLayer)
                                if (ok) onDismiss()
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(LiquidGlyphs.Share, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Close", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.1f))
                        .liquidClickable { onDismiss() }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
    }
}

private suspend fun captureAndShare(
    context: android.content.Context,
    graphicsLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
): Boolean {
    return try {
        val bitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "lyric.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share lyrics"))
        true
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Couldn't share", android.widget.Toast.LENGTH_SHORT).show()
        false
    }
}
