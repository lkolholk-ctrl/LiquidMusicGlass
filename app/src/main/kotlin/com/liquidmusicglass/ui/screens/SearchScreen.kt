package com.liquidmusicglass.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.ui.glass.GlassKit
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.IcmSearchItem
import com.liquidmusicglass.api.icm.IcmSearchSource
import com.liquidmusicglass.api.icm.IcmWaveOnboardingArtist
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.components.TrackActionsSheet
import com.liquidmusicglass.ui.components.WrapRow
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun SearchScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onOpenPlayer: () -> Unit = {}
) {
    // Режим «Видео»: 4-й сегмент источника ищет видеоклипы (Apple Music) вместо
    // треков; результаты — видео-карточки, тап открывает плеер с видео.
    var videoMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val prefs = remember { context.getSharedPreferences("search_history", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // ICM search state. viewModel(), НЕ remember{} (P1, аудит): remember создаёт
    // VM мимо ViewModelStore — onCleared никогда не зовётся, debounce-коллектор
    // из init жил вечно; каждый заход в поиск = +1 бессмертный SearchViewModel.
    val viewModel: SearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val query by viewModel.query.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()

    // Адаптив: поле поиска и сегменты остаются во всю ширину, а списки
    // результатов/истории в широком окне (альбом/планшет) центрируем узкой
    // колонкой ~600dp боковыми отступами — длинные строки во всю ширину плохи.
    val win = com.liquidmusicglass.ui.rememberWindowInfo()
    val resultsSidePad = if (win.useSideBySide)
        (((win.widthDp - 600) / 2).coerceAtLeast(0)).dp else 0.dp

    // Load categories on first composition
    LaunchedEffect(Unit) {
        viewModel.loadCategories()
    }

    fun hideKeyboard() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    // Search history — упорядоченная, СВЕЖИЕ СВЕРХУ. Старый StringSet терял
    // порядок (сортировался по алфавиту и обрезался произвольно) — мигрируем.
    // Первое чтение prefs — С ДИСКА и НЕ на main (первый кадр экрана поиска
    // не должен ждать I/O; см. полевые ANR на тапе по лупе).
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        history = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val v2 = prefs.getString("queries_v2", null)
            if (v2 != null) v2.split('\n').filter { it.isNotBlank() }
            else prefs.getStringSet("queries", emptySet())?.toList()?.take(8) ?: emptyList()
        }
    }
    fun saveQuery(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        // Дедуп без учёта регистра, свежий — наверх, максимум 8.
        val updated = (listOf(t) + history.filter { !it.equals(t, ignoreCase = true) }).take(8)
        if (updated == history) return
        history = updated
        prefs.edit()
            .putString("queries_v2", updated.joinToString("\n"))
            .remove("queries")
            .apply()
    }
    fun clearHistory() {
        prefs.edit().remove("queries_v2").remove("queries").apply()
        history = emptyList()
    }

    // Save query when search completes with results
    LaunchedEffect(searchResults) {
        if (searchResults.isNotEmpty() && query.isNotBlank()) {
            saveQuery(query)
        }
    }

    val tracks = searchResults.filter { it.isTrack }
    val albums = searchResults.filter { it.isAlbum }
    val artists = searchResults.filter { it.isArtist }

    // Долгий тап по треку → контекст-меню (в очередь / поделиться).
    var actionsTrack by remember { mutableStateOf<Track?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(LiquidTheme.colors.settingsBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            Text(
                text = "Search",
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = LiquidTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Источники — единый сегмент-контрол пилюлей (вместо трёх чипов).
            val segBg = if (LiquidTheme.colors.isDark) Color(0xFF1A1A1A) else Color(0xFFF2F2F7)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(segBg)
                    .padding(4.dp)
            ) {
                SourceSegment(
                    text = "Apple Music",
                    selected = !videoMode && selectedSource == IcmSearchSource.APPLE,
                    modifier = Modifier.weight(1f),
                    onClick = { videoMode = false; viewModel.setSource(IcmSearchSource.APPLE) }
                )
                SourceSegment(
                    text = "VK",
                    selected = !videoMode && selectedSource == IcmSearchSource.VK,
                    modifier = Modifier.weight(1f),
                    onClick = { videoMode = false; viewModel.setSource(IcmSearchSource.VK) }
                )
                SourceSegment(
                    text = "All",
                    selected = !videoMode && selectedSource == IcmSearchSource.ALL,
                    modifier = Modifier.weight(1f),
                    onClick = { videoMode = false; viewModel.setSource(IcmSearchSource.ALL) }
                )
                SourceSegment(
                    text = "Video",
                    selected = videoMode,
                    modifier = Modifier.weight(1f),
                    onClick = { videoMode = true }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search field — пилюля с подсветкой при фокусе + Cancel рядом.
            val isDark = LiquidTheme.colors.isDark
            val searchBarBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF2F2F7)
            val accentColor = AppleRed
            var searchFocused by remember { mutableStateOf(false) }
            val focusBorder by animateColorAsState(
                targetValue = if (searchFocused) accentColor.copy(alpha = 0.65f) else Color.Transparent,
                animationSpec = tween(200),
                label = "focusBorder"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(searchBarBg)
                    .border(1.5.dp, focusBorder, CircleShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = LiquidTheme.colors.iconMuted,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { viewModel.setQuery(it) },
                    textStyle = TextStyle(
                        color = LiquidTheme.colors.textPrimary,
                        fontSize = 16.sp
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(accentColor),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { searchFocused = it.isFocused },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = {
                            hideKeyboard()
                            viewModel.searchNow()
                        }
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = "Songs, artists, albums",
                                    color = LiquidTheme.colors.textTertiary,
                                    fontSize = 16.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f))
                            .liquidClickable(pressedScale = LiquidMotion.PressIcon) {
                                viewModel.clearQuery()
                                hideKeyboard()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = LiquidTheme.colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Cancel — появляется при фокусе/вводе, сбрасывает поиск.
            AnimatedVisibility(visible = searchFocused || query.isNotEmpty()) {
                Text(
                    text = "Cancel",
                    color = accentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .liquidClickable(pressedScale = LiquidMotion.PressButton) {
                            viewModel.clearQuery()
                            hideKeyboard()
                        }
                )
            }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
              if (videoMode) {
                ClipResultsSection(query = query, onOpenPlayer = onOpenPlayer)
              } else {
                // ─── IDLE STATE: Categories + History ───
                androidx.compose.animation.AnimatedVisibility(
                    visible = query.isBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = resultsSidePad, end = resultsSidePad, bottom = 178.dp)
                    ) {
                        if (categories.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Browse",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    color = LiquidTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                )
                            }
                            val chunkedCategories = categories.chunked(2)
                            itemsIndexed(
                                items = chunkedCategories,
                                key = { index, pair -> "cat_${index}_${pair.first().id}" }
                            ) { _, pair ->
                                Row(
                                    modifier = Modifier.animateItem().fillMaxWidth().padding(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    pair.forEach { category ->
                                        CategoryCard(
                                            category = category,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                hideKeyboard()
                                                onNavigateToArtist(category.id)
                                            }
                                        )
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }

                        if (history.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Recent Searches",
                                        color = LiquidTheme.colors.sectionLabel,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Clear",
                                        color = AppleRed,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.liquidClickable(pressedScale = LiquidMotion.PressButton) { clearHistory() }
                                    )
                                }
                            }
                            // Чипы-пилюли в несколько рядов: компактнее списка,
                            // тап — искать сразу. WrapRow (свой Layout), НЕ
                            // androidx FlowRow — его сигнатура плавает между
                            // версиями foundation → NoSuchMethodError на рендере
                            // (поймано полевым дампом краша поиска).
                            item(key = "hist_chips") {
                                WrapRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                ) {
                                    history.forEach { item ->
                                        HistoryChip(
                                            query = item,
                                            onClick = {
                                                hideKeyboard()
                                                viewModel.setQuery(item)
                                                viewModel.searchNow()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ─── ACTIVE SEARCH: Results ───
                androidx.compose.animation.AnimatedVisibility(
                    visible = query.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                        when {
                            isLoading -> {
                                // Шиммер-скелетоны: сразу видно структуру будущих
                                // результатов, а не крутилку в пустоте.
                                SearchSkeleton()
                            }
                            error != null -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 24.dp)
                                            .clip(RoundedCornerShape(28.dp))
                                            .background(if (isDark) Color(0xFF1A1A1A) else Color(0xFFF2F2F7))
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Something went wrong",
                                            color = LiquidTheme.colors.textPrimary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = error ?: "Unknown error",
                                            color = LiquidTheme.colors.textTertiary,
                                            fontSize = 13.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "Retry",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .background(AppleRed)
                                                .liquidClickable(pressedScale = LiquidMotion.PressButton) { viewModel.searchNow() }
                                                .padding(horizontal = 24.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                            else -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    contentPadding = PaddingValues(start = resultsSidePad, end = resultsSidePad, bottom = 178.dp)
                                ) {
                                    // Artists section
                                    if (artists.isNotEmpty()) {
                                        item(key = "artists_label") {
                                            SearchSectionLabel("Artists")
                                        }
                                        item(key = "artists_row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 20.dp),
                                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                itemsIndexed(
                                                    items = artists,
                                                    key = { index, artist -> "artist_${index}_${artist.id}" }
                                                ) { _, artist ->
                                                    ArtistChip(
                                                        artist = artist,
                                                        onClick = {
                                                            hideKeyboard()
                                                            onNavigateToArtist(artist.id)
                                                        }
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                        }
                                    }

                                    // Albums section
                                    if (albums.isNotEmpty()) {
                                        item(key = "albums_label") {
                                            SearchSectionLabel("Albums")
                                        }
                                        item(key = "albums_row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 20.dp),
                                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                                            ) {
                                                itemsIndexed(
                                                    items = albums,
                                                    key = { index, album -> "album_${index}_${album.id}" }
                                                ) { _, album ->
                                                    AlbumCard(
                                                        album = album,
                                                        onClick = {
                                                            hideKeyboard()
                                                            onNavigateToAlbum(album.id)
                                                        }
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                        }
                                    }

                                    // Tracks section
                                    if (tracks.isNotEmpty()) {
                                        item(key = "tracks_label") {
                                            SearchSectionLabel("Songs")
                                        }
                                        val playableTracks = tracks.map { it.toTrack() }
                                        itemsIndexed(
                                            items = tracks,
                                            key = { index, track -> "track_${index}_${track.id}" }
                                        ) { _, item ->
                                            SearchResultRow(
                                                title = item.title,
                                                subtitle = item.displayArtist,
                                                icon = Icons.Rounded.MusicNote,
                                                coverUrl = item.cover,
                                                isExplicit = item.isExplicit,
                                                isCustom = item.isCustom,
                                                onClick = {
                                                    hideKeyboard()
                                                    val startIdx = playableTracks.indexOfFirst { it.id == item.id }
                                                        .coerceAtLeast(0)
                                                    PlayerController.playFromList(
                                                        context = context,
                                                        tracks = playableTracks,
                                                        startIndex = startIdx,
                                                        autoRefillType = "search",
                                                        autoRefillId = query,
                                                        autoRefillName = query
                                                    )
                                                },
                                                onLongClick = {
                                                    hideKeyboard()
                                                    actionsTrack = playableTracks
                                                        .firstOrNull { it.id == item.id }
                                                }
                                            )
                                        }
                                    }

                                    if (tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()) {
                                        item {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 20.dp)
                                                    .padding(top = 24.dp)
                                                    .clip(RoundedCornerShape(28.dp))
                                                    .background(if (isDark) Color(0xFF1A1A1A) else Color(0xFFF2F2F7))
                                                    .padding(24.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = "Nothing found",
                                                    color = LiquidTheme.colors.textPrimary,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = "No results for \"$query\"",
                                                    color = LiquidTheme.colors.textTertiary,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        }
                                    }

                                    item { Spacer(modifier = Modifier.height(200.dp)) }
                                }
                            }
                        }
                }
              }
            }
        }

        // Контекст-меню трека (долгий тап по строке результата).
        actionsTrack?.let { t ->
            TrackActionsSheet(track = t, onDismiss = { actionsTrack = null })
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  UI Components
// ═══════════════════════════════════════════════════════════

@Composable
private fun CategoryCard(
    category: IcmWaveOnboardingArtist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val gradientColors = remember(category.id) {
        // Generate consistent gradient from category id hash
        val hash = category.id.hashCode()
        val hue1 = (hash % 360).let { if (it < 0) it + 360 else it }
        val hue2 = ((hash * 31) % 360).let { if (it < 0) it + 360 else it }
        listOf(
            android.graphics.Color.HSVToColor(floatArrayOf(hue1.toFloat(), 0.7f, 0.4f)),
            android.graphics.Color.HSVToColor(floatArrayOf(hue2.toFloat(), 0.8f, 0.25f))
        ).map { Color(it) }
    }

    Box(
        modifier = modifier
            .height(100.dp)
            .clip(RoundedCornerShape(24.dp))   // большой радиус, в тон карточкам
            .background(Brush.linearGradient(gradientColors))
            .liquidClickable { onClick() }
            .padding(16.dp)
    ) {
        // Artist image (small, bottom-right)
        if (category.image != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(category.image)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .align(Alignment.BottomEnd)
            )
        }
        // Category name
        Text(
            text = category.name,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Composable
private fun ArtistChip(
    artist: IcmSearchItem,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .liquidClickable { onClick() }
    ) {
        if (artist.cover != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artist.cover)
                    .crossfade(true)
                    .build(),
                contentDescription = artist.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (LiquidTheme.colors.isDark) Color(0xFF2A2A2A) else Color(0xFFF2F2F7)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = LiquidTheme.colors.iconMuted,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.title.takeIf { it.isNotBlank() } ?: artist.displayArtist,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun AlbumCard(
    album: IcmSearchItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .liquidClickable { onClick() }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(album.cover)
                .crossfade(true)
                .build(),
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = album.displayArtist,
            color = LiquidTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Чип-пилюля недавнего запроса (тап = искать сразу). */
@Composable
private fun HistoryChip(
    query: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (LiquidTheme.colors.isDark) Color(0xFF1A1A1A) else Color(0xFFF2F2F7))
            .liquidClickable(pressedScale = LiquidMotion.PressButton) { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = null,
            tint = LiquidTheme.colors.iconMuted,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = query,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Шиммер-скелетоны результатов: пульсирующие пилюли на месте будущих строк. */
@Composable
private fun SearchSkeleton() {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
        .animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(650),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "skeletonPulse"
        )
    val base = if (LiquidTheme.colors.isDark) Color(0xFF1A1A1A) else Color(0xFFEAEAEF)
    Column(modifier = Modifier.fillMaxSize()) {
        // Плашка на месте заголовка секции
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .size(width = 110.dp, height = 20.dp)
                .clip(RoundedCornerShape(50))
                .background(base.copy(alpha = pulse))
        )
        repeat(7) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(50))
                    .background(base.copy(alpha = pulse))
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    coverUrl: String?,
    isExplicit: Boolean = false,
    isCustom: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(50))   // строки-пилюли, как в настройках
            .background(if (LiquidTheme.colors.isDark) Color(0xFF1A1A1A) else Color(0xFFF2F2F7))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (LiquidTheme.colors.isDark) Color(0xFF2A2A2A) else Color(0xFFF2F2F7)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LiquidTheme.colors.iconMuted,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = LiquidTheme.colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isExplicit) {
                    Spacer(modifier = Modifier.width(6.dp))
                    GlassKit.ExplicitBadge()
                }
                if (isCustom) {
                    Spacer(modifier = Modifier.width(6.dp))
                    GlassKit.VerifiedBadge()
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Сегмент источника внутри единой пилюли (Apple Music / VK / All). */
@Composable
private fun SourceSegment(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (selected) AppleRed else Color.Transparent,
        animationSpec = tween(220),
        label = "segBg"
    )
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .liquidClickable(pressedScale = LiquidMotion.PressButton) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else LiquidTheme.colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun SearchSectionLabel(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        color = LiquidTheme.colors.textPrimary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

// ═══════════════════════════════════════════════════════════
//  Встроенный поиск видеоклипов (сегмент «Video» в обычном поиске)
// ═══════════════════════════════════════════════════════════

@Composable
private fun ClipResultsSection(
    query: String,
    onOpenPlayer: () -> Unit,
) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var results by remember { mutableStateOf<List<com.liquidmusicglass.api.icm.IcmClipItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Дебаунс поиска клипов по общему полю запроса.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); error = null; loading = false; return@LaunchedEffect }
        error = null; loading = true
        kotlinx.coroutines.delay(350)
        val r = com.liquidmusicglass.api.icm.IcmApi.getInstance().searchClips(q)
        loading = false
        r.onSuccess { results = it.results }
            .onFailure { error = com.liquidmusicglass.api.icm.icmUserMessage(it); results = emptyList() }
    }

    fun openClip(clip: com.liquidmusicglass.api.icm.IcmClipItem) {
        if (resolving != null) return
        resolving = clip.id; error = null
        scope.launch {
            val r = com.liquidmusicglass.api.icm.IcmApi.getInstance().resolveClipStreamUrl(clip.id)
            resolving = null
            r.onSuccess { url ->
                PlayerController.playClip(context, url, clip.id, clip.title, clip.artist, clip.thumbnail)
                onOpenPlayer()
            }.onFailure { error = com.liquidmusicglass.api.icm.icmUserMessage(it) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        error?.let {
            Text(it, color = Color(0xFFFC3C44), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        if (loading && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF88C088))
            }
        } else if (results.isEmpty() && query.trim().length >= 2 && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos found", color = lc.textTertiary, fontSize = 15.sp)
            }
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 178.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                androidx.compose.foundation.lazy.grid.items(results, key = { it.id }) { clip ->
                    ClipResultCard(clip, resolving == clip.id) { openClip(clip) }
                }
            }
        }
    }
}

@Composable
private fun ClipResultCard(
    clip: com.liquidmusicglass.api.icm.IcmClipItem,
    isResolving: Boolean,
    onClick: () -> Unit,
) {
    val lc = LiquidTheme.colors
    Column(modifier = Modifier.liquidClickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA)),
            contentAlignment = Alignment.Center
        ) {
            if (!clip.thumbnail.isNullOrBlank()) {
                AsyncImage(model = clip.thumbnail, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                if (isResolving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(clip.title, color = lc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(clip.artist, color = lc.textSecondary, fontSize = 12.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
