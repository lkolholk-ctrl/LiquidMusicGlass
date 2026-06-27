package com.liquidmusicglass.ui.screens

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.MediaCacheManager
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.PlayerSettings
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.ui.liquid.LiquidToggle
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Единый акцент приложения — бледно-зелёный (заменил красный Apple-стиля).
private val Accent = Color(0xFF7FB77E)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    showBack: Boolean = true,
    backdrop: LayerBackdrop
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    val gaplessEnabled by AppSettings.gaplessEnabled.collectAsState()
    val sleepTimerMinutes by AppSettings.sleepTimerMinutes.collectAsState()
    val sleepOptions = listOf(0, 15, 30, 45, 60, 90)

    val themeMode by PlayerController.themeMode.collectAsState()
    val themeLabels = listOf("System", "Dark", "Light")

    val lc = LiquidTheme.colors

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(12.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка «назад» только когда Settings открыт оверлеем; как таб — без неё.
                if (showBack) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            tint = lc.iconDefault,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Text(
                    text = "Settings",
                    color = lc.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // PLAYBACK
            SectionLabel("PLAYBACK")

            val hideExplicit by AppSettings.hideExplicit.collectAsState()

            PlainCard {
                SettingsToggleItem(
                    title = "Gapless Playback",
                    subtitle = "No silence between tracks",
                    selected = gaplessEnabled,
                    onSelect = { AppSettings.setGapless(it) }
                )
                PlainDivider()
                SettingsToggleItem(
                    title = "Hide Explicit",
                    subtitle = "Filter explicit content from search & artist",
                    selected = hideExplicit,
                    onSelect = { AppSettings.setHideExplicit(it) }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Equalizer",
                    subtitle = "Bass Boost, Surround, Presets",
                    icon = Icons.Rounded.Equalizer,
                    onClick = onOpenEqualizer
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // SLEEP TIMER
            SectionLabel("SLEEP TIMER")

            PlainCard {
                SleepTimerSelector(
                    options = sleepOptions,
                    selectedMinutes = sleepTimerMinutes,
                    onSelect = { AppSettings.setSleepTimer(it) }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // PRELOAD NEXT TRACK
            SectionLabel("PRELOAD NEXT TRACK")

            val preloadLead by AppSettings.preloadLeadSeconds.collectAsState()
            PlainCard {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    PreloadSelector(
                        options = listOf(30, 45, 60, 75, 90),
                        selectedSeconds = preloadLead,
                        onSelect = { AppSettings.setPreloadLeadSeconds(it) }
                    )
                    Text(
                        text = "How early to preload the next track before the current one ends",
                        color = lc.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // STREAM QUALITY
            SectionLabel("STREAM QUALITY")

            val qualityOptions = listOf(
                "128K" to "Compressed. Fastest load, lowest data usage.",
                "256K" to "Balanced. Standard high-quality AAC.",
                "320K" to "Premium. Near-lossless perceptual quality."
            )
            var selectedQuality by remember {
                mutableStateOf(
                    context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                        .getString("stream_quality", "256K") ?: "256K"
                )
            }

            PlainCard {
                Column(modifier = Modifier.padding(vertical = 14.dp)) {
                    val isPremium by com.liquidmusicglass.api.icm.IcmAuthRepository.isPremium.collectAsState()
                    
                    // Auto-fallback to 256K if premium is lost or ALAC is selected (as ALAC is deprecated due to decryption latency)
                    androidx.compose.runtime.LaunchedEffect(isPremium, selectedQuality) {
                        if (selectedQuality == "ALAC") {
                            selectedQuality = "256K"
                            context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                                .edit().putString("stream_quality", "256K").apply()
                            com.liquidmusicglass.api.icm.IcmRepository.streamQuality = "256K"
                        } else if (!isPremium && selectedQuality == "320K") {
                            selectedQuality = "256K"
                            context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                                .edit().putString("stream_quality", "256K").apply()
                            com.liquidmusicglass.api.icm.IcmRepository.streamQuality = "256K"
                        }
                    }

                    qualityOptions.forEach { (quality, description) ->
                        val isSelected = selectedQuality == quality
                        val isAvailable = isPremium || quality != "320K"
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = isAvailable
                                ) {
                                    selectedQuality = quality
                                    context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                                        .edit().putString("stream_quality", quality).apply()
                                    com.liquidmusicglass.api.icm.IcmRepository.streamQuality = quality
                                    
                                    // Sync preference to server
                                    scope.launch {
                                        com.liquidmusicglass.api.icm.IcmRepository.updateUserPreferences(
                                            com.liquidmusicglass.api.icm.IcmUserPreferences(qualityPreference = quality)
                                        )
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .graphicsLayer { alpha = if (isAvailable) 1f else 0.4f },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = quality,
                                        color = if (isSelected) Accent else lc.textPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    if (!isAvailable) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Premium",
                                            color = Color(0xFF8B5CF6),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .background(Color(0xFF8B5CF6).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = description,
                                    color = lc.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Accent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✓",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // WAVE
            SectionLabel("WAVE")

            var isResettingWave by remember { mutableStateOf(false) }
            var waveResetSuccess by remember { mutableStateOf(false) }

            PlainCard {
                SettingsActionItem(
                    title = "Reset Wave Preferences",
                    subtitle = if (waveResetSuccess) "Preferences reset successfully" else "Clear wave history and start fresh",
                    icon = Icons.Rounded.ChevronRight,
                    onClick = {
                        if (isResettingWave) return@SettingsActionItem
                        scope.launch {
                            isResettingWave = true
                            val success = IcmRepository.resetWave()
                            isResettingWave = false
                            if (success) {
                                waveResetSuccess = true
                                delay(3000)
                                waveResetSuccess = false
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // THEME
            SectionLabel("APPEARANCE")

            PlainCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    themeLabels.forEachIndexed { index, label ->
                        val isSelected = themeMode == index
                        val isDark = lc.isDark
                        val itemBg = if (isSelected) Accent else (if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
                        val unselectedTextColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.45f)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(
                                    itemBg,
                                    RoundedCornerShape(50)
                                )
                                .clip(RoundedCornerShape(50))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { PlayerController.setThemeMode(index) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val textColor by animateColorAsState(
                                targetValue = if (isSelected) Color.White
                                else unselectedTextColor,
                                animationSpec = tween(200),
                                label = "themeText"
                            )
                            Text(
                                text = label,
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── AUTOMIX & SOUND ──
            SectionLabel("AUTOMIX & SOUND")

            val autoMix by PlayerSettings.autoMix.collectAsState()
            val volumeNorm by PlayerSettings.volumeNormalization.collectAsState()
            PlainCard {
                SettingsToggleItem(
                    title = "AutoMix",
                    subtitle = "Seamlessly keep the wave going",
                    selected = autoMix,
                    onSelect = { PlayerSettings.setAutoMix(it) }
                )
                PlainDivider()
                SettingsToggleItem(
                    title = "Sound Check",
                    subtitle = "Normalize volume across tracks",
                    selected = volumeNorm,
                    onSelect = { PlayerSettings.setVolumeNormalization(it) }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── AUDIO CACHE ──
            SectionLabel("AUDIO CACHE")

            val cacheBytes by PlayerSettings.audioCacheBytes.collectAsState()
            var cacheUsed by remember { mutableStateOf(-1L) }
            var cacheRefresh by remember { mutableStateOf(0) }
            LaunchedEffect(cacheBytes, cacheRefresh) {
                cacheUsed = MediaCacheManager.getCacheSizeBytes()
            }
            PlainCard {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    CacheSizeSelector(
                        options = PlayerSettings.CACHE_OPTIONS_BYTES,
                        selected = cacheBytes,
                        onSelect = { bytes ->
                            PlayerSettings.setAudioCacheBytes(bytes)
                            MediaCacheManager.applyCacheSizeChange()
                            scope.launch {
                                delay(600)
                                cacheRefresh++
                            }
                        }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (cacheBytes <= 0L) "Cache is off"
                            else "Currently used: ${formatBytes(cacheUsed.coerceAtLeast(0L))}",
                            color = lc.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Clear",
                            color = Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    scope.launch {
                                        MediaCacheManager.clearCache()
                                        delay(300)
                                        cacheRefresh++
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── ACCESSIBILITY ──
            SectionLabel("ACCESSIBILITY")

            val increaseContrast by PlayerSettings.increaseContrast.collectAsState()
            PlainCard {
                SettingsToggleItem(
                    title = "Increase Contrast",
                    subtitle = "Stronger text & less glass transparency",
                    selected = increaseContrast,
                    onSelect = { PlayerSettings.setIncreaseContrast(it) }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── DEV (temporary — JUCE Stage 1/2 verification) ──
            SectionLabel("DEV")

            var toneOn by remember { mutableStateOf(false) }
            var devStatus by remember {
                mutableStateOf("Decodes: WAV/AIFF/FLAC/OGG (JUCE) + MP3/AAC/M4A (MediaCodec)")
            }
            val engine = com.liquidmusicglass.engine.automix.AutoMixNativeEngine

            // System file picker → copy to cache → JUCE decode + play.
            val pickTrack = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        devStatus = "Loading…"
                        val path = withContext(Dispatchers.IO) { copyUriToCache(context, uri) }
                        if (path == null) {
                            devStatus = "Copy failed"
                            return@launch
                        }
                        engine.init(context)
                        val ok = engine.loadTrack(path)
                        if (ok) {
                            engine.play()
                            devStatus = "Playing: ${path.substringAfterLast('/')}"
                        } else {
                            devStatus = "Decode failed (unsupported?): ${path.substringAfterLast('/')}"
                        }
                    }
                }
            }

            PlainCard {
                SettingsToggleItem(
                    title = "JUCE Test Tone 440 Hz",
                    subtitle = "Native JUCE → Oboe output check (only when no track loaded)",
                    selected = toneOn,
                    onSelect = { on ->
                        toneOn = on
                        if (on) {
                            engine.init(context)
                            engine.startTone()
                        } else {
                            engine.stopTone()
                        }
                    }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "JUCE: Pick & Play Track",
                    subtitle = devStatus,
                    icon = Icons.Rounded.PlayArrow,
                    onClick = { pickTrack.launch(arrayOf("audio/*")) }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Pause",
                    subtitle = "Pause JUCE transport",
                    icon = Icons.Rounded.Pause,
                    onClick = { engine.pause() }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Stop",
                    subtitle = "Stop & rewind JUCE transport",
                    icon = Icons.Rounded.Stop,
                    onClick = { engine.stop() }
                )
            }

            // Нижний отступ под плавающий таб-бар (Settings теперь вкладка).
            Spacer(modifier = Modifier.height(110.dp))
        }
    }
}

/** 0/200МБ/500МБ/1/2/5ГБ — две строки по три кнопки. */
@Composable
private fun CacheSizeSelector(
    options: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        options.chunked(3).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEach { bytes ->
                    val isSelected = selected == bytes
                    val isDark = LiquidTheme.colors.isDark
                    val itemBg = if (isSelected) Accent else (if (isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA))
                    val unselectedTextColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.45f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(itemBg, RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(bytes) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = cacheLabel(bytes),
                            color = if (isSelected) Color.White else unselectedTextColor,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

private fun cacheLabel(bytes: Long): String = when {
    bytes <= 0L -> "Off"
    bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
    else -> "${bytes / (1024L * 1024)} MB"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    else -> "${bytes / (1024L * 1024)} MB"
}

// ── UI Components ──

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = LiquidTheme.colors.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun PlainCard(content: @Composable ColumnScope.() -> Unit) {
    val isDark = LiquidTheme.colors.isDark
    val cardBg = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBg, RoundedCornerShape(28.dp))
            .padding(vertical = 4.dp),
        content = content
    )
}

@Composable
private fun PlainDivider() {
    val isDark = LiquidTheme.colors.isDark
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(dividerColor)
    )
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: (Boolean) -> Unit
) {
    val screenBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onSelect(!selected) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp
            )
        }
        LiquidToggle(selected = { selected }, onSelect = onSelect, backdrop = screenBackdrop)
    }
}

@Composable
private fun SettingsActionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = LiquidTheme.colors.iconDefault,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun PreloadSelector(
    options: List<Int>,
    selectedSeconds: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { sec ->
            val isSelected = selectedSeconds == sec
            val isDark = LiquidTheme.colors.isDark
            val itemBg = if (isSelected) Accent else (if (isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA))
            val unselectedTextColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.45f)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(itemBg, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(sec) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else unselectedTextColor,
                    animationSpec = tween(200),
                    label = "preloadText"
                )
                Text(
                    text = "${sec}s",
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun SleepTimerSelector(
    options: List<Int>,
    selectedMinutes: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { minutes ->
            val isSelected = selectedMinutes == minutes
            val isDark = LiquidTheme.colors.isDark
            val itemBg = if (isSelected) Accent else (if (isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA))
            val unselectedTextColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.45f)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(
                        itemBg,
                        RoundedCornerShape(50)
                    )
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(minutes) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White
                    else unselectedTextColor,
                    animationSpec = tween(200),
                    label = "sleepText"
                )
                Text(
                    text = if (minutes == 0) "Off" else "${minutes}m",
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * DEV helper (Stage 2): copy a picked content:// audio file into the app cache so
 * JUCE's AudioFormatManager can open it by path. Returns the absolute path, or
 * null on failure. The extension is preserved so JUCE picks the right decoder.
 */
private fun copyUriToCache(context: Context, uri: Uri): String? {
    return try {
        val resolver = context.contentResolver
        var displayName: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) displayName = c.getString(idx)
            }
        }
        val ext = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() } ?: "wav"
        val out = File(context.cacheDir, "automix_test.$ext")
        resolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out.absolutePath
    } catch (t: Throwable) {
        null
    }
}
