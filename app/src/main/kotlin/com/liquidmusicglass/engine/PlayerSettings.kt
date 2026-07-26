package com.liquidmusicglass.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Один на процесс DataStore. Делегат обязан быть top-level расширением Context.
private val Context.playerSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "player_settings")

/**
 * Настройки плеера в Apple-Music-стиле, персистентные в **DataStore**.
 *
 * DataStore асинхронен (Flow), но плееру/кэшу/сети нужно читать значения
 * синхронно (на потоке DataSource, при сборке плеера). Поэтому каждое значение
 * **зеркалируется** в [MutableStateFlow]: UI читает через collectAsState(),
 * поведенческий код — синхронно через `.value`. Источник истины — DataStore;
 * StateFlow обновляется и оптимистично при записи, и при эмиссии из DataStore.
 *
 * Дефолты безопасны для холодного старта (до первой эмиссии DataStore): кэш
 * включён на 500 МБ, AutoMix включён, нормализация/контраст выключены.
 */
object PlayerSettings {

    // ── Audio cache (байты). 0 = кэш ВЫКЛЮЧЕН. ──
    val CACHE_OPTIONS_BYTES: List<Long> = listOf(
        0L,
        200L * 1024 * 1024,
        500L * 1024 * 1024,
        1024L * 1024 * 1024,
        2048L * 1024 * 1024,
        5120L * 1024 * 1024,
    )
    private const val DEFAULT_CACHE_BYTES = 500L * 1024 * 1024

    /** Кроссфейд: 0 = выкл. Верх выше эпловских 12 c — по запросу на длинные своды. */
    const val MIN_CROSSFADE_MS = 1_000
    const val MAX_CROSSFADE_MS = 18_000
    private const val DEFAULT_CROSSFADE_MS = 0

    private val KEY_CACHE_BYTES = longPreferencesKey("audio_cache_bytes")
    private val KEY_THEME_MODE = intPreferencesKey("theme_mode")  // 0=System 1=Dark 2=Light
    private val KEY_AUTO_MIX = booleanPreferencesKey("auto_mix")
    private val KEY_CROSSFADE_MS = intPreferencesKey("crossfade_ms")
    private val KEY_AUTO_DOWNLOAD_FAVORITES = booleanPreferencesKey("auto_download_favorites")
    private val KEY_VOLUME_NORMALIZATION = booleanPreferencesKey("volume_normalization")
    private val KEY_INCREASE_CONTRAST = booleanPreferencesKey("increase_contrast")

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dataStore: DataStore<Preferences>? = null

    // ── Зеркала (синхронное чтение через .value, реактивное — через collectAsState) ──
    private val _audioCacheBytes = MutableStateFlow(DEFAULT_CACHE_BYTES)
    val audioCacheBytes: StateFlow<Long> = _audioCacheBytes

    // Тема приложения: 0=Системная, 1=Тёмная, 2=Светлая. По умолчанию системная.
    private val _themeMode = MutableStateFlow(0)
    val themeMode: StateFlow<Int> = _themeMode

    // AutoMix (бесшовная авто-дозаправка волны; будущий движок кроссфейда/инференса
    // подписывается на этот же Flow). По умолчанию ВКЛЮЧЕН.
    private val _autoMix = MutableStateFlow(true)
    val autoMix: StateFlow<Boolean> = _autoMix

    /**
     * Длительность кроссфейда на стриминге, мс. 0 = выключен.
     *
     * Фиксированная величина от пользователя: ML-модель решала это сама, но на
     * стриминге она себя не оправдала. Оффлайн-движок (JUCE) её по-прежнему
     * использует и этой настройкой не управляется.
     */
    private val _crossfadeMs = MutableStateFlow(DEFAULT_CROSSFADE_MS)
    val crossfadeMs: StateFlow<Int> = _crossfadeMs

