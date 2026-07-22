package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.api.icm.IcmApi
import com.liquidmusicglass.api.icm.IcmClipItem
import com.liquidmusicglass.api.icm.icmUserMessage
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

/**
 * Раздел «Видео»: поиск видеоклипов Apple Music (ICM /clips/search), сетка
 * тумбнейлов. Тап → resolve stream_url → playClip → открывается плеер, где
 * вместо обложки Surface с видео (см. FullPlayer).
 */
@Composable
fun VideoClipScreen(
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit,
) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<IcmClipItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<String?>(null) }   // clipId в процессе резолва
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        val q = query.trim()
        if (q.length < 2) return
        error = null; loading = true
        scope.launch {
            val r = IcmApi.getInstance().searchClips(q)
            loading = false
            r.onSuccess { results = it.results }
                .onFailure { error = icmUserMessage(it); results = emptyList() }
        }
    }

    fun openClip(clip: IcmClipItem) {
        if (resolving != null) return
        resolving = clip.id; error = null
        scope.launch {
            val r = IcmApi.getInstance().resolveClipStreamUrl(clip.id)
            resolving = null
            r.onSuccess { url ->
                PlayerController.playClip(context, url, clip.id, clip.title, clip.artist, clip.thumbnail)
                onOpenPlayer()
            }.onFailure { error = icmUserMessage(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(lc.settingsBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
                    .liquidClickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text("Video", color = lc.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))

        val cardBg = if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
        TextField(
            value = query,
            onValueChange = { query = it; error = null },
            singleLine = true,
            placeholder = { Text("Search music videos…", color = lc.textTertiary) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = cardBg, unfocusedContainerColor = cardBg,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = lc.textPrimary, unfocusedTextColor = lc.textPrimary,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Color(0xFFFC3C44), fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF88C088))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(results, key = { it.id }) { clip ->
                    ClipCard(clip, resolving == clip.id) { openClip(clip) }
                }
            }
        }
    }
}

@Composable
private fun ClipCard(clip: IcmClipItem, isResolving: Boolean, onClick: () -> Unit) {
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
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(clip.artist, color = lc.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
