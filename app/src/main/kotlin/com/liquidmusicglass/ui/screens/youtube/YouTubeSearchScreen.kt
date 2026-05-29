package com.liquidmusicglass.ui.screens.youtube

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liquidmusicglass.api.youtube.YouTubeMusicRepository
import com.liquidmusicglass.api.youtube.YtSearchFilter
import com.liquidmusicglass.api.youtube.YtTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

private val YouTubeRed = Color(0xFFFF0000)

@Composable
fun YouTubeSearchScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<YtTrack>>(emptyList()) }
    var history by remember {
        mutableStateOf<List<String>>(
            context.getSharedPreferences("yt_search_history", Context.MODE_PRIVATE)
                .getStringSet("queries", emptySet())?.toList()?.sortedDescending() ?: emptyList()
        )
    }

    val ytRepo = remember { YouTubeMusicRepository.getInstance() }

    fun hideKeyboard() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun saveQuery(q: String) {
        if (q.isBlank()) return
        val prefs = context.getSharedPreferences("yt_search_history", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("queries", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(q)
        val trimmed = if (current.size > 20) current.drop(current.size - 20).toSet() else current
        prefs.edit().putStringSet("queries", trimmed).apply()
        history = trimmed.toList().sortedDescending()
    }

    fun clearHistory() {
        context.getSharedPreferences("yt_search_history", Context.MODE_PRIVATE)
            .edit().remove("queries").apply()
        history = emptyList()
    }

    fun performSearch(q: String) {
        if (q.isBlank()) return
        isLoading = true
        error = null
        scope.launch {
            try {
                val result = ytRepo.search(q, YtSearchFilter.SONGS)
                if (result.isSuccess) {
                    results = result.getOrThrow()
                    saveQuery(q)
                } else {
                    error = result.exceptionOrNull()?.message ?: "Search failed"
                }
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
            } finally {
                isLoading = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LiquidTheme.colors.settingsBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "YouTube Music",
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = YouTubeRed
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(YouTubeRed.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "FREE",
                        fontSize = 11.sp,
                        color = YouTubeRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search field
            val isDark = LiquidTheme.colors.isDark
            val searchBarBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF2F2F7)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(searchBarBg)
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
                    onValueChange = { query = it },
                    textStyle = TextStyle(
                        color = LiquidTheme.colors.textPrimary,
                        fontSize = 16.sp
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(YouTubeRed),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { hideKeyboard(); performSearch(query) }
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = "Search YouTube Music...",
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
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { query = ""; results = emptyList() }
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

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Idle state — history
                androidx.compose.animation.AnimatedVisibility(
                    visible = query.isBlank() && results.isEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
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
                                        color = YouTubeRed,
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
                            itemsIndexed(history) { _, item ->
                                HistoryRow(
                                    query = item,
                                    onClick = {
                                        query = item
                                        performSearch(item)
                                    }
                                )
                            }
                        } else {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 60.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = LiquidTheme.colors.textTertiary,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "Search YouTube Music",
                                            color = LiquidTheme.colors.textTertiary,
                                            fontSize = 16.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Free unlimited music streaming",
                                            color = LiquidTheme.colors.textTertiary.copy(alpha = 0.7f),
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Loading
                androidx.compose.animation.AnimatedVisibility(
                    visible = isLoading,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = YouTubeRed, modifier = Modifier.size(32.dp))
                    }
                }

                // Results
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isLoading && results.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        itemsIndexed(results) { _, item ->
                            YtSearchResultRow(
                                item = item,
                                onClick = {
                                    hideKeyboard()
                                    val track = item.toEngineTrack()
                                    PlayerController.playNext(track, context)
                                },
                                onPlayRadio = {
                                    hideKeyboard()
                                    scope.launch {
                                        val radioResult = ytRepo.getRadioQueue(item.videoId)
                                        if (radioResult.isSuccess) {
                                            val ytTracks = radioResult.getOrThrow()
                                            val resolvedTracks = ytTracks.map { ytTrack ->
                                                ytTrack.toEngineTrack()
                                            }
                                            if (resolvedTracks.isNotEmpty()) {
                                                PlayerController.playFromList(
                                                    context = context,
                                                    tracks = resolvedTracks,
                                                    startIndex = 0,
                                                    autoRefillType = "YOUTUBE_RADIO",
                                                    autoRefillId = item.videoId,
                                                    autoRefillName = "${item.title} Radio"
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // Error
                androidx.compose.animation.AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Error",
                                color = YouTubeRed,
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
            }
        }
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = LiquidTheme.colors.iconMuted,
            modifier = Modifier.size(18.dp)
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
    }
}

@Composable
private fun YtSearchResultRow(
    item: YtTrack,
    onClick: () -> Unit,
    onPlayRadio: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1C1C1E))
        ) {
            val thumbUrl = item.thumbnail
            if (thumbUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = LiquidTheme.colors.iconMuted,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.artist,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.durationSeconds != null && item.durationSeconds > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                val mins = item.durationSeconds / 60
                val secs = item.durationSeconds % 60
                Text(
                    text = "$mins:${secs.toString().padStart(2, '0')}",
                    color = LiquidTheme.colors.textTertiary,
                    fontSize = 12.sp
                )
            }
        }

        // Radio button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(YouTubeRed.copy(alpha = 0.12f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayRadio
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Radio",
                color = YouTubeRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
