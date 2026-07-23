package com.liquidmusicglass.ui.screens

import android.content.Context
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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
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
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.liquid.LiquidToggle
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// Единый акцент приложения — бледно-зелёный (заменил красный Apple-стиля).
private val Accent = Color(0xFF7FB77E)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    showBack: Boolean = true,
    backdrop: LayerBackdrop
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    val sleepTimerMinutes by AppSettings.sleepTimerMinutes.collectAsState()
    val sleepOptions = listOf(0, 15, 30, 45, 60, 90)

    val themeMode by PlayerController.themeMode.collectAsState()
    val themeLabels = listOf("System", "Dark", "Light")

    val lc = LiquidTheme.colors

    // Широкое окно (телефон-альбом ИЛИ планшет). В портрете layout не меняется.
    val win = com.liquidmusicglass.ui.rememberWindowInfo()
    // В альбоме/на планшете всё компактнее: секции ставим ближе (меньше вертикальный
    // ход взгляда). Только для широкого окна — портрет остаётся как был.
    val sectionGap = if (win.useSideBySide) 20.dp else 28.dp

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(
            modifier = Modifier
                // В альбоме/на планшете вертикальный список настроек не тянем на
                // всю ширину (растянутые карточки некрасивы, взгляд возит далеко) —
                // ограничиваем 640dp и центрируем. Портрет остаётся как был.
                .then(
                    if (win.useSideBySide)
                        Modifier.align(Alignment.TopCenter).fillMaxHeight().widthIn(max = 640.dp)
                    else Modifier.fillMaxSize()
                )
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
                            .liquidClickable(pressedScale = LiquidMotion.PressIcon) { onBack() },
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
                // 5 быстрых тапов по заголовку — вкл/выкл отладочный UI
                // (панель JUCE DEBUG, LOG-чип, LCAT). Скрыт по умолчанию.
                var dbgTaps by remember { mutableStateOf(0) }
                var dbgLastTapAt by remember { mutableStateOf(0L) }
                val dbgContext = LocalContext.current
                Text(
                    text = "Settings",
                    color = lc.textPrimary,
                    fontSize = if (win.useSideBySide) 20.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val now = System.currentTimeMillis()
                        dbgTaps = if (now - dbgLastTapAt < 1200L) dbgTaps + 1 else 1
                        dbgLastTapAt = now
                        if (dbgTaps >= 5) {
                            dbgTaps = 0
                            val on = !AppSettings.debugUiEnabled.value
                            AppSettings.setDebugUiEnabled(on)
                            android.widget.Toast.makeText(
                                dbgContext,
                                if (on) "Debug tools: ON" else "Debug tools: OFF",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Шапка-профиль: аватарка + имя, тап → профиль (как у Apple/TG) ──
            run {
                val avatarUrl by com.liquidmusicglass.api.icm.IcmAuthRepository
                    .avatarUrl.collectAsState()
                val profileName by com.liquidmusicglass.api.icm.IcmAuthRepository
                    .profileName.collectAsState()
                val userEmail by com.liquidmusicglass.api.icm.IcmAuthRepository
                    .userEmail.collectAsState()
                val displayName = when {
                    !profileName.isNullOrBlank() -> profileName!!
                    userEmail != null -> userEmail!!.substringBefore("@")
                        .replaceFirstChar { it.uppercase() }
                    else -> "Guest"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
                        .liquidClickable { onOpenProfile() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (lc.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!avatarUrl.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = avatarUrl,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = lc.iconMuted,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = displayName,
                            color = lc.textPrimary,
                            fontSize = if (win.useSideBySide) 15.sp else 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Account, premium & data",
                            color = lc.textSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(sectionGap))

            // PLAYBACK
            SectionLabel("PLAYBACK")

            val hideExplicit by AppSettings.hideExplicit.collectAsState()
            val audioCompatMode by AppSettings.audioCompatMode.collectAsState()
            val audioCompatAuto by AppSettings.audioCompatAuto.collectAsState()
            val hapticMusicEnabled by AppSettings.hapticMusicEnabled.collectAsState()
            val hapticStrength by AppSettings.hapticStrength.collectAsState()

            PlainCard {
                // Gapless-тумблер удалён: флаг никто не читал (ExoPlayer бесшовный
                // сам по себе, у JUCE — AutoMix), мёртвая настройка врала юзеру.
                SettingsToggleItem(
                    title = "Hide Explicit",
                    subtitle = "Filter explicit content from search & artist",
                    selected = hideExplicit,
                    onSelect = { enabled ->
                        AppSettings.setHideExplicit(enabled)
                        // hide_explicit живёт в токене сессии ICM → перевыпускаем
                        // его сразу, чтобы фильтр включился без перезапуска.
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                com.liquidmusicglass.api.icm.IcmAuthRepository.reissueSessionToken()
                                com.liquidmusicglass.api.icm.IcmRepository.clearSearchCache()
                            } catch (_: Exception) {}
                        }
                    }
                )
                PlainDivider()
                // Выход локального аудио. Auto — вендорная таблица AudioQuirks +
                // watchdog (рекомендуется); Fast — нативный low-latency (AAudio);
                // Compat — legacy-путь без low-latency; Track — Java AudioTrack,
                // путь ExoPlayer (максимальная совместимость: vivo/Xiaomi).
                // Продвинутые режимы (exclusive/i16/OpenSL) — кнопкой MODE в
                // дебаг-панели; при них здесь не подсвечено ничего.
                Column(modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                    Text(
                        text = "Audio Output",
                        color = LiquidTheme.colors.textPrimary,
                        fontSize = if (win.useSideBySide) 14.sp else 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    // Битые для этого вендора режимы — помечаем в селекторе.
                    // Compat = mode 4 (SAFE_I16): float deep-buffer (был mode 1)
                    // глух на Honor и капризен на vivo/Xiaomi; i16-вариант шире.
                    val audioBadModes = remember { com.liquidmusicglass.engine.automix.AudioQuirks.knownBadModes() }
                    val audioOutCtx = LocalContext.current
                    val badKeys = remember(audioBadModes) {
                        buildSet {
                            if (0 in audioBadModes) add("fast")
                            if (4 in audioBadModes) add("compat")
                            if (6 in audioBadModes) add("track")
                        }
                    }
                    AudioOutputSelector(
                        selectedKey = when {
                            audioCompatAuto -> "auto"
                            audioCompatMode == 0 -> "fast"
                            audioCompatMode == 1 || audioCompatMode == 4 -> "compat"
                            audioCompatMode == 6 -> "track"
                            else -> "custom"
                        },
                        badKeys = badKeys,
                        onSelect = { key ->
                            if (key in badKeys) {
                                android.widget.Toast.makeText(
                                    audioOutCtx,
                                    "This path is known to be broken on this device. If you get silence or noise, switch back to Auto.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            when (key) {
                                "auto" -> AppSettings.setAudioCompatModeAuto()
                                "fast" -> AppSettings.setAudioCompatMode(0)
                                "compat" -> AppSettings.setAudioCompatMode(4)
                                "track" -> AppSettings.setAudioCompatMode(6)
                            }
                        }
                    )
                    Text(
                        text = "Auto picks the right path for this device and self-heals if audio stalls",
                        color = LiquidTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                PlainDivider()
                // Warm Sound: «звук как на Track» для быстрых выходов — свой
                // bass-shelf + ширина в JUCE-цепочке (вендорский Histen быстрые
                // пути обходит). На Track отключается сам (иначе бас двоится).
                run {
                    val warmEnabled by com.liquidmusicglass.engine.AudioFxController
                        .warmEnabled.collectAsState()
                    SettingsToggleItem(
                        title = "Warm Sound",
                        subtitle = "Track-like bass & width on the Fast output",
                        selected = warmEnabled,
                        onSelect = { com.liquidmusicglass.engine.AudioFxController.setWarmEnabled(it) }
                    )
                }
                PlainDivider()
                // Haptic Music: тактильные удары в такт (свой детектор в нативном
                // колбэке — системный HapticGenerator вендоры не открывают).
                SettingsToggleItem(
                    title = "Haptic Music",
                    subtitle = "Feel the beat — vibration taps follow the music",
                    selected = hapticMusicEnabled,
                    onSelect = { AppSettings.setHapticMusicEnabled(it) }
                )
                if (hapticMusicEnabled) {
                    // Сила: Soft — только акценты (лёгкие тики), Medium — баланс,
                    // Strong — каждый удар в полную руку.
                    HapticStrengthSelector(
                        selected = hapticStrength,
                        onSelect = { AppSettings.setHapticStrength(it) }
                    )
                }
                PlainDivider()
                SettingsActionItem(
                    title = "Audio",
                    subtitle = "EQ, Bass, Loudness, Compressor, Limiter",
                    icon = Icons.Rounded.Equalizer,
                    onClick = onOpenEqualizer
                )
            }

            Spacer(modifier = Modifier.height(sectionGap))

            // SLEEP TIMER
            SectionLabel("SLEEP TIMER")

            PlainCard {
                SleepTimerSelector(
                    options = sleepOptions,
                    selectedMinutes = sleepTimerMinutes,
                    onSelect = { AppSettings.setSleepTimer(it) }
                )
            }

            Spacer(modifier = Modifier.height(sectionGap))

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

            Spacer(modifier = Modifier.height(sectionGap))

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
                                .liquidClickable(enabled = isAvailable) {
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
                                        fontSize = if (win.useSideBySide) 14.sp else 16.sp,
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

            Spacer(modifier = Modifier.height(sectionGap))

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

            Spacer(modifier = Modifier.height(sectionGap))

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
                                .liquidClickable(
                                    pressedScale = LiquidMotion.PressButton,
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

            Spacer(modifier = Modifier.height(sectionGap))

            // ── AUTOMIX & SOUND ──
            SectionLabel("AUTOMIX & SOUND")

            val autoMix by PlayerSettings.autoMix.collectAsState()
            val volumeNorm by PlayerSettings.volumeNormalization.collectAsState()
            PlainCard {
                SettingsToggleItem(
                    title = "AutoMix",
                    subtitle = "Model-driven JUCE blending between tracks (local + streaming) + auto wave",
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

            Spacer(modifier = Modifier.height(sectionGap))

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
                                .liquidClickable(pressedScale = LiquidMotion.PressButton) {
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

            Spacer(modifier = Modifier.height(sectionGap))

            // ── BACKGROUND PLAYBACK ──
            // Doze/оптимизация батареи душит фоновые декод-потоки и сеть стриминга
            // (лаги/«цикличка» при погашенном экране). Исключение из оптимизации —
            // штатное решение для музыкальных плееров.
            SectionLabel("BACKGROUND PLAYBACK")
            PlainCard {
                SettingsActionItem(
                    title = "Ignore Battery Optimization",
                    subtitle = "Prevents background stutter (Doze). Recommended for music",
                    icon = Icons.Rounded.ChevronRight,
                    onClick = { requestIgnoreBatteryOptimizations(context) }
                )
            }

            Spacer(modifier = Modifier.height(sectionGap))

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

            Spacer(modifier = Modifier.height(sectionGap))

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
                            .liquidClickable(
                                pressedScale = LiquidMotion.PressButton,
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

/**
 * Запрос исключения из оптимизации батареи — МАКСИМАЛЬНО защищённо. На части
 * прошивок/версий Android этот интент вырезан или кидает SecurityException
 * (известны стабильные краши у приложений без страховок), поэтому:
 *  - каждый шаг в runCatching (никогда не роняем приложение);
 *  - FLAG_ACTIVITY_NEW_TASK (не зависим от типа контекста);
 *  - цепочка фолбэков: системный диалог → общий список исключений →
 *    страница приложения в настройках. Ничего не персистим — повторный
 *    запуск приложения ни при каком исходе не затрагивается.
 */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val pkg = context.packageName
    val alreadyIgnoring = runCatching {
        context.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(pkg) == true
    }.getOrDefault(false)

    fun tryStart(intent: android.content.Intent): Boolean = runCatching {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    if (!alreadyIgnoring) {
        // 1) Прямой системный диалог (нужен REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        //    в манифесте — добавлен; без него часть прошивок кидает SecurityException).
        if (tryStart(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$pkg")
                )
            )
        ) return
    }
    // 2) Общий список исключений (или уже в исключениях — показать, где это).
    if (tryStart(
            android.content.Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            )
        )
    ) return
    // 3) Последний фолбэк — страница приложения в настройках.
    tryStart(
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$pkg")
        )
    )
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

/** Выбор выхода локального аудио: Auto / Fast / Compat / Track (стиль PreloadSelector). */
@Composable
private fun AudioOutputSelector(
    selectedKey: String,
    badKeys: Set<String> = emptySet(),
    onSelect: (String) -> Unit
) {
    val options = listOf("auto" to "Auto", "fast" to "Fast", "compat" to "Compat", "track" to "Track")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            val isSelected = selectedKey == key
            val isBad = key in badKeys
            val isDark = LiquidTheme.colors.isDark
            val itemBg = if (isSelected) Accent else (if (isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA))
            // Битый режим — сильно приглушаем (юзер видит: путь есть, но глухой).
            val baseAlpha = if (isBad) 0.25f else 0.45f
            val unselectedTextColor = if (isDark) Color.White.copy(alpha = baseAlpha) else Color.Black.copy(alpha = baseAlpha)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(itemBg, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .liquidClickable(
                        pressedScale = LiquidMotion.PressButton,
                        onClick = { onSelect(key) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else unselectedTextColor,
                    animationSpec = tween(200),
                    label = "audioOutText"
                )
                Text(
                    // Битый режим помечаем крестиком-подсказкой.
                    text = if (isBad) "$label ✕" else label,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** Сила Haptic Music: Soft / Medium / Strong (стиль AudioOutputSelector). */
@Composable
private fun HapticStrengthSelector(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val options = listOf(1 to "Medium", 2 to "Strong")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            val isSelected = selected == key
            val isDark = LiquidTheme.colors.isDark
            val itemBg = if (isSelected) Accent else (if (isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA))
            val unselectedTextColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.45f)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(itemBg, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .liquidClickable(
                        pressedScale = LiquidMotion.PressButton,
                        onClick = { onSelect(key) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else unselectedTextColor,
                    animationSpec = tween(200),
                    label = "hapticStrengthText"
                )
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: (Boolean) -> Unit
) {
    val screenBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    // Компактный заголовок строки в широком окне (телефон-альбом/планшет).
    val compact = com.liquidmusicglass.ui.rememberWindowInfo().useSideBySide
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidClickable { onSelect(!selected) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = if (compact) 14.sp else 16.sp,
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
    // Компактный заголовок строки в широком окне (телефон-альбом/планшет).
    val compact = com.liquidmusicglass.ui.rememberWindowInfo().useSideBySide
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidClickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = if (compact) 14.sp else 16.sp,
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
                    .liquidClickable(
                        pressedScale = LiquidMotion.PressButton,
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
                    .liquidClickable(
                        pressedScale = LiquidMotion.PressButton,
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
