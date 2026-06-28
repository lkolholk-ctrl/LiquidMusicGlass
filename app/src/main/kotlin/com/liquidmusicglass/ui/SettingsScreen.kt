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
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.runtime.DisposableEffect
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
                mutableStateOf("WAV/AIFF/FLAC/OGG (JUCE) + MP3/AAC/M4A (MediaCodec)")
            }
            var bpmA by remember { mutableStateOf("128") }
            var bpmB by remember { mutableStateOf("100") }
            var pathA by remember { mutableStateOf<String?>(null) }
            var pathB by remember { mutableStateOf<String?>(null) }
            var durationA by remember { mutableStateOf(0L) }
            var juceAutoMix by remember { mutableStateOf(false) }
            val engine = com.liquidmusicglass.engine.automix.AutoMixNativeEngine
            // LAZY: do NOT construct at composition — AutoMixController's ctor loads
            // the TFLite model. It's built on first analysis, off the main thread,
            // so the model never loads at app/cold start.
            val autoMixLazy = remember { lazy { com.liquidmusicglass.automix.AutoMixController(context.applicationContext) } }
            var autoMixJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

            // Free the JUCE/Oboe device + decoded buffers (and the ML model if it was
            // loaded) when leaving this screen, so nothing heavy is held in the bg.
            DisposableEffect(Unit) {
                onDispose {
                    engine.release()
                    if (autoMixLazy.isInitialized()) runCatching { autoMixLazy.value.release() }
                }
            }

            // Pickers ONLY copy + remember the path. They DO NOT touch JUCE — the
            // engine (AAudio / NDK MediaCodec / TimeSliceThread) must stay asleep
            // until a blend actually runs, so it's never on the cold-start budget.
            val pickA = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        devStatus = "Copying A…"
                        val path = withContext(Dispatchers.IO) { copyUriToCache(context, uri, "A") }
                        if (path == null) { devStatus = "A copy failed"; return@launch }
                        pathA = path
                        durationA = withContext(Dispatchers.IO) { audioDurationMs(File(path)) }
                        devStatus = "A ready (${durationA / 1000}s) — pick B"
                    }
                }
            }

            val pickB = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        devStatus = "Copying B…"
                        val path = withContext(Dispatchers.IO) { copyUriToCache(context, uri, "B") }
                        if (path == null) { devStatus = "B copy failed"; return@launch }
                        pathB = path
                        devStatus = "B ready — Arm AutoMix (or Manual blend)"
                    }
                }
            }

            PlainCard {
                SettingsToggleItem(
                    title = "JUCE Test Tone 440 Hz",
                    subtitle = "Output check (only when no track loaded)",
                    selected = toneOn,
                    onSelect = { on ->
                        toneOn = on
                        if (on) { engine.init(context); engine.startTone() } else engine.stopTone()
                    }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Deck A: Pick",
                    subtitle = devStatus,
                    icon = Icons.Rounded.PlayArrow,
                    onClick = { pickA.launch(arrayOf("audio/*")) }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Deck B: Pick",
                    subtitle = "Second track (no JUCE until blend)",
                    icon = Icons.Rounded.LibraryMusic,
                    onClick = { pickB.launch(arrayOf("audio/*")) }
                )
                PlainDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = bpmA,
                        onValueChange = { bpmA = it },
                        label = { Text("BPM A") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = bpmB,
                        onValueChange = { bpmB = it },
                        label = { Text("BPM B") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                PlainDivider()
                SettingsActionItem(
                    title = "Manual blend now (A → B, 8s)",
                    subtitle = "Wakes JUCE NOW: decode A+B, beat-match (if BPM), bass-swap crossfade",
                    icon = Icons.Rounded.SwapHoriz,
                    onClick = {
                        scope.launch {
                            val pa = pathA; val pb = pathB
                            if (pa == null || pb == null) { devStatus = "Pick Deck A & B first"; return@launch }
                            devStatus = "JUCE waking + decoding A/B…"
                            val a = bpmA.toDoubleOrNull() ?: 0.0
                            val b = bpmB.toDoubleOrNull() ?: 0.0
                            withContext(Dispatchers.IO) {
                                engine.init(context)               // JUCE wakes on this manual action
                                engine.loadTrackA(pa)
                                engine.loadTrackB(pb)
                                if (a > 0.0 && b > 0.0) engine.prepareStretchB(a, b)
                            }
                            engine.play()
                            engine.startCrossfade(8000.0)
                            devStatus = "Manual blend running (8s)"
                        }
                    }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Pause",
                    subtitle = "Pause both decks",
                    icon = Icons.Rounded.Pause,
                    onClick = { engine.pause() }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Stop",
                    subtitle = "Stop & rewind both decks",
                    icon = Icons.Rounded.Stop,
                    onClick = { engine.stop() }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── DEV: AutoMix model -> JUCE (Stage 6) ──
            // Separate from the global AutoMix toggle. ON = the ML model analyses
            // the picked A/B pair and JUCE executes the blend by its decisions.
            SectionLabel("DEV — AUTOMIX (MODEL → JUCE)")
            PlainCard {
                SettingsToggleItem(
                    title = "JUCE AutoMix (test)",
                    subtitle = "On: model analyses in bg, JUCE blends near track end. Off: legacy Media3.",
                    selected = juceAutoMix,
                    onSelect = { juceAutoMix = it }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Arm AutoMix (analyze bg → timed cue)",
                    subtitle = devStatus,
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = {
                        autoMixJob?.cancel()
                        autoMixJob = scope.launch {
                            val pa = pathA; val pb = pathB; val durA = durationA
                            if (pa == null || pb == null || durA <= 0L) { devStatus = "Pick Deck A & B first"; return@launch }
                            if (!juceAutoMix) { devStatus = "Toggle 'JUCE AutoMix' ON"; return@launch }

                            // 1) Analyse the pair in the BACKGROUND. JUCE stays ASLEEP —
                            //    no engine calls here, so AAudio / NDK MediaCodec / the
                            //    TimeSliceThread are never started during analysis.
                            devStatus = "Model analysing (bg)… JUCE asleep"
                            val feat = withContext(Dispatchers.Default) {
                                autoMixLazy.value.analyzeTrackPair(Uri.fromFile(File(pa)), Uri.fromFile(File(pb)), durA)
                            }
                            android.util.Log.i(
                                "JUCEAutoMix",
                                "MODEL READY: xfade=${feat.crossfadeDurationMs}ms type=${feat.transitionType} " +
                                    "start=${feat.transitionStartMs}ms entry=${feat.entryOffsetMs}ms " +
                                    "bpmA=${feat.bpmA} bpmB=${feat.bpmB} compat=${feat.compatibility} ready=${feat.readyForTransition}"
                            )
                            if (!feat.readyForTransition) {
                                devStatus = "Model: pair not compatible (compat=${"%.2f".format(feat.compatibility)}) — no transition"
                                return@launch
                            }

                            // 2) Будим JUCE и СРАЗУ играем трек A — настоящий звук.
                            //    JUCE грузится только ТУТ, по нажатию Arm (не на старте
                            //    приложения), поэтому cold-start остаётся лёгким. Decode
                            //    A+B, точку входа B и beat-match готовим заранее, чтобы
                            //    кроссфейд стартовал без рывка.
                            val controller = autoMixLazy.value
                            val ba = feat.bpmA; val bb = feat.bpmB
                            devStatus = "Waking JUCE · loading A+B…"
                            withContext(Dispatchers.IO) {
                                engine.init(context)
                                engine.loadTrackA(pa)
                                engine.loadTrackB(pb)
                                engine.setEntryOffsetB(feat.entryOffsetMs.toDouble())
                                if (ba != null && bb != null && ba > 0f && bb > 0f) {
                                    engine.prepareStretchB(ba.toDouble(), bb.toDouble())
                                }
                            }
                            withContext(Dispatchers.IO) { engine.play() }   // дек A играет с начала — звук слышен сразу
                            android.util.Log.i(
                                "JUCEAutoMix",
                                "PLAYING A · cue @ ${feat.transitionStartMs}ms (model) · " +
                                    "xfade ${feat.crossfadeDurationMs}ms type ${feat.transitionType}"
                            )

                            // 3) Крутим РЕАЛЬНУЮ позицию дека A. Когда модель скажет
                            //    «пора» (shouldStartTransition по transitionStartMs) —
                            //    запускаем кроссфейд A→B. И звук, и момент — от модели.
                            var blended = false
                            while (!blended) {
                                delay(200)
                                val posA = engine.positionMsA().toLong()
                                val lenA = engine.lengthMsA().toLong().coerceAtLeast(durA)
                                val remaining = (lenA - posA).coerceAtLeast(0L)
                                val tr = controller.shouldStartTransition(posA, remaining, feat)
                                if (tr.shouldStart) {
                                    android.util.Log.i(
                                        "JUCEAutoMix",
                                        "MODEL CUE @ posA=${posA}ms → blend ${tr.crossfadeDurationMs}ms " +
                                            "type ${tr.transitionType} entry ${tr.entryOffsetMs}ms"
                                    )
                                    withContext(Dispatchers.IO) {
                                        engine.setEntryOffsetB(tr.entryOffsetMs.toDouble())
                                        engine.startCrossfade(tr.crossfadeDurationMs.toDouble())
                                    }
                                    devStatus = "JUCE blend: ${tr.crossfadeDurationMs}ms · type ${tr.transitionType} · " +
                                        "bpm ${ba?.toInt() ?: "?"}→${bb?.toInt() ?: "?"}"
                                    blended = true
                                } else {
                                    devStatus = "Playing A · ${posA / 1000}s / cue ${feat.transitionStartMs / 1000}s of ${lenA / 1000}s"
                                }
                            }
                        }
                    }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Cancel AutoMix arm",
                    subtitle = "Stop playback / waiting for the cue",
                    icon = Icons.Rounded.Stop,
                    onClick = {
                        autoMixJob?.cancel()
                        runCatching { engine.stop() }
                        devStatus = "AutoMix arm cancelled"
                    }
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
private fun copyUriToCache(context: Context, uri: Uri, slot: String): String? {
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
        // Unique filename per pick. The model's AutoMixController caches its energy
        // analysis by URI string (energyCache[uri.toString()]). A fixed name like
        // "automix_A.wav" makes every pick reuse the same path -> same URI -> the
        // cache returns the FIRST track's analysis forever, so swapping A<->B yielded
        // identical params. A unique nonce per pick guarantees a fresh URI -> cache
        // miss -> real inference (new BPM/crossfade/start) for the new track.
        val prefix = "automix_${slot}_"
        context.cacheDir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
        val out = File(context.cacheDir, "$prefix${System.currentTimeMillis()}.$ext")
        resolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out.absolutePath
    } catch (t: Throwable) {
        null
    }
}

/** DEV helper: track duration (ms) for the AutoMix model analysis window. */
private fun audioDurationMs(file: File): Long {
    return try {
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        r.release()
        d ?: 180_000L
    } catch (_: Throwable) {
        180_000L
    }
}
