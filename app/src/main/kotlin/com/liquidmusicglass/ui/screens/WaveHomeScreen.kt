package com.liquidmusicglass.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liquidmusicglass.api.icm.IcmChart
import com.liquidmusicglass.api.icm.IcmHomeItem
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.rememberAlbumColors
import com.liquidmusicglass.ui.player.AuraBackground
import com.liquidmusicglass.ui.theme.AppFontFamily
import com.liquidmusicglass.ui.viewmodel.HomeViewModel

/**
 * "My Wave" — the main screen, our own take on the Yandex-Music style feed.
 *
 * It is a single scrollable feed:
 *  - a wave hero (idle: big title + Play; playing: artist, cover and flat controls);
 *  - a row of animated mood tiles (patterned, color-shifting);
 *  - content sections reusing [HomeViewModel] data (recently played, charts,
 *    new releases and recommendations).
 *
 * Glass is intentionally avoided (heavy blur lags on devices) — controls are flat
 * and the background is a single animated aura Canvas. Tapping the cover or the
 * title panel opens the full-screen player via [onOpenPlayer].
 */
@Composable
fun WaveHomeScreen(
    onNavigateToSearch: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPlaylist: (String) -> Unit = {},
    onOpenAuth: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    animationsActive: Boolean = true,
) {
    val context = LocalContext.current
    val viewModel = remember { HomeViewModel() }

    LaunchedEffect(Unit) { viewModel.loadHomeContent() }

    val currentTrack by PlayerController.currentTrack.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val favoriteIds by PlayerController.favoriteIds.collectAsState()
    val isBuildingWave by viewModel.isBuildingWave.collectAsState()
    val needsOnboarding by viewModel.needsOnboarding.collectAsState()
    val needsLink by viewModel.needsLink.collectAsState()
    val recentlyPlayed by PlayerController.recentlyPlayed.collectAsState()
    val homeContent by viewModel.homeContent.collectAsState()
    val charts by viewModel.charts.collectAsState()

    // Активная «именованная» волна (по муду/треку/артисту). У дефолтной «Моей волны»
    // имени нет → индикатор не показываем.
    val waveContext by PlayerController.waveRefillContext.collectAsState()
    val activeStationName = waveContext?.name?.takeIf { it.isNotBlank() }

    val albumColors = rememberAlbumColors(currentTrack?.displayArtUri, currentTrack?.coverUrl)

    val track = currentTrack
    val isFavorite = track?.id?.let { favoriteIds.contains(it) } == true

    // Все смысловые блоки home-контента (popular / banners / new_releases /
    // recommendations …). Чарты идут отдельной секцией из viewModel.charts.
    val homeBlocks = remember(homeContent) {
        homeContent?.blocks?.filter { it.type != "charts" && it.items.isNotEmpty() } ?: emptyList()
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Living aura background (own AGSL shader, reacts to the music) ──
        AuraBackground(
            albumColors = albumColors,
            modifier = Modifier.fillMaxSize(),
            animate = animationsActive,
            smokeSaturation = 1.22f,
            smokeContrast = 1.16f
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item { WaveTopBar(onSearch = onNavigateToSearch, onOpenProfile = onOpenProfile) }

            // ── Индикатор активной волны (по муду/треку/артисту) + сброс на «Мою волну» ──
            if (activeStationName != null) {
                item {
                    WaveStationIndicator(
                        name = activeStationName,
                        onClear = { viewModel.buildWaveQueue(context) }
                    )
                }
            }

            // ── Hero ──
            item {
                if (track == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "My Wave",
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
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Весь блок «артист + обложка + контролы» опущен чуть ниже.
                        Spacer(Modifier.height(40.dp))
                        Text(
                            text = track.artist,
                            color = Color.White,
                            fontSize = 44.sp,
                            // Межстрочный интервал — чтобы при переносе (длинное имя)
                            // строки не налезали друг на друга.
                            lineHeight = 50.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AppFontFamily,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                        )
                        Spacer(Modifier.height(26.dp))
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
                        Spacer(Modifier.height(28.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FlatCircleButton(onClick = { PlayerController.togglePlayPause(context) }) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
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
                            FlatCircleButton(onClick = { PlayerController.toggleFavorite(track.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Like",
                                    tint = if (isFavorite) Color(0xFFFF4D67) else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Animated mood tiles ──
            item {
                // Блобы опущены заметно ниже контролов (больше воздуха сверху).
                Spacer(Modifier.height(36.dp))
                WaveMoodTiles(
                    onSelect = { mood -> viewModel.buildMoodWave(context, mood.query, mood.label) },
                    animate = animationsActive
                )
                Spacer(Modifier.height(8.dp))
            }

            // Рекомендации (recently played / home-блоки / charts) перенесены в таб New.
            // На Wave остаются только мудкарточки + плеер волны/текущий трек.
        }

        // ── Онбординг волны: показываем, когда персональная волна пуста и юзер
        // ещё не выбрал стартовых артистов (по доке: empty wave → wave/onboarding). ──
        if (needsOnboarding) {
            WaveOnboardingScreen(
                onComplete = {
                    viewModel.clearOnboardingFlag()
                    viewModel.buildWaveQueue(context)
                },
                onDismiss = { viewModel.clearOnboardingFlag() }
            )
        }

        // ── Гейт по TG-линку: без partner_user_id персонализации нет, поэтому вместо
        // общей выдачи предлагаем залогиниться через Telegram. ──
        if (needsLink) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.clearLinkFlag() },
                title = { Text("Connect Telegram") },
                text = {
                    Text(
                        "Sign in with Telegram so My Wave can adapt to you. " +
                        "Without linking, the server returns generic recommendations."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.clearLinkFlag()
                        onOpenAuth()
                    }) { Text("Sign in") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.clearLinkFlag()
                    }) { Text("Later") }
                }
            )
        }
    }
}

internal fun IcmHomeItem.toWaveTrack(): Track = Track(
    id = id,
    title = title,
    artist = displayArtist,
    albumName = album ?: "",
    uri = Uri.parse("https://byicloud.online/track/$id"),
    durationMs = durationMs,
    albumId = collectionId?.hashCode()?.toLong() ?: -1L,
    coverUrl = cover,
    isExplicit = isExplicit,
    // без source резолвер стрима не знал, откуда тянуть (apple/vk) → трек не грузился
    source = source,
    genre = genre
)

@Composable
private fun WaveSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AppFontFamily,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp)
    )
}

