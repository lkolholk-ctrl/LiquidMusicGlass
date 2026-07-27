package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.liquidmusicglass.api.icm.IcmArtistAlbum
import com.liquidmusicglass.api.icm.IcmArtistResponse
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.data.local.db.AppDatabase
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMetrics
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidSurfaces
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope


/** Обложки каталога приходят огромными; для карточек это лишний трафик и память. */
private fun String?.toThumb(): String? = this
    ?.replace("1000x1000", "600x600")
    ?.replace("1500x1500", "600x600")
    ?.replace("300x300", "600x600")

/**
 * Экран артиста.
 *
 * Порядок разделов привычный: сначала то, ради чего сюда заходят (послушать
 * прямо сейчас), затем свежий релиз, дискография и только потом окружение
 * артиста. Подача своя — живая шапка, личный блок и разный вес разделов вместо
 * ровного списка одинаковых каруселей.
 */
@Composable
fun ArtistDetailScreen(
    artistId: String,
    onBack: () -> Unit,
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val colors = LiquidTheme.colors

    var artist by remember { mutableStateOf<IcmArtistResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artistId) {
        isLoading = true
        error = null
        try {
            val result = IcmRepository.getArtist(artistId)
            if (result == null) {
                error = IcmRepository.lastError.value ?: "Artist not found"
            } else {
                artist = result
            }
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    // Полная выборка треков артиста: топ + все релизы. Нужна и кнопке
    // воспроизведения, и личному блоку — по ней считается прослушанное.
    var artistTracks by remember {
        mutableStateOf<List<com.liquidmusicglass.engine.Track>>(emptyList())
    }

    LaunchedEffect(artist) {
        val art = artist ?: return@LaunchedEffect
        val allTracks = mutableListOf<com.liquidmusicglass.engine.Track>()
        art.topSongs.mapTo(allTracks) { it.toTrack() }

        val albumIds = mutableListOf<String>()
        art.albums.mapTo(albumIds) { it.id }
        art.singles.mapTo(albumIds) { it.id }
        art.featuring.mapTo(albumIds) { it.id }
        art.latestRelease?.let { albumIds.add(it.id) }

        if (albumIds.isNotEmpty() && IcmRepository.isInitialized.value) {
            coroutineScope {
                val albumResults = albumIds.distinct().map { albumId ->
                    async(Dispatchers.IO) {
                        try { IcmRepository.getAlbum(albumId) } catch (_: Exception) { null }
                    }
                }.awaitAll()

                for (album in albumResults.filterNotNull()) {
                    album.tracks.mapTo(allTracks) { track ->
                        com.liquidmusicglass.engine.Track(
                            id = track.id,
                            title = track.title,
                            artist = track.artist,
                            albumName = album.album.title,
                            uri = android.net.Uri.parse("https://byicloud.online/track/${track.id}"),
                            durationMs = track.durationMs,
                            albumId = album.album.id.hashCode().toLong(),
                            coverUrl = album.album.cover.toThumb()
                        )
                    }
                }
            }
        }
        artistTracks = allTracks.distinctBy { it.id }
    }

    // Личный блок: сколько раз слушали именно этого артиста и что чаще всего.
    // Такого на карточке артиста нет ни у одного стриминга, а данные у нас свои.
    var playCount by remember { mutableStateOf(0) }
    var favouriteTrackTitle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artistTracks) {
        if (artistTracks.isEmpty()) return@LaunchedEffect
        try {
            val stats = AppDatabase.getInstance(context).playbackHistoryDao().getAllTrackStats(500)
            val byId = artistTracks.associateBy { it.id }
            val mine = stats.filter { byId.containsKey(it.trackId) }
            playCount = mine.sumOf { it.playCount }
            favouriteTrackTitle = mine.maxByOrNull { it.playCount }
                ?.takeIf { it.playCount > 0 }
                ?.let { byId[it.trackId]?.title }
        } catch (_: Exception) {
            playCount = 0
        }
    }

    val topSongs = remember(artist) { artist?.topSongs.orEmpty() }

    // Дискографию делим по типу релиза: сборники и концертные записи слушают
    // иначе, чем студийные альбомы, и в общей куче они только мешают искать.
    val allAlbums = remember(artist) { artist?.albums.orEmpty().distinctBy { it.id } }
    val compilations = remember(allAlbums) {
        allAlbums.filter { it.type?.contains("compilation", ignoreCase = true) == true }
    }
    val liveAlbums = remember(allAlbums) {
        allAlbums.filter {
            it.type?.contains("live", ignoreCase = true) == true ||
                it.title.contains("(Live", ignoreCase = true)
        }
    }
    val albums = remember(allAlbums, compilations, liveAlbums) {
        val excluded = (compilations + liveAlbums).map { it.id }.toSet()
        allAlbums.filterNot { it.id in excluded }
    }
    val singles = remember(artist) { artist?.singles.orEmpty().distinctBy { it.id } }
    val appearsOn = remember(artist) { artist?.appearsOn.orEmpty().distinctBy { it.id } }
    val playlists = remember(artist) { artist?.playlists.orEmpty().distinctBy { it.id } }
    val similar = remember(artist) { artist?.similarArtists.orEmpty().distinctBy { it.id } }

    val listState = rememberLazyListState()
    // Имя в панели показываем только когда шапка ушла: пока артист виден крупно,
    // дублировать его незачем.
    val showTopBarTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 320
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LiquidSurfaces.sheet(colors.isDark))) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(32.dp))
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = error.orEmpty(), color = colors.textSecondary, fontSize = 14.sp)
            }

            else -> {
                val art = artist
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 140.dp)
                ) {
                    item {
                        ArtistHeaderWithSheet(
                            name = art?.name.orEmpty(),
                            genre = art?.genre,
                            imageUrl = (art?.image ?: art?.cover).toThumb(),
                            videoUrl = art?.editorialVideoUrl,
                            isDark = colors.isDark,
                            onPlay = {
                                if (artistTracks.isNotEmpty()) {
                                    PlayerController.play(context, artistTracks, 0)
                                }
                            },
                            onShuffle = {
                                if (artistTracks.isNotEmpty()) {
                                    PlayerController.play(context, artistTracks.shuffled(), 0)
                                }
                            }
                        )
                    }

                    if (playCount > 0) {
                        item {
                            PersonalStrip(
                                playCount = playCount,
                                favouriteTrack = favouriteTrackTitle,
                                textPrimary = LiquidSurfaces.textPrimary(colors.isDark),
                                textSecondary = LiquidSurfaces.textSecondary(colors.isDark),
                                isDark = colors.isDark
                            )
                        }
                    }

                    art?.latestRelease?.let { latest ->
                        item { SectionHeaderThemed(colors.isDark, "Latest release") }
                        item {
                            LatestReleaseCard(
                                album = latest,
                                textPrimary = LiquidSurfaces.textPrimary(colors.isDark),
                                textSecondary = LiquidSurfaces.textSecondary(colors.isDark),
                                isDark = colors.isDark,
                                onClick = { onNavigateToAlbum(latest.id) }
                            )
                        }
                    }

                    if (topSongs.isNotEmpty()) {
                        item { SectionHeaderThemed(colors.isDark, "Top songs") }
                        item {
                            Column(modifier = Modifier.padding(horizontal = LiquidMetrics.ScreenPadding)) {
                                topSongs.take(5).forEachIndexed { index, song ->
                                    TopSongRow(
                                        position = index + 1,
                                        title = song.title,
                                        subtitle = song.albumName ?: song.artist,
                                        coverUrl = song.cover.toThumb(),
                                        textPrimary = LiquidSurfaces.textPrimary(colors.isDark),
                                        textSecondary = LiquidSurfaces.textSecondary(colors.isDark),
                                        onClick = {
                                            PlayerController.play(
                                                context,
                                                topSongs.map { it.toTrack() },
                                                index
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (albums.isNotEmpty()) {
                        item { SectionHeaderThemed(colors.isDark, "Albums") }
                        item {
                            AlbumRow(albums, colors.textPrimary, colors.textSecondary, onNavigateToAlbum)
                        }
                    }

                    if (singles.isNotEmpty()) {
                        item { SectionHeaderThemed(colors.isDark, "Singles & EPs") }
                        item {
                            AlbumRow(singles, colors.textPrimary, colors.textSecondary, onNavigateToAlbum)
                        }
                    }

                    if (compilations.isNotEmpty()) {
                        item { SectionHeaderThemed(colors.isDark, "Compilations") }
                        item {
                            AlbumRow(compilations, colors.textPrimary, colors.textSecondary, onNavigateToAlbum)
                        }
                    }

                    if (liveAlbums.isNotEmpty()) {
                        item { SectionHeaderThemed(colors.isDark, "Live albums") }
                        item {
                            AlbumRow(liveAlbums, colors.textPrimary, colors.textSecondary, onNavigateToAlbum)
                        }
                    }

                    if (playlists.isNotEmpty()) {
                        item { SectionHeaderThemed(colors.isDark, "Playlists") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = LiquidMetrics.ScreenPadding),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(playlists, key = { it.id }) { playlist ->
                                    Column(modifier = Modifier.width(160.dp)) {
                                        AlbumArtImage(
                                            uri = null,
                                            coverUrl = playlist.cover.toThumb(),
                                            contentDescription = playlist.title,
                                            modifier = Modifier
                                                .size(160.dp)
                                                .clip(LiquidMetrics.CardShape)
                                                .liquidClickable(
                                                    pressedScale = LiquidMotion.PressButton,
                                                    onClick = { onNavigateToAlbum(playlist.id) }
                                                ),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = playlist.title,
                                            color = colors.textPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (similar.isNotEmpty()) {
                        item { SectionHeaderThemed(colors.isDark, "Similar artists") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = LiquidMetrics.ScreenPadding),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(similar, key = { it.id }) { other ->
                                    Column(
                                        modifier = Modifier.width(96.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        AlbumArtImage(
                                            uri = null,
                                            coverUrl = other.cover.toThumb(),
                                            contentDescription = other.displayName,
                                            modifier = Modifier
                                                .size(96.dp)
                                                .clip(CircleShape)
                                                .liquidClickable(
                                                    pressedScale = LiquidMotion.PressButton,
                                                    onClick = { onNavigateToArtist(other.id) }
                                                ),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = other.displayName,
                                            color = colors.textPrimary,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (appearsOn.isNotEmpty()) {
                        item { SectionHeaderThemed(colors.isDark, "Appears on") }
                        item {
                            AlbumRow(appearsOn, colors.textPrimary, colors.textSecondary, onNavigateToAlbum)
                        }
                    }
                }
            }
        }

        // Панель поверх шапки: кнопка «назад» нужна всегда, имя подхватывается
        // только после прокрутки.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (showTopBarTitle) {
                        LiquidSurfaces.sheet(colors.isDark)
                    } else {
                        Color.Transparent
                    }
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (showTopBarTitle) {
                            LiquidSurfaces.card(colors.isDark)
                        } else {
                            LiquidSurfaces.glassFill
                        }
                    )
                    .liquidClickable(pressedScale = LiquidMotion.PressButton, onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = if (showTopBarTitle) {
                        LiquidSurfaces.textPrimary(colors.isDark)
                    } else {
                        Color.White
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = artist?.name.orEmpty(),
                color = LiquidSurfaces.textPrimary(colors.isDark),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (showTopBarTitle) 1f else 0f)
            )
        }
    }
}

/**
 * Шапка: видео-заставка артиста, если каталог её отдал, иначе фото.
 *
 * Видео идёт без звука и по кругу — это фон, а не проигрывание: звук поверх
 * музыки недопустим, а один проход выглядел бы как сбой.
 */
@Composable
private fun ArtistHeader(
    name: String,
    genre: String?,
    imageUrl: String?,
    videoUrl: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxWidth().height(LiquidMetrics.HeaderHeight)) {
        // Фон, имя и кнопки двигаются одним куском. Параллакс здесь пробовался и
        // был убран: фон уезжал медленнее содержимого, и при прокрутке шапка
        // расползалась — фотография отдельно, подписи с кнопками отдельно.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        ) {
            if (!videoUrl.isNullOrBlank()) {
                val exoPlayer = remember(videoUrl) {
                    ExoPlayer.Builder(context).build().apply {
                        volume = 0f
                        repeatMode = Player.REPEAT_MODE_ONE
                        playWhenReady = true
                        setMediaItem(MediaItem.fromUri(videoUrl))
                        prepare()
                    }
                }
                DisposableEffect(videoUrl) {
                    onDispose { exoPlayer.release() }
                }
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            player = exoPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AlbumArtImage(
                    uri = null,
                    coverUrl = imageUrl,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Затемнение снизу: имя поверх светлого кадра иначе не читается.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.15f),
                            1f to Color.Black.copy(alpha = 0.85f)
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = LiquidMetrics.ScreenPadding,
                    end = LiquidMetrics.ScreenPadding,
                    // Ровно столько, чтобы кнопки не ушли под край наезжающего
                    // листа: больше — и блок повиснет в пустоте посреди шапки.
                    bottom = LiquidMetrics.SheetOverlap + 8.dp
                )
        ) {
            Text(
                text = name,
                color = Color.White,
                fontSize = LiquidMetrics.TitleHuge,
                fontWeight = LiquidMetrics.TitleHugeWeight,
                letterSpacing = LiquidMetrics.TitleHugeSpacing,
                lineHeight = 44.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!genre.isNullOrBlank()) {
                Text(
                    text = genre,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderButton("Play", Icons.Rounded.PlayArrow, filled = true, onClick = onPlay)
                HeaderButton("Shuffle", Icons.Rounded.Shuffle, filled = false, onClick = onShuffle)
            }
        }
    }
}

/**
 * Кнопка действия в шапке.
 *
 * Главная — сплошная белая с тёмным текстом: под ней фотография, и только
 * плотная заливка гарантирует читаемость на любом кадре. Вторая — стеклянная,
 * чтобы не спорить с главной за внимание.
 */
@Composable
private fun RowScope.HeaderButton(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (filled) Color.Black else Color.White
    Row(
        modifier = Modifier
            .weight(1f)
            .height(LiquidMetrics.ActionButtonHeight)
            .clip(CircleShape)
            .background(if (filled) Color.White else LiquidSurfaces.glassAction)
            .liquidClickable(pressedScale = LiquidMotion.PressButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = LiquidMetrics.ActionLabel,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** То, чего нет у стримингов: сколько именно ВЫ слушали этого артиста. */
@Composable
private fun PersonalStrip(
    playCount: Int,
    favouriteTrack: String?,
    textPrimary: Color,
    textSecondary: Color,
    isDark: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LiquidMetrics.ScreenPadding, vertical = 16.dp)
            .clip(LiquidMetrics.CardShape)
            .background(LiquidSurfaces.card(isDark))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            text = "You played this artist $playCount times",
            color = textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (!favouriteTrack.isNullOrBlank()) {
            Text(
                text = "Most played: $favouriteTrack",
                color = textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Шапка вместе с верхушкой листа.
 *
 * Обе части живут в одном элементе списка намеренно: если лист сдвигать
 * отдельным элементом, уезжает только он, а следующие остаются на месте — между
 * ними появляется пустая полоса. Здесь наезд рисуется внутри общего контейнера,
 * поэтому части всегда держатся друг за друга.
 */
@Composable
private fun ArtistHeaderWithSheet(
    name: String,
    genre: String?,
    imageUrl: String?,
    videoUrl: String?,
    isDark: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        ArtistHeader(
            name = name,
            genre = genre,
            imageUrl = imageUrl,
            videoUrl = videoUrl,
            onPlay = onPlay,
            onShuffle = onShuffle
        )
        SheetTop(
            isDark = isDark,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * Верхушка листа контента: наезжает на шапку и скруглена сверху.
 *
 * Приём из макета — за счёт наезда шапка воспринимается подложкой, а не первым
 * элементом списка, и переход к контенту читается без разделителя.
 */
@Composable
private fun SheetTop(isDark: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(LiquidMetrics.SheetShape)
            .background(LiquidSurfaces.sheet(isDark))
            .padding(top = 12.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 5.dp)
                .clip(CircleShape)
                .background(LiquidSurfaces.grabber(isDark))
        )
    }
}

@Composable
private fun SectionHeaderThemed(isDark: Boolean, title: String) {
    Text(
        text = title,
        color = LiquidSurfaces.textPrimary(isDark),
        fontSize = LiquidMetrics.SectionTitle,
        fontWeight = LiquidMetrics.SectionTitleWeight,
        letterSpacing = LiquidMetrics.SectionTitleSpacing,
        modifier = Modifier.padding(
            start = LiquidMetrics.ScreenPadding,
            end = LiquidMetrics.ScreenPadding,
            top = LiquidMetrics.SectionGap,
            bottom = 12.dp
        )
    )
}

@Composable
private fun TopSongRow(
    position: Int,
    title: String,
    subtitle: String,
    coverUrl: String?,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .liquidClickable(pressedScale = LiquidMotion.PressButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$position",
            color = textSecondary,
            fontSize = 15.sp,
            modifier = Modifier.width(28.dp)
        )
        AlbumArtImage(
            uri = null,
            coverUrl = coverUrl,
            contentDescription = title,
            modifier = Modifier
                .size(LiquidMetrics.TrackCoverSize)
                .clip(LiquidMetrics.CoverShapeSmall),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = textPrimary,
                fontSize = LiquidMetrics.RowTitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = textSecondary,
                fontSize = LiquidMetrics.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Свежий релиз крупно: у знакомого артиста его ищут первым делом. */
@Composable
private fun LatestReleaseCard(
    album: IcmArtistAlbum,
    textPrimary: Color,
    textSecondary: Color,
    isDark: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LiquidMetrics.ScreenPadding)
            .clip(LiquidMetrics.CardShape)
            .background(LiquidSurfaces.card(isDark))
            .liquidClickable(pressedScale = LiquidMotion.PressButton, onClick = onClick)
            .padding(LiquidMetrics.CardPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtImage(
            uri = null,
            coverUrl = album.cover.toThumb(),
            contentDescription = album.title,
            modifier = Modifier
                .size(LiquidMetrics.ReleaseCoverSize)
                .clip(LiquidMetrics.CoverShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = album.title,
                color = textPrimary,
                fontSize = LiquidMetrics.CardTitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(album.type, album.year).joinToString(" · "),
                color = textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun AlbumRow(
    albums: List<IcmArtistAlbum>,
    textPrimary: Color,
    textSecondary: Color,
    onNavigateToAlbum: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = LiquidMetrics.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(albums, key = { it.id }) { album ->
            Column(modifier = Modifier.width(150.dp)) {
                AlbumArtImage(
                    uri = null,
                    coverUrl = album.cover.toThumb(),
                    contentDescription = album.title,
                    modifier = Modifier
                        .size(150.dp)
                        .clip(LiquidMetrics.CardShape)
                        .liquidClickable(
                            pressedScale = LiquidMotion.PressButton,
                            onClick = { onNavigateToAlbum(album.id) }
                        ),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = album.title,
                    color = textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                album.year?.let {
                    Text(text = it, color = textSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}
