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
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.liquidmusicglass.ui.theme.AppFontFamily
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
                        fontFamily = AppFontFamily,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(40.dp))
                    BigPlayButton(
                        loading = isBuildingWave,
                        onClick = { viewModel.buildWaveQueue(context) }
                    )
                }
            } else {
                Spacer(Modifier.weight(0.8f))
                Text(
                    text = track.artist,
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AppFontFamily,
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

                Spacer(Modifier.height(20.dp))

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
                            fontFamily = AppFontFamily,
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

                Spacer(Modifier.weight(1f))
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
            fontFamily = AppFontFamily,
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
            fontFamily = AppFontFamily,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WaveGradientBackground(colors: AlbumColors) {
    // Один цвет-«герой» из обложки → яркое ядро + оттенки той же гаммы.
    val primary = pickPrimary(colors)
    val core by animateColorAsState(lerp(primary, Color.White, 0.32f), tween(1200), label = "core")
    val mid by animateColorAsState(primary, tween(1200), label = "mid")
    val sideA by animateColorAsState(primary.shiftHsv(hue = 14f, valMul = 0.95f), tween(1200), label = "sideA")
    val sideB by animateColorAsState(primary.shiftHsv(hue = -14f, satMul = 0.90f), tween(1200), label = "sideB")
    val base by animateColorAsState(lerp(primary, Color.Black, 0.88f), tween(1200), label = "base")

    val transition = rememberInfiniteTransition(label = "wave-bg")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing)),
        label = "t"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(base)

        fun blob(
            color: Color,
            baseX: Float,
            baseY: Float,
            phase: Float,
            radiusFactor: Float,
            alpha: Float,
            driftX: Float = 0.12f,
            driftY: Float = 0.08f
        ) {
            val cx = w * baseX + sin(t + phase) * w * driftX
            val cy = h * baseY + cos(t * 0.7f + phase) * h * driftY
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

        // Broad ambient glow in the upper area
        blob(mid, 0.50f, 0.28f, 0f, 1.25f, 0.55f)
        // Organic side blobs (same family) for a smoky, non-uniform feel
        blob(sideA, 0.30f, 0.20f, 2.0f, 0.85f, 0.45f, driftX = 0.16f, driftY = 0.10f)
        blob(sideB, 0.74f, 0.30f, 4.1f, 0.80f, 0.42f, driftX = 0.16f, driftY = 0.10f)
        // Hot bright core behind the title — concentrated luminous peak
        // (stacked draws build up toward near-white via Screen blend)
        blob(core, 0.50f, 0.23f, 1.0f, 0.52f, 0.78f, driftX = 0.06f, driftY = 0.05f)
        blob(core, 0.50f, 0.23f, 1.0f, 0.30f, 0.65f, driftX = 0.06f, driftY = 0.05f)

        // Strong fade to near-black in the lower half for contrast
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.35f),
                    Color.Black.copy(alpha = 0.92f)
                ),
                startY = h * 0.38f,
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

/** Выбирает один насыщенный цвет-«герой» из палитры обложки. */
private fun pickPrimary(colors: AlbumColors): Color {
    for (c in listOf(colors.vibrant, colors.dominant, colors.lightVibrant, colors.muted)) {
        val v = c.vivify()
        if (v.isSpecified) return v
    }
    return WAVE_FALLBACK_COLORS[0]
}

/** Сдвигает цвет по HSV — для производных оттенков той же гаммы. */
private fun Color.shiftHsv(hue: Float = 0f, satMul: Float = 1f, valMul: Float = 1f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    hsv[0] = (hsv[0] + hue + 360f) % 360f
    hsv[1] = (hsv[1] * satMul).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * valMul).coerceIn(0f, 1f)
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
