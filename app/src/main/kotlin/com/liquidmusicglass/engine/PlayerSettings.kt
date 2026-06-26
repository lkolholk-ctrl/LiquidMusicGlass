package com.liquidmusicglass.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
 * включён на 500 МБ, сотовые разрешены (как в Apple Music по умолчанию),
 * нормализация/эконом/контраст выключены.
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

    private val KEY_CACHE_BYTES = longPreferencesKey("audio_cache_bytes")
    private val KEY_AUTO_MIX = booleanPreferencesKey("auto_mix")
    private val KEY_VOLUME_NORMALIZATION = booleanPreferencesKey("volume_normalization")
    private val KEY_DATA_SAVER = booleanPreferencesKey("data_saver")
    private val KEY_CELLULAR_DATA = booleanPreferencesKey("cellular_data")
    private val KEY_CELLULAR_STREAMING = booleanPreferencesKey("cellular_streaming")
    private val KEY_CELLULAR_DOWNLOADS = booleanPreferencesKey("cellular_downloads")
    private val KEY_INCREASE_CONTRAST = booleanPreferencesKey("increase_contrast")

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dataStore: DataStore<Preferences>? = null

    // ── Зеркала (синхронное чтение через .value, реактивное — через collectAsState) ──
    private val _audioCacheBytes = MutableStateFlow(DEFAULT_CACHE_BYTES)
    val audioCacheBytes: StateFlow<Long> = _audioCacheBytes

    // AutoMix (бесшовная авто-дозаправка волны; будущий движок кроссфейда/инференса
    // подписывается на этот же Flow). По умолчанию ВКЛЮЧЕН.
    private val _autoMix = MutableStateFlow(true)
    val autoMix: StateFlow<Boolean> = _autoMix

    private val _volumeNormalization = MutableStateFlow(false)
    val volumeNormalization: StateFlow<Boolean> = _volumeNormalization

    private val _dataSaver = MutableStateFlow(false)
    val dataSaver: StateFlow<Boolean> = _dataSaver

    private val _cellularData = MutableStateFlow(true)
    val cellularData: StateFlow<Boolean> = _cellularData

    private val _cellularStreaming = MutableStateFlow(true)
    val cellularStreaming: StateFlow<Boolean> = _cellularStreaming

    private val _cellularDownloads = MutableStateFlow(true)
    val cellularDownloads: StateFlow<Boolean> = _cellularDownloads

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
        _autoMix.value = p[KEY_AUTO_MIX] ?: true
        _volumeNormalization.value = p[KEY_VOLUME_NORMALIZATION] ?: false
        _dataSaver.value = p[KEY_DATA_SAVER] ?: false
        _cellularData.value = p[KEY_CELLULAR_DATA] ?: true
        _cellularStreaming.value = p[KEY_CELLULAR_STREAMING] ?: true
        _cellularDownloads.value = p[KEY_CELLULAR_DOWNLOADS] ?: true
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

    fun setAutoMix(enabled: Boolean) {
        _autoMix.value = enabled
        persist { it[KEY_AUTO_MIX] = enabled }
    }

    fun setVolumeNormalization(enabled: Boolean) {
        _volumeNormalization.value = enabled
        persist { it[KEY_VOLUME_NORMALIZATION] = enabled }
    }

    fun setDataSaver(enabled: Boolean) {
        _dataSaver.value = enabled
        persist { it[KEY_DATA_SAVER] = enabled }
    }

    fun setCellularData(enabled: Boolean) {
        _cellularData.value = enabled
        persist { it[KEY_CELLULAR_DATA] = enabled }
    }

    fun setCellularStreaming(enabled: Boolean) {
        _cellularStreaming.value = enabled
        persist { it[KEY_CELLULAR_STREAMING] = enabled }
    }

    fun setCellularDownloads(enabled: Boolean) {
        _cellularDownloads.value = enabled
        persist { it[KEY_CELLULAR_DOWNLOADS] = enabled }
    }

    fun setIncreaseContrast(enabled: Boolean) {
        _increaseContrast.value = enabled
        persist { it[KEY_INCREASE_CONTRAST] = enabled }
    }

    // ── Производные правила доступа по сети (синхронные) ──

    /** Кэш аудио включён (выбран ненулевой размер). */
    fun isCacheEnabled(): Boolean = _audioCacheBytes.value > 0L

    /**
     * Разрешён ли СТРИМИНГ прямо сейчас. На Wi-Fi/безлимите — всегда. На сотовой —
     * только если включён общий доступ к сотовым И стриминг по сотовой.
     */
    fun streamingAllowed(): Boolean {
        if (NetworkMonitor.isUnmetered()) return true
        return _cellularData.value && _cellularStreaming.value
    }

    /** Разрешены ли ЗАГРУЗКИ прямо сейчас (та же логика, своя под-настройка). */
    fun downloadsAllowed(): Boolean {
        if (NetworkMonitor.isUnmetered()) return true
        return _cellularData.value && _cellularDownloads.value
    }
}
