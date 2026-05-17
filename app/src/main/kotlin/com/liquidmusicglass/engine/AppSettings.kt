package com.liquidmusicglass.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AppSettings — централизованное хранение всех настроек.
 *
 * Сохраняет и восстанавливает:
 * - Настройки плеера (AutoMix, Gapless, Sleep Timer, Ignore Short)
 * - Состояние плеера (последний трек, позиция, очередь)
 * - Настройки EQ (enabled, bands, bass, virtualizer, loudness, preset)
 * - Навигация (последний экран)
 */
object AppSettings {

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // ── Playback Settings ──
    private val _autoMixEnabled = MutableStateFlow(false)
    val autoMixEnabled: StateFlow<Boolean> = _autoMixEnabled

    private val _gaplessEnabled = MutableStateFlow(true)
    val gaplessEnabled: StateFlow<Boolean> = _gaplessEnabled

    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes: StateFlow<Int> = _sleepTimerMinutes

    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs

    private val _ignoreShortEnabled = MutableStateFlow(false)
    val ignoreShortEnabled: StateFlow<Boolean> = _ignoreShortEnabled

    private val _ignoreThresholdSec = MutableStateFlow(30f)
    val ignoreThresholdSec: StateFlow<Float> = _ignoreThresholdSec

    // ── Player State ──
    private val _lastTrackIndex = MutableStateFlow(-1)
    val lastTrackIndex: StateFlow<Int> = _lastTrackIndex

    private val _lastPositionMs = MutableStateFlow(0L)
    val lastPositionMs: StateFlow<Long> = _lastPositionMs

    private val _lastScreenIndex = MutableStateFlow(0)
    val lastScreenIndex: StateFlow<Int> = _lastScreenIndex

    // ── EQ Settings ──
    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled

    // ── Scan Folders ──
    private val _scanFolders = MutableStateFlow<List<String>>(emptyList())
    val scanFolders: StateFlow<List<String>> = _scanFolders

    private val _eqPreset = MutableStateFlow("Flat")
    val eqPreset: StateFlow<String> = _eqPreset

    private val _eqBands = MutableStateFlow(IntArray(5))
    val eqBands: StateFlow<IntArray> = _eqBands

    private val _bassBoost = MutableStateFlow(0)
    val bassBoost: StateFlow<Int> = _bassBoost

    private val _virtualizer = MutableStateFlow(0)
    val virtualizer: StateFlow<Int> = _virtualizer

    private val _loudness = MutableStateFlow(0)
    val loudness: StateFlow<Int> = _loudness

    // ── Explicit Content Filter ──
    private val _hideExplicit = MutableStateFlow(false)
    val hideExplicit: StateFlow<Boolean> = _hideExplicit

