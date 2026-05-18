package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.IcmSearchItem
import com.liquidmusicglass.api.icm.IcmSearchSource
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
fun SearchScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE) }

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<IcmSearchItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var selectedSource by remember { mutableStateOf(IcmSearchSource.APPLE) }
    val searchMutex = remember { Mutex() }

    // Load search history
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

    // Debounce search: 500ms after user stops typing + mutex serialization
    LaunchedEffect(query, selectedSource) {
        if (query.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(500)
        isLoading = true
        lastError = null
        searchMutex.withLock {
            try {
                val result = IcmRepository.searchAll(query, source = selectedSource)
                searchResults = result?.items ?: emptyList()
                if (result != null) {
                    saveQuery(query)
                } else {
                    lastError = IcmRepository.lastError.value ?: "Search failed"
                }
            } catch (e: Exception) {
                lastError = e.message
            } finally {
                isLoading = false
            }
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
                    onClick = { selectedSource = IcmSearchSource.APPLE }
                )
                SourceChip(
                    text = "VK",
                    selected = selectedSource == IcmSearchSource.VK,
                    onClick = { selectedSource = IcmSearchSource.VK }
                )
                SourceChip(
                    text = "All",
                    selected = selectedSource == IcmSearchSource.ALL,
                    onClick = { selectedSource = IcmSearchSource.ALL }
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
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = TextStyle(
                        color = LiquidTheme.colors.textPrimary,
                        fontSize = 16.sp
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Color(0xFFFC3C44)),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
                                onClick = { query = "" }
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

            if (query.isBlank()) {
                // Show search history
                if (history.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
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
                                    color = Color(0xFFFC3C44),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { clearHistory() }
                                    )
                                )
                            }
                        }
                        items(history, key = { "hist_$it" }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1A1A1A))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { query = item }
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
                                    text = item,
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
                        item { Spacer(modifier = Modifier.height(200.dp)) }
                    }
                } else {
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
            } else if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFFC3C44),
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else if (lastError != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Error",
                            color = Color(0xFFFC3C44),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = lastError ?: "Unknown error",
                            color = LiquidTheme.colors.textTertiary,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Artists
                    if (artists.isNotEmpty()) {
                        item(key = "artists_label") {
                            SearchSectionLabel("Artists")
                        }
                        items(artists, key = { "artist_${it.id}" }) { item ->
                            SearchResultRow(
                                title = item.title.takeIf { it.isNotBlank() } ?: item.displayArtist,
                                subtitle = "Artist",
                                icon = Icons.Rounded.Person,
                                coverUrl = item.cover,
                                onClick = { onNavigateToArtist(item.id) }
                            )
                        }
                    }

                    // Albums
                    if (albums.isNotEmpty()) {
                        item(key = "albums_label") {
                            SearchSectionLabel("Albums")
                        }
                        items(albums, key = { "album_${it.id}" }) { item ->
                            SearchResultRow(
                                title = item.title,
                                subtitle = item.displayArtist,
                                icon = Icons.Rounded.Album,
                                coverUrl = item.cover,
                                onClick = { onNavigateToAlbum(item.id) }
                            )
                        }
                    }

                    // Tracks
                    if (tracks.isNotEmpty()) {
                        item(key = "tracks_label") {
                            SearchSectionLabel("Songs")
                        }
                        items(tracks, key = { "track_${it.id}" }) { item ->
                            val track = item.toTrack()
                            SearchResultRow(
                                title = item.title,
                                subtitle = item.displayArtist,
                                icon = Icons.Rounded.MusicNote,
                                coverUrl = item.cover,
                                onClick = {
                                    PlayerController.playNext(track, context)
                                }
                            )
                        }
                    }

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
                if (selected) Color(0xFFFC3C44) else Color(0xFF1A1A1A)
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
        color = LiquidTheme.colors.sectionLabel,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    coverUrl: String? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(64.dp)
            .clip(shape)
            .background(Color(0xFF1A1A1A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AlbumArtImage(
                uri = null,
                coverUrl = coverUrl,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LiquidTheme.colors.iconMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}
