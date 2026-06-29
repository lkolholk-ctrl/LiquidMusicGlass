package com.liquidmusicglass.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.liquidmusicglass.engine.automix.AutoMixNativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.equalizerDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "equalizer_settings")

/**
 * 10-полосный графический эквалайзер поверх ВСЕГО локального аудио (движок JUCE).
 *
 * Состояние (вкл/выкл, 10 усилений в дБ, индекс пресета) персистится в DataStore
 * и зеркалируется в StateFlow для UI. Любое изменение тут же толкается в нативный
 * движок; на старте движка [applyToEngine] переотправляет текущее состояние
 * (движок грузится лениво — на первом реальном проигрывании).
 *
 * EQ применяется только к локальному пути (JUCE). Стриминг идёт через ExoPlayer и
 * этим эквалайзером не затрагивается — как и задумано.
 */
object EqualizerController {

    const val BAND_COUNT = 10
    const val MAX_GAIN_DB = 12f
    const val MIN_GAIN_DB = -12f

    /** Центры полос (Гц) — для подписей в UI. Совпадают с kEqCenters в нативке. */
    val BAND_FREQS_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** Короткие подписи под слайдерами. */
    val BAND_LABELS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    data class Preset(val name: String, val gains: FloatArray)

    /** Пресеты. Индекс 0 = Flat (плоский). */
    val PRESETS: List<Preset> = listOf(
        Preset("Flat",        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Bass Boost",  floatArrayOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Treble Boost",floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f)),
        Preset("Vocal",       floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f)),
        Preset("Rock",        floatArrayOf(5f, 4f, 2f, 0f, -1f, 0f, 2f, 4f, 5f, 5f)),
        Preset("Pop",         floatArrayOf(-1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f, -1f)),
        Preset("Jazz",        floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
        Preset("Classical",   floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
        Preset("Electronic",  floatArrayOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 2f, 4f, 5f)),
    )

    const val PRESET_CUSTOM = -1

    private val KEY_ENABLED = booleanPreferencesKey("eq_enabled")
    private val KEY_GAINS = stringPreferencesKey("eq_gains")     // CSV из 10 значений
    private val KEY_PRESET = intPreferencesKey("eq_preset")      // -1 = custom

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dataStore: DataStore<Preferences>? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _gains = MutableStateFlow(FloatArray(BAND_COUNT) { 0f }.toList())
    val gains: StateFlow<List<Float>> = _gains

    private val _presetIndex = MutableStateFlow(0)
    val presetIndex: StateFlow<Int> = _presetIndex

    /** Один раз из App.onCreate. */
    fun init(context: Context) {
        if (dataStore != null) return
        val ds = context.applicationContext.equalizerDataStore
        dataStore = ds
        scope.launch {
            runCatching { apply(ds.data.first()) }
            // После загрузки сохранённого состояния — на случай, если движок уже поднят.
            applyToEngine()
        }
    }

    private fun apply(p: Preferences) {
        _enabled.value = p[KEY_ENABLED] ?: false
        _gains.value = parseGains(p[KEY_GAINS])
        _presetIndex.value = p[KEY_PRESET] ?: 0
    }

    private fun parseGains(csv: String?): List<Float> {
        if (csv.isNullOrBlank()) return List(BAND_COUNT) { 0f }
        val parts = csv.split(',')
        return List(BAND_COUNT) { i ->
            parts.getOrNull(i)?.toFloatOrNull()?.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) ?: 0f
        }
    }

    private fun persist(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val ds = dataStore ?: return
        scope.launch { runCatching { ds.edit { block(it) } } }
    }

    // ── Публичные сеттеры (оптимистично обновляют зеркало + пишут + толкают в движок) ──

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        AutoMixNativeEngine.setEqEnabled(on)
        if (on) AutoMixNativeEngine.setEqBands(_gains.value.toFloatArray()) // гарантируем актуальные полосы
        persist { it[KEY_ENABLED] = on }
    }

    /** Сдвинуть одну полосу. Перестаёт совпадать с пресетом → presetIndex=custom. */
    fun setBand(band: Int, gainDb: Float) {
        if (band !in 0 until BAND_COUNT) return
        val v = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val updated = _gains.value.toMutableList().also { it[band] = v }
        _gains.value = updated
        AutoMixNativeEngine.setEqBandGain(band, v)
        val pidx = matchPreset(updated)
        _presetIndex.value = pidx
        persist {
            it[KEY_GAINS] = updated.joinToString(",")
            it[KEY_PRESET] = pidx
        }
    }

    fun applyPreset(index: Int) {
        val preset = PRESETS.getOrNull(index) ?: return
        val list = preset.gains.toList()
        _gains.value = list
        _presetIndex.value = index
        AutoMixNativeEngine.setEqBands(preset.gains.copyOf())
        persist {
            it[KEY_GAINS] = list.joinToString(",")
            it[KEY_PRESET] = index
        }
    }

    fun reset() = applyPreset(0)

    /** Текущее состояние → нативный движок (вызывать после успешного init движка). */
    fun applyToEngine() {
        AutoMixNativeEngine.setEqBands(_gains.value.toFloatArray())
        AutoMixNativeEngine.setEqEnabled(_enabled.value)
    }

    private fun matchPreset(gains: List<Float>): Int {
        for ((i, p) in PRESETS.withIndex()) {
            if (p.gains.indices.all { kotlin.math.abs(p.gains[it] - gains[it]) < 0.01f }) return i
        }
        return PRESET_CUSTOM
    }
}