    /**
     * Держать избранное скачанным для игры без сети.
     *
     * По умолчанию выключено: качать музыку за спиной пользователя — это его
     * трафик и его место на диске.
     */
    private val _autoDownloadFavorites = MutableStateFlow(false)
    val autoDownloadFavorites: StateFlow<Boolean> = _autoDownloadFavorites

    private val _volumeNormalization = MutableStateFlow(false)
    val volumeNormalization: StateFlow<Boolean> = _volumeNormalization

    private val _increaseContrast = MutableStateFlow(false)
    val increaseContrast: StateFlow<Boolean> = _increaseContrast

    /** Вызывать один раз из App.onCreate (лёгкая операция — только подписка). */
    fun init(context: Context) {
        if (dataStore != null) return
        val ds = context.applicationContext.playerSettingsDataStore
        dataStore = ds
        scope.launch {
            // Приоритетно применяем сохранённое значение, затем слушаем изменения.
            runCatching { apply(ds.data.first()) }
            ds.data.collect { apply(it) }
        }
    }

    private fun apply(p: Preferences) {
        val newCache = p[KEY_CACHE_BYTES] ?: DEFAULT_CACHE_BYTES
        val cacheChanged = newCache != _audioCacheBytes.value
        _audioCacheBytes.value = newCache
        // Сохранённый размер кэша мог отличаться от дефолта, под которым кэш уже
        // собрался на старте — применяем его (rebuild идемпотентен, сам отсеет no-op).
        if (cacheChanged) MediaCacheManager.applyCacheSizeChange()
        _themeMode.value = p[KEY_THEME_MODE] ?: 0
        _autoMix.value = p[KEY_AUTO_MIX] ?: true
        _crossfadeMs.value = p[KEY_CROSSFADE_MS] ?: DEFAULT_CROSSFADE_MS
        _autoDownloadFavorites.value = p[KEY_AUTO_DOWNLOAD_FAVORITES] ?: false
        _volumeNormalization.value = p[KEY_VOLUME_NORMALIZATION] ?: false
        _increaseContrast.value = p[KEY_INCREASE_CONTRAST] ?: false
    }

    private fun persist(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val ds = dataStore ?: return
        scope.launch { runCatching { ds.edit { prefs -> block(prefs) } } }
    }

    // ── Setters: оптимистично обновляем зеркало + пишем в DataStore ──

    fun setAudioCacheBytes(bytes: Long) {
        val v = bytes.coerceAtLeast(0L)
        _audioCacheBytes.value = v
        persist { it[KEY_CACHE_BYTES] = v }
    }

    fun setThemeMode(mode: Int) {
        val m = mode.coerceIn(0, 2)
        _themeMode.value = m
        persist { it[KEY_THEME_MODE] = m }
    }

    fun setAutoMix(enabled: Boolean) {
        _autoMix.value = enabled
        persist { it[KEY_AUTO_MIX] = enabled }
    }

    /** @param ms 0 = выключить, иначе зажимается в [MIN_CROSSFADE_MS, MAX_CROSSFADE_MS]. */
    fun setCrossfadeMs(ms: Int) {
        val v = if (ms <= 0) 0 else ms.coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)
        _crossfadeMs.value = v
        persist { it[KEY_CROSSFADE_MS] = v }
    }

    fun setAutoDownloadFavorites(enabled: Boolean) {
        _autoDownloadFavorites.value = enabled
        persist { it[KEY_AUTO_DOWNLOAD_FAVORITES] = enabled }
    }

    fun setVolumeNormalization(enabled: Boolean) {
        _volumeNormalization.value = enabled
        persist { it[KEY_VOLUME_NORMALIZATION] = enabled }
    }

    fun setIncreaseContrast(enabled: Boolean) {
        _increaseContrast.value = enabled
        persist { it[KEY_INCREASE_CONTRAST] = enabled }
    }

    /** Кэш аудио включён (выбран ненулевой размер). */
    fun isCacheEnabled(): Boolean = _audioCacheBytes.value > 0L
}
