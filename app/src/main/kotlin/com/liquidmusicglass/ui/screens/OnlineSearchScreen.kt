package com.liquidmusicglass.ui.screens

import android.content.Context
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.liquidmusicglass.api.icm.IcmApi
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.toTrack
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Экран онлайн-поиска через ICM Music Partner API.
 * Поиск по каталогу Apple Music + VK (90M+ треков).
 */
@Composable
fun OnlineSearchScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val screenBackdrop = rememberLayerBackdrop()

    var query by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isInitialized by IcmRepository.isInitialized.collectAsState()

    // Debounce search: 300ms after last keystroke
    var searchJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(query) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults = emptyList()
            errorMessage = null
            return@LaunchedEffect
        }
        searchJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            delay(300)
            if (!isInitialized) {
                errorMessage = "ICM API not initialized. Set API key in settings."
                return@launch
            }
            isSearching = true
            errorMessage = null
            val results = IcmRepository.searchTracks(query)
            searchResults = results
            isSearching = false
            if (results.isEmpty()) {
                errorMessage = "No results for \"$query\""
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(LiquidTheme.colors.screenBackground)
                .layerBackdrop(screenBackdrop)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            Text(
                text = "Online Music",
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = LiquidTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Text(
                text = "Search 90M+ tracks via ICM",
                fontSize = 13.sp,
                color = LiquidTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Search field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(44.dp)
                    .drawBackdrop(
                        backdrop = screenBackdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(4.dp.toPx())
                            lens(20.dp.toPx(), 32.dp.toPx(), chromaticAberration = true)
                        },
                        highlight = {
                            Highlight.Ambient.copy(alpha = 0.5f)
                        },
                        shadow = {
                            Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.12f))
                        },
                        innerShadow = {
                            InnerShadow(radius = 3.dp, alpha = 0.2f)
                        },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = 0.02f))
                            drawRect(
                                color = Color.White.copy(alpha = 0.20f),
                                style = Stroke(width = 0.8.dp.toPx())
                            )
                        }
                    )
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
                    cursorBrush = SolidColor(Color(0xFFFC3C44)),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = "Search artists, songs, albums...",
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

            if (isSearching) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Searching...",
                        color = LiquidTheme.colors.textTertiary,
                        fontSize = 16.sp
                    )
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = LiquidTheme.colors.textTertiary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMessage!!,
                            color = LiquidTheme.colors.textTertiary,
                            fontSize = 14.sp
                        )
                        if (!isInitialized) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Get your key at byicloud.online/partners",
                                color = LiquidTheme.colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else if (searchResults.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)
                ) {
                    items(searchResults, key = { "icm_${it.id}" }) { track ->
                        OnlineTrackRow(
                            track = track,
                            backdrop = screenBackdrop,
                            context = context,
                            onClick = { playOnlineTrack(context, track) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(200.dp)) }
                }
            } else if (query.isBlank()) {
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
                            text = "Search online music",
                            color = LiquidTheme.colors.textTertiary,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Powered by ICM Music (byicloud.online)",
                            color = LiquidTheme.colors.textSecondary.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineTrackRow(
    track: Track,
    backdrop: LayerBackdrop,
    context: Context,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(4.dp.toPx())
                    vibrancy()
                    lens(
                        refractionHeight = 18.dp.toPx(),
                        refractionAmount = 28.dp.toPx(),
                        chromaticAberration = true
                    )
                },
                highlight = { Highlight.Ambient.copy(alpha = 0.3f) },
                shadow = { Shadow(radius = 3.dp, color = Color.Black.copy(alpha = 0.08f)) },
                innerShadow = { InnerShadow(radius = 2.dp, alpha = 0.12f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            AlbumArtImage(
                uri = track.albumArtUri,
                coverUrl = track.coverUrl,
                audioFileUri = track.uri,
                albumId = track.albumId,
                coverUrl = track.coverUrl,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${track.artist} · ${track.albumName}",
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val minutes = (track.durationMs / 1000 / 60).toInt()
        val seconds = ((track.durationMs / 1000) % 60).toInt()
        Text(
            text = "$minutes:${seconds.toString().padStart(2, '0')}",
            color = LiquidTheme.colors.textTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * Проиграть онлайн-трек: получаем подписанный URL и передаём в плеер.
 */
private fun playOnlineTrack(context: Context, track: Track) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        try {
            val streamUrl = IcmRepository.getStreamUrl(track.id)
            if (streamUrl != null) {
                PlayerController.playRadioStream(context, streamUrl, track.title)
            }
        } catch (_: Exception) { }
    }
}