    // ── Sleep Timer ──
    private var sleepTimerJob: Job? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        loadAll()
    }

    // ═══════════════════════════════════════════
    //  Setters (save immediately)
    // ═══════════════════════════════════════════

    fun setAutoMix(enabled: Boolean) {
        _autoMixEnabled.value = enabled
        prefs.edit().putBoolean("automix", enabled).apply()
    }

    fun setGapless(enabled: Boolean) {
        _gaplessEnabled.value = enabled
        prefs.edit().putBoolean("gapless", enabled).apply()
    }

    fun setSleepTimer(minutes: Int) {
        _sleepTimerMinutes.value = minutes
        prefs.edit().putInt("sleep_timer", minutes).apply()
        startSleepTimer(minutes)
    }

    fun setIgnoreShort(enabled: Boolean) {
        _ignoreShortEnabled.value = enabled
        prefs.edit().putBoolean("ignore_short", enabled).apply()
    }

    fun setIgnoreThreshold(sec: Float) {
        _ignoreThresholdSec.value = sec
        prefs.edit().putFloat("ignore_threshold", sec).apply()
    }

    fun setLastTrackIndex(index: Int) {
        _lastTrackIndex.value = index
        prefs.edit().putInt("last_track", index).apply()
    }

    fun setLastPosition(ms: Long) {
        _lastPositionMs.value = ms
        prefs.edit().putLong("last_position", ms).apply()
    }

    fun setLastScreen(index: Int) {
        _lastScreenIndex.value = index
        prefs.edit().putInt("last_screen", index).apply()
    }

    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
        prefs.edit().putBoolean("eq_enabled", enabled).apply()
    }

    fun setEqPreset(name: String) {
        _eqPreset.value = name
        prefs.edit().putString("eq_preset", name).apply()
    }

    fun setEqBands(bands: IntArray) {
        _eqBands.value = bands.copyOf()
        val sb = bands.joinToString(",")
        prefs.edit().putString("eq_bands", sb).apply()
    }

    fun setBassBoost(value: Int) {
        _bassBoost.value = value
        prefs.edit().putInt("bass_boost", value).apply()
    }

    fun setVirtualizer(value: Int) {
        _virtualizer.value = value
        prefs.edit().putInt("virtualizer", value).apply()
    }

    fun setLoudness(value: Int) {
        _loudness.value = value
        prefs.edit().putInt("loudness", value).apply()
    }

    fun setHideExplicit(enabled: Boolean) {
        _hideExplicit.value = enabled
        prefs.edit().putBoolean("hide_explicit", enabled).apply()
    }

    // ── Scan Folders ──

    fun setScanFolders(folders: List<String>) {
        _scanFolders.value = folders
        prefs.edit().putString("scan_folders", folders.joinToString("|")).apply()
    }

    fun addScanFolder(path: String) {
        val current = _scanFolders.value.toMutableList()
        if (path !in current) {
            current.add(path)
            setScanFolders(current)
        }
    }

    fun removeScanFolder(path: String) {
        setScanFolders(_scanFolders.value.filter { it != path })
    }

    fun clearScanFolders() {
        setScanFolders(emptyList())
    }

    /**
     * Найти все папки с музыкой на устройстве.
     */
    fun discoverMusicFolders(context: android.content.Context): List<String> {
        val folders = mutableSetOf<String>()
        try {
            val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
            val selection = "${android.provider.MediaStore.Audio.Media.IS_MUSIC} != 0"

            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    val folder = path.substringBeforeLast("/")
                    if (folder.isNotBlank()) folders.add(folder)
                }
            }
        } catch (_: Exception) {}
        return folders.sorted()
    }

    /**
     * Сохранить текущее состояние плеера (вызывать при паузе/закрытии).
     */
    fun savePlayerState(trackIndex: Int, positionMs: Long) {
        setLastTrackIndex(trackIndex)
        setLastPosition(positionMs)
    }

    // ═══════════════════════════════════════════
    //  Sleep Timer logic
    // ═══════════════════════════════════════════

    private fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerRemainingMs.value = 0L
            return
        }

        val totalMs = minutes * 60_000L
        _sleepTimerRemainingMs.value = totalMs

        sleepTimerJob = scope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                delay(1000L)
                remaining -= 1000L
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0)
            }

            // Timer expired — fade out and pause
            val player = AudioService.companionPlayer
            if (player != null && player.isPlaying) {
                // Fade out over 30 seconds
                val fadeSteps = 60
                val stepMs = 500L
                val startVolume = player.volume
                for (i in 1..fadeSteps) {
                    val vol = startVolume * (1f - i.toFloat() / fadeSteps)
                    player.volume = vol.coerceAtLeast(0f)
                    delay(stepMs)
                }
                player.pause()
                player.volume = startVolume // restore for next play
            }

            _sleepTimerMinutes.value = 0
            _sleepTimerRemainingMs.value = 0L
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerMinutes.value = 0
        _sleepTimerRemainingMs.value = 0L
        prefs.edit().putInt("sleep_timer", 0).apply()
    }

    // ═══════════════════════════════════════════
    //  Load
    // ═══════════════════════════════════════════

    private fun loadAll() {
        _autoMixEnabled.value = prefs.getBoolean("automix", false)
        _gaplessEnabled.value = prefs.getBoolean("gapless", true)
        _sleepTimerMinutes.value = prefs.getInt("sleep_timer", 0)
        _ignoreShortEnabled.value = prefs.getBoolean("ignore_short", false)
        _ignoreThresholdSec.value = prefs.getFloat("ignore_threshold", 30f)

        _lastTrackIndex.value = prefs.getInt("last_track", -1)
        _lastPositionMs.value = prefs.getLong("last_position", 0L)
        _lastScreenIndex.value = prefs.getInt("last_screen", 0)

        _eqEnabled.value = prefs.getBoolean("eq_enabled", false)
        _hideExplicit.value = prefs.getBoolean("hide_explicit", false)
        _eqPreset.value = prefs.getString("eq_preset", "Flat") ?: "Flat"
        _bassBoost.value = prefs.getInt("bass_boost", 0)
        _virtualizer.value = prefs.getInt("virtualizer", 0)
        _loudness.value = prefs.getInt("loudness", 0)

        try {
            val bandsStr = prefs.getString("eq_bands", null)
            if (bandsStr != null) {
                _eqBands.value = bandsStr.split(",").map { it.toInt() }.toIntArray()
            }
        } catch (_: Exception) {}

        // Scan folders
        try {
            val foldersStr = prefs.getString("scan_folders", null)
            if (!foldersStr.isNullOrBlank()) {
                _scanFolders.value = foldersStr.split("|").filter { it.isNotBlank() }
            }
        } catch (_: Exception) {}
    }
}
