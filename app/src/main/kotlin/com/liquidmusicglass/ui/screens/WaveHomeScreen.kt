package com.liquidmusicglass.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.AlbumColors
import com.liquidmusicglass.ui.glass.rememberAlbumColors
import com.liquidmusicglass.ui.viewmodel.HomeViewModel
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Моя волна" — главный экран в стиле Яндекс Музыки.
 *
 * Состояния:
 *  - idle (ничего не играет): большой заголовок + круглая кнопка Play;
 *  - playing: имя артиста, обложка и плоская панель управления.
 *
 * Стекло намеренно не используется (тяжёлый блюр лагает на устройствах) —
 * контролы плоские, фон рисуется одним Canvas.
 *
 * Тап по обложке или панели с названием открывает полноэкранный плеер
 * через [onOpenPlayer].
 */
@Composable
fun WaveHomeScreen(
    onNavigateToSearch: () -> Unit = {},
    onOpenPlayer: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel = remember { HomeViewModel() }

    val currentTrack by PlayerController.currentTrack.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val favoriteIds by PlayerController.favoriteIds.collectAsState()
    val isBuildingWave by viewModel.isBuildingWave.collectAsState()

    val albumColors = rememberAlbumColors(currentTrack?.displayArtUri, currentTrack?.coverUrl)

    val track = currentTrack
    val isFavorite = track?.id?.let { favoriteIds.contains(it) } == true

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Живой градиент-фон ──
        WaveGradientBackground(colors = albumColors)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            WaveTopBar(onSearch = onNavigateToSearch)

            if (track == null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Моя волна",
                        color = Color.White,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(40.dp))
                    BigPlayButton(
                        loading = isBuildingWave,
                        onClick = { viewModel.buildWaveQueue(context) }
                    )
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = track.artist,
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(24.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AlbumArtImage(
                        uri = track.displayArtUri,
                        coverUrl = track.coverUrl,
                        albumId = track.albumId,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(216.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .clickable { onOpenPlayer() }
                    )
                }

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Play / pause
                    FlatCircleButton(onClick = { PlayerController.togglePlayPause(context) }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Пауза" else "Играть",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    // Title — opens full player
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { onOpenPlayer() }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.SansSerif,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                    // Like
                    FlatCircleButton(onClick = { PlayerController.toggleFavorite(track.id) }) {
                        Icon(
                            imageVector = Icons.Rounded.FavoriteBorder,
                            contentDescription = "Нравится",
                            tint = if (isFavorite) Color(0xFFFF4D67) else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // ── Ряд пресетов-«шаров» ──
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(WAVE_PRESETS) { preset ->
                    PresetOrb(
                        preset = preset,
                        onClick = { viewModel.buildWaveQueue(context) }
                    )
                }
            }

            // Запас снизу под навбар
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun FlatCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun WaveTopBar(onSearch: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFFFF2D9B), Color(0xFFB14BFF)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        Text(
            text = "Моя волна",
            color = WaveAccent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier.align(Alignment.Center)
        )

        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "Поиск",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(26.dp)
                .clickable { onSearch() }
        )
    }
}

@Composable
private fun BigPlayButton(loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(WaveAccent)
            .clickable(enabled = !loading) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = Color.Black,
                strokeWidth = 3.dp,
                modifier = Modifier.size(34.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Слушать",
                tint = Color.Black,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
private fun PresetOrb(preset: WavePreset, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(96.dp)) {
            val base = preset.color
            val light = lerp(base, Color.White, 0.45f)
            val dark = lerp(base, Color.Black, 0.55f)
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(light, base, dark),
                    center = Offset(size.width * 0.36f, size.height * 0.30f),
                    radius = size.minDimension * 0.95f
                ),
                radius = r,
                center = center
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(size.width * 0.34f, size.height * 0.26f),
                    radius = size.minDimension * 0.34f
                ),
                radius = r,
                center = center
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = preset.label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WaveGradientBackground(colors: AlbumColors) {
    val targets = remember(colors) {
        val raw = listOf(colors.vibrant, colors.dominant, colors.lightVibrant)
        raw.mapIndexed { i, c -> c.vivify().takeOrElse { WAVE_FALLBACK_COLORS[i] } }
    }

    val c1 by animateColorAsState(targets[0], tween(1200), label = "c1")
    val c2 by animateColorAsState(targets[1], tween(1200), label = "c2")
    val c3 by animateColorAsState(targets[2], tween(1200), label = "c3")
    val base by animateColorAsState(lerp(targets[0], Color.Black, 0.80f), tween(1200), label = "base")

    val transition = rememberInfiniteTransition(label = "wave-bg")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
        label = "t"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(base)

        fun blob(color: Color, baseX: Float, baseY: Float, phase: Float, radiusFactor: Float, alpha: Float) {
            val cx = w * baseX + sin(t + phase) * w * 0.14f
            val cy = h * baseY + cos(t * 0.7f + phase) * h * 0.10f
            val radius = w * radiusFactor
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy),
                blendMode = BlendMode.Screen
            )
        }

        blob(c1, 0.30f, 0.30f, 0f, 1.05f, 0.85f)
        blob(c2, 0.72f, 0.42f, 2.1f, 0.95f, 0.70f)
        blob(c3, 0.50f, 0.66f, 4.2f, 1.00f, 0.60f)

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.50f)),
                startY = h * 0.5f,
                endY = h
            )
        )
    }
}

/**
 * Усиливает насыщенность/яркость цвета. Возвращает [Color.Unspecified],
 * если цвет слишком тёмный или серый — тогда подставляется яркий дефолт.
 */
private fun Color.vivify(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    if (hsv[2] < 0.12f || hsv[1] < 0.12f) return Color.Unspecified
    hsv[1] = (hsv[1] * 1.7f).coerceIn(0.55f, 1f)
    hsv[2] = hsv[2].coerceIn(0.55f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private data class WavePreset(val label: String, val color: Color)

private val WaveAccent = Color(0xFFFFE000)

private const val TWO_PI = 6.2831855f

private val WAVE_FALLBACK_COLORS = listOf(
    Color(0xFFE5314E),
    Color(0xFF8A2BE2),
    Color(0xFF2E6BFF)
)

private val WAVE_PRESETS = listOf(
    WavePreset("Прогрессив-хаус", Color(0xFF3B6FE0)),
    WavePreset("Бегаю под летние треки", Color(0xFF2FB24A)),
    WavePreset("Спокойный вечер", Color(0xFF17A2A2)),
    WavePreset("Хочется инди", Color(0xFF5B5BE0)),
    WavePreset("В дороге", Color(0xFFE07B2F)),
    WavePreset("Танцпол", Color(0xFFE0405F))
)
