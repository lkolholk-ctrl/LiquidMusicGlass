package com.liquidmusicglass.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun SearchScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val prefs = remember { context.getSharedPreferences("search_history", Context.MODE_PRIVATE) }

    val viewModel = remember { SearchViewModel() }
    val query by viewModel.query.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()

    // Load categories on first composition
    LaunchedEffect(Unit) {
        viewModel.loadCategories()
    }

    fun hideKeyboard() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    // Search history
    var history by remember {
        mutableStateOf<List<String>>(
            prefs.getStringSet("queries", emptySet())?.toList()?.sortedDescending() ?: emptyList()
        )
    }
    fun saveQuery(q: String) {
        if (q.isBlank()) return
        val current = prefs.getStringSet("queries", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(q)
        val trimmed = if (current.size > 20) current.drop(current.size - 20).toSet() else current
        prefs.edit().putStringSet("queries", trimmed).apply()
        history = trimmed.toList().sortedDescending()
    }
    fun clearHistory() {
        prefs.edit().remove("queries").apply()
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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

            // Source selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SourceChip(
                    text = "Apple Music",
                    selected = selectedSource == IcmSearchSource.APPLE,
                    onClick = { viewModel.setSource(IcmSearchSource.APPLE) }
                )
                SourceChip(
                    text = "VK",
                    selected = selectedSource == IcmSearchSource.VK,
                    onClick = { viewModel.setSource(IcmSearchSource.VK) }
                )
                SourceChip(
                    text = "All",
                    selected = selectedSource == IcmSearchSource.ALL,
                    onClick = { viewModel.setSource(IcmSearchSource.ALL) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search field — solid dark background
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A1A1A))
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
                    cursorBrush = SolidColor(AppleRed),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { hideKeyboard(); viewModel.searchNow() }
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
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { viewModel.clearQuery(); hideKeyboard() }
                            ),
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

            Spacer(modifier = Modifier.height(16.dp))

            // ─── IDLE STATE: Categories + History ───
            AnimatedVisibility(
                visible = query.isBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    // Categories grid (popular artists as genres)
                    if (categories.isNotEmpty()) {
                        Text(
                            text = "Browse",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = LiquidTheme.colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        // 2-column grid of category cards
                        val chunkedCategories = categories.chunked(2)
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp)
                        ) {
                            items(chunkedCategories, key = { "cat_${it.first().id}" }) { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    pair.forEach { category ->
                                        CategoryCard(
                                            category = category,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                hideKeyboard()
                                                // Navigate to artist screen instead of searching
                                                onNavigateToArtist(category.id)
                                            }
                                        )
                                    }
                                    // Fill empty slot if odd number
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            item { Spacer(modifier = Modifier.height(16.dp)) }
                        }
                    }

                    // Search history
                    if (history.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
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
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { clearHistory() }
                                )
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(history, key = { "hist_$it" }) { item ->
                                HistoryRow(
                                    query = item,
                                    onClick = {
                                        hideKeyboard()
                                        viewModel.setQuery(item)
                                        viewModel.searchNow()
                                    }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(200.dp)) }
                        }
                    } else if (categories.isEmpty()) {
                        // Empty state
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = LiquidTheme.colors.textTertiary,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Search ICM Music",
                                    color = LiquidTheme.colors.textTertiary,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // ─── ACTIVE SEARCH: Results ───
            AnimatedVisibility(
                visible = query.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = AppleRed,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    error != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Error",
                                    color = AppleRed,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = error ?: "Unknown error",
                                    color = LiquidTheme.colors.textTertiary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Artists section
                            if (artists.isNotEmpty()) {
                                item(key = "artists_label") {
                                    SearchSectionLabel("Artists")
                                }
                                // Artists as horizontal row of circular avatars
                                item(key = "artists_row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        items(artists, key = { "artist_${it.id}" }) { artist ->
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
                                // Albums as horizontal row of square cards
                                item(key = "albums_row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        items(albums, key = { "album_${it.id}" }) { album ->
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
                                items(tracks, key = { "track_${it.id}" }) { item ->
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
                                        }
                                    )
                                }
                            }

                            // No results
                            if (tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 60.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No results for \"$query\"",
                                            color = LiquidTheme.colors.textTertiary,
                                            fontSize = 16.sp
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
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(gradientColors))
            .clickable(onClick = onClick)
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
            .clickable(onClick = onClick)
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
                    .background(Color(0xFF2A2A2A)),
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
            .clickable(onClick = onClick)
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

@Composable
private fun HistoryRow(
    query: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = null,
            tint = LiquidTheme.colors.iconMuted,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = query,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = LiquidTheme.colors.iconMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    coverUrl: String?,
    isExplicit: Boolean = false,
    isCustom: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
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
                    .background(Color(0xFF2A2A2A)),
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

@Composable
private fun SourceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) AppleRed else Color(0xFF1A1A1A)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else LiquidTheme.colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
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
