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

    private val KEY_CACHE_BYTES = longPreferencesKey("audio_cache_bytes")
    private val KEY_AUTO_MIX = booleanPreferencesKey("auto_mix")
    private val KEY_VOLUME_NORMALIZATION = booleanPreferencesKey("volume_normalization")
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

    fun setIncreaseContrast(enabled: Boolean) {
        _increaseContrast.value = enabled
        persist { it[KEY_INCREASE_CONTRAST] = enabled }
    }

    /** Кэш аудио включён (выбран ненулевой размер). */
    fun isCacheEnabled(): Boolean = _audioCacheBytes.value > 0L
}
