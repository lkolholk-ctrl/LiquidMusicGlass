package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.liquidmusicglass.engine.RadioApi
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun RadioScreen(
    onBack: () -> Unit,
    onPlayStation: (RadioApi.RadioStation) -> Unit,
    currentStationId: String? = null
) {
    val backdrop = rememberLayerBackdrop()
    val lc = LiquidTheme.colors
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var stations by remember { mutableStateOf<List<RadioApi.RadioStation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedTag by remember { mutableStateOf<String?>(null) }

    val quickTags = listOf("pop", "rock", "electronic", "hip hop", "jazz", "chill", "dance", "rnb", "classical", "lounge")
    val countryTags = listOf("Russia", "USA", "UK", "Germany", "France", "Japan")

    // Load top stations on start
    LaunchedEffect(Unit) {
        isLoading = true
        stations = RadioApi.topStations(40)
        isLoading = false
    }

    fun search(query: String) {
        scope.launch {
            isLoading = true
            stations = if (query.isBlank()) RadioApi.topStations(40)
            else RadioApi.searchByName(query, 40)
            isLoading = false
            selectedTag = null
        }
    }

    fun loadTag(tag: String) {
        scope.launch {
            isLoading = true
            selectedTag = tag
            stations = RadioApi.searchByTag(tag, 40)
            isLoading = false
        }
    }

    fun loadCountry(country: String) {
        scope.launch {
            isLoading = true
            selectedTag = country
            stations = RadioApi.searchByCountry(country, 40)
            isLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(lc.screenBackground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
        ) {
            // ── Header ──
            item {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Back
                    Box(
                        modifier = Modifier.size(40.dp)
                            .drawBackdrop(backdrop = backdrop, shape = { Capsule() },
                                effects = { vibrancy(); blur(4.dp.toPx()); lens(16.dp.toPx(), 24.dp.toPx(), chromaticAberration = true) },
                                highlight = { Highlight.Ambient },
                                shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(0.12f)) },
                                innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                                onDrawSurface = { drawRect(Color.White.copy(0.03f)); drawRect(Color.White.copy(0.22f), style = Stroke(1.dp.toPx())) }
                            ).clip(CircleShape)
                            .clickable(remember { MutableInteractionSource() }, null) { onBack() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp)) }

                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Rounded.Radio, null, tint = AppleRed, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Radio", color = lc.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Search ──
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                        .drawBackdrop(backdrop = backdrop, shape = { RoundedCornerShape(16.dp) },
                            effects = { vibrancy(); blur(4.dp.toPx()); lens(20.dp.toPx(), 32.dp.toPx(), chromaticAberration = true) },
                            highlight = { Highlight.Ambient },
                            shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(0.1f)) },
                            onDrawSurface = { drawRect(Color.White.copy(0.03f)); drawRect(Color.White.copy(0.20f), style = Stroke(1.dp.toPx())) }
                        ).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, tint = lc.iconMuted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it; if (it.length > 2) search(it) },
                            singleLine = true,
                            textStyle = TextStyle(color = lc.textPrimary, fontSize = 16.sp),
                            cursorBrush = SolidColor(AppleRed),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (searchQuery.isEmpty()) Text("Search stations...", color = lc.textTertiary, fontSize = 16.sp)
                                inner()
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Genre chips ──
            item {
                Text("GENRES", color = lc.sectionLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickTags) { tag ->
                        val isSelected = selectedTag == tag
                        Box(
                            modifier = Modifier.height(34.dp)
                                .drawBackdrop(backdrop = backdrop, shape = { Capsule() },
                                    effects = { vibrancy(); blur(4.dp.toPx()) },
                                    highlight = { if (isSelected) Highlight.Default.copy(alpha = 0.7f) else Highlight.Ambient.copy(alpha = 0.3f) },
                                    shadow = { if (isSelected) Shadow(radius = 4.dp, color = AppleRed.copy(0.3f)) else Shadow(radius = 2.dp, color = Color.Black.copy(0.08f)) },
                                    onDrawSurface = {
                                        if (isSelected) drawRect(AppleRed.copy(0.8f))
                                        else { drawRect(Color.White.copy(0.03f)); drawRect(Color.White.copy(0.15f), style = Stroke(1.dp.toPx())) }
                                    }
                                ).clip(RoundedCornerShape(50))
                                .clickable(remember { MutableInteractionSource() }, null) { loadTag(tag) }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(tag.replaceFirstChar { it.uppercase() },
                                color = if (isSelected) Color.White else lc.textSecondary,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Country chips ──
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(countryTags) { country ->
                        val isSelected = selectedTag == country
                        Box(
                            modifier = Modifier.height(34.dp)
                                .drawBackdrop(backdrop = backdrop, shape = { Capsule() },
                                    effects = { vibrancy(); blur(4.dp.toPx()) },
                                    highlight = { Highlight.Ambient.copy(alpha = 0.3f) },
                                    onDrawSurface = {
                                        if (isSelected) drawRect(AppleRed.copy(0.8f))
                                        else { drawRect(Color.White.copy(0.03f)); drawRect(Color.White.copy(0.15f), style = Stroke(1.dp.toPx())) }
                                    }
                                ).clip(RoundedCornerShape(50))
                                .clickable(remember { MutableInteractionSource() }, null) { loadCountry(country) }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(country, color = if (isSelected) Color.White else lc.textSecondary,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── Loading ──
            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppleRed, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // ── Stations ──
            items(stations, key = { it.id }) { station ->
                val isPlaying = currentStationId == station.id
                StationRow(station = station, isPlaying = isPlaying, backdrop = backdrop, lc = lc,
                    onClick = { onPlayStation(station) })
                Spacer(Modifier.height(6.dp))
            }

            item { Spacer(Modifier.height(200.dp)) }
        }
    }
}

@Composable
private fun StationRow(
    station: RadioApi.RadioStation,
    isPlaying: Boolean,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier.fillMaxWidth()
            .drawBackdrop(backdrop = backdrop, shape = { shape },
                effects = { vibrancy(); blur(4.dp.toPx()) },
                highlight = { Highlight.Ambient.copy(alpha = 0.3f) },
                shadow = { if (isPlaying) Shadow(radius = 4.dp, color = AppleRed.copy(0.2f)) else Shadow(radius = 2.dp, color = Color.Black.copy(0.05f)) },
                onDrawSurface = {
                    drawRect(if (isPlaying) AppleRed.copy(0.08f) else Color.White.copy(0.03f))
                    drawRect(Color.White.copy(if (isPlaying) 0.25f else 0.15f), style = Stroke(1.dp.toPx()))
                }
            ).clip(shape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Favicon / Play indicator
        Box(
            modifier = Modifier.size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isPlaying) AppleRed.copy(0.2f) else Color.White.copy(0.06f)),
            contentAlignment = Alignment.Center
        ) {
            if (station.favicon.isNotBlank()) {
                AsyncImage(
                    model = station.favicon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                )
            } else {
                Icon(
                    if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    null, tint = if (isPlaying) AppleRed else lc.iconMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(station.name, color = if (isPlaying) AppleRed else lc.textPrimary,
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                if (station.country.isNotBlank()) {
                    Text(station.country, color = lc.textSecondary, fontSize = 12.sp)
                    if (station.tags.isNotBlank()) Text(" · ", color = lc.textTertiary, fontSize = 12.sp)
                }
                if (station.tags.isNotBlank()) {
                    Text(station.tags.split(",").take(2).joinToString(", "),
                        color = lc.textTertiary, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Bitrate
        if (station.bitrate > 0) {
            Text("${station.bitrate}k", color = lc.textTertiary, fontSize = 11.sp)
        }
    }
}