@Composable
private fun WaveTrackCard(
    title: String,
    subtitle: String,
    coverUrl: String?,
    uri: Uri? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        if (uri != null) {
            AlbumArtImage(
                uri = uri,
                coverUrl = coverUrl,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WaveChartCard(
    chart: IcmChart,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(chart.cover)
                    .crossfade(true)
                    .build(),
                contentDescription = chart.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            startY = 80f
                        )
                    )
            )
            Text(
                text = chart.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = AppFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${chart.tracks.size} tracks",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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

/** Чип активной именованной волны: «Wave by <name>» + крестик сброса на My Wave. */
@Composable
private fun WaveStationIndicator(name: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(start = 14.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Wave by $name",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable { onClear() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Reset to My Wave",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun WaveTopBar(onSearch: () -> Unit, onOpenProfile: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
    ) {
        // Слева — профиль (вход/аккаунт), заменил фиолетовый play-квадрат.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable { onOpenProfile() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountCircle,
                contentDescription = "Profile",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }

        Text(
            text = "My Wave",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AppFontFamily,
            modifier = Modifier.align(Alignment.Center)
        )

        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "Search",
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
            .size(132.dp)
            .clickable(enabled = !loading) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = WaveAccent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
        } else {
            // Просто большой треугольник, без круга/подложки
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Listen",
                tint = WaveAccent,
                modifier = Modifier.size(124.dp)
            )
        }
    }
}

// Бледно-зелёный акцент волны (заменил жёлтый — цвет Яндекса убран).
private val WaveAccent = Color(0xFF88C088)
