package com.liquidmusicglass.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

private val Context.audioFxDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "audio_fx_settings")

/**
 * Профессиональная аудио-обработка поверх ВСЕГО локального аудио (JUCE-цепочка):
 *   Preamp(log) → EQ(10) → Bass Boost → Loudness comp → Stereo Width
 *                → Compressor → Limiter.
 *
 * Все параметры юзер крутит в UI → тут же уходят в нативную цепочку (real-time),
 * персистятся в DataStore и переотправляются при ленивом подъёме движка.
 * Стриминг (ExoPlayer) НЕ затрагивается. AutoMix/RT/fast-path — тоже.
 */
object AudioFxController {

    // ── EQ ────────────────────────────────────────────────────────────────
    const val BAND_COUNT = 10
    const val EQ_MAX_DB = 12f
    const val EQ_MIN_DB = -12f
    val BAND_LABELS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    data class EqPreset(val name: String, val gains: FloatArray)
    val EQ_PRESETS: List<EqPreset> = listOf(
        EqPreset("Flat",        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Bass Boost",  floatArrayOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Vocal",       floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f)),
        EqPreset("Rock",        floatArrayOf(5f, 4f, 2f, 0f, -1f, 0f, 2f, 4f, 5f, 5f)),
        EqPreset("Electronic",  floatArrayOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 2f, 4f, 5f)),
        EqPreset("Deep House",  floatArrayOf(6f, 6f, 4f, 2f, 0f, -1f, 0f, 1f, 2f, 3f)),
    )
    const val EQ_PRESET_CUSTOM = -1

    // ── Compressor пресеты ──────────────────────────────────────────────────
    data class CompPreset(val name: String, val threshDb: Float, val ratio: Float, val attackMs: Float, val releaseMs: Float)
    val COMP_PRESETS: List<CompPreset> = listOf(
        CompPreset("Soft",   -12f, 2f, 50f, 200f),
        CompPreset("Medium", -18f, 3f, 20f, 150f),
        CompPreset("Hard",   -24f, 4f, 5f,  100f),
    )

    // ── Дефолты ─────────────────────────────────────────────────────────────
    const val PREAMP_MIN_DB = -60f
    const val PREAMP_MAX_DB = 0f
    const val BASS_FREQ_MIN = 40f
    const val BASS_FREQ_MAX = 200f
    private const val LIM_RELEASE_MS = 50f

    // ── Состояние (StateFlow для UI) ────────────────────────────────────────
    private val _masterEnabled = MutableStateFlow(true)
    val masterEnabled: StateFlow<Boolean> = _masterEnabled

    private val _preamp01 = MutableStateFlow(1f)            // 0..1 (1 = 0 dB)
    val preamp01: StateFlow<Float> = _preamp01

    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled
    private val _eqGains = MutableStateFlow(List(BAND_COUNT) { 0f })
    val eqGains: StateFlow<List<Float>> = _eqGains
    private val _eqPreset = MutableStateFlow(0)
    val eqPreset: StateFlow<Int> = _eqPreset

    private val _bassEnabled = MutableStateFlow(false)
    val bassEnabled: StateFlow<Boolean> = _bassEnabled
    private val _bassFreq = MutableStateFlow(80f)
    val bassFreq: StateFlow<Float> = _bassFreq
    private val _bassGain = MutableStateFlow(6f)            // 0..12 dB
    val bassGain: StateFlow<Float> = _bassGain

    private val _loudnessEnabled = MutableStateFlow(false)
    val loudnessEnabled: StateFlow<Boolean> = _loudnessEnabled

    private val _stereoWidth = MutableStateFlow(1f)         // 0..2 (1 = норма)
    val stereoWidth: StateFlow<Float> = _stereoWidth

    private val _compEnabled = MutableStateFlow(false)
    val compEnabled: StateFlow<Boolean> = _compEnabled
    private val _compPreset = MutableStateFlow(1)           // Medium
    val compPreset: StateFlow<Int> = _compPreset

    private val _limEnabled = MutableStateFlow(true)        // вкл по умолчанию (анти-клиппинг)
    val limEnabled: StateFlow<Boolean> = _limEnabled
    private val _limThreshold = MutableStateFlow(-1f)       // -6..0 dB
    val limThreshold: StateFlow<Float> = _limThreshold

    // ── DataStore ───────────────────────────────────────────────────────────
    private val K_MASTER = booleanPreferencesKey("master")
    private val K_PREAMP = floatPreferencesKey("preamp01")
    private val K_EQ_ON = booleanPreferencesKey("eq_on")
    private val K_EQ_GAINS = stringPreferencesKey("eq_gains")
    private val K_EQ_PRESET = intPreferencesKey("eq_preset")
    private val K_BASS_ON = booleanPreferencesKey("bass_on")
    private val K_BASS_FREQ = floatPreferencesKey("bass_freq")
    private val K_BASS_GAIN = floatPreferencesKey("bass_gain")
    private val K_LOUD_ON = booleanPreferencesKey("loud_on")
    private val K_WIDTH = floatPreferencesKey("width")
    private val K_COMP_ON = booleanPreferencesKey("comp_on")
    private val K_COMP_PRESET = intPreferencesKey("comp_preset")
    private val K_LIM_ON = booleanPreferencesKey("lim_on")
    private val K_LIM_THRESH = floatPreferencesKey("lim_thresh")

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dataStore: DataStore<Preferences>? = null
    private var audioManager: AudioManager? = null

    fun init(context: Context) {
        if (dataStore != null) return
        val app = context.applicationContext
        val ds = app.audioFxDataStore
        dataStore = ds
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        scope.launch {
            runCatching { apply(ds.data.first()) }
            applyToEngine()
            refreshSystemVolume()
        }
        // Следим за системной громкостью (для loudness-компенсации).
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { refreshSystemVolume() }
            }, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        }
    }

    private fun apply(p: Preferences) {
        _masterEnabled.value = p[K_MASTER] ?: true
        _preamp01.value = (p[K_PREAMP] ?: 1f).coerceIn(0f, 1f)
        _eqEnabled.value = p[K_EQ_ON] ?: false
        _eqGains.value = parseGains(p[K_EQ_GAINS])
        _eqPreset.value = p[K_EQ_PRESET] ?: 0
        _bassEnabled.value = p[K_BASS_ON] ?: false
        _bassFreq.value = (p[K_BASS_FREQ] ?: 80f).coerceIn(BASS_FREQ_MIN, BASS_FREQ_MAX)
        _bassGain.value = (p[K_BASS_GAIN] ?: 6f).coerceIn(0f, 12f)
        _loudnessEnabled.value = p[K_LOUD_ON] ?: false
        _stereoWidth.value = (p[K_WIDTH] ?: 1f).coerceIn(0f, 2f)
        _compEnabled.value = p[K_COMP_ON] ?: false
        _compPreset.value = (p[K_COMP_PRESET] ?: 1).coerceIn(0, COMP_PRESETS.lastIndex)
        _limEnabled.value = p[K_LIM_ON] ?: true
        _limThreshold.value = (p[K_LIM_THRESH] ?: -1f).coerceIn(-6f, 0f)
    }

    private fun parseGains(csv: String?): List<Float> {
        if (csv.isNullOrBlank()) return List(BAND_COUNT) { 0f }
        val parts = csv.split(',')
        return List(BAND_COUNT) { i -> parts.getOrNull(i)?.toFloatOrNull()?.coerceIn(EQ_MIN_DB, EQ_MAX_DB) ?: 0f }
    }

    private fun persist(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val ds = dataStore ?: return
        scope.launch { runCatching { ds.edit { block(it) } } }
    }

    // ── Толкаем всё текущее состояние в движок ──────────────────────────────
    fun applyToEngine() {
        val e = AutoMixNativeEngine
        e.fxSetMasterEnabled(_masterEnabled.value)
        e.fxSetPreampGainDb(preampDb())
        e.setEqEnabled(_eqEnabled.value)
        e.setEqBands(_eqGains.value.toFloatArray())
        e.fxSetBassBoost(_bassEnabled.value, _bassFreq.value, _bassGain.value)
        e.fxSetLoudnessEnabled(_loudnessEnabled.value)
        e.fxSetStereoWidth(_stereoWidth.value)
        val cp = COMP_PRESETS[_compPreset.value]
        e.fxSetCompressor(_compEnabled.value, cp.threshDb, cp.ratio, cp.attackMs, cp.releaseMs)
        e.fxSetLimiter(_limEnabled.value, _limThreshold.value, LIM_RELEASE_MS)
    }

    private fun preampDb(): Float = PREAMP_MIN_DB + (PREAMP_MAX_DB - PREAMP_MIN_DB) * _preamp01.value

    /** Прочитать системную громкость музыки и отдать в движок (loudness-компенсация). */
    fun refreshSystemVolume() {
        val am = audioManager ?: return
        runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            AutoMixNativeEngine.fxSetCurrentVolume((cur.toFloat() / max).coerceIn(0f, 1f))
        }
    }

    // ── Сеттеры из UI ───────────────────────────────────────────────────────
    fun setMasterEnabled(on: Boolean) {
        _masterEnabled.value = on
        AutoMixNativeEngine.fxSetMasterEnabled(on)
        persist { it[K_MASTER] = on }
    }

    fun setPreamp01(v: Float) {
        val x = v.coerceIn(0f, 1f)
        _preamp01.value = x
        AutoMixNativeEngine.fxSetPreampGainDb(preampDb())
        persist { it[K_PREAMP] = x }
    }

    fun setEqEnabled(on: Boolean) {
        _eqEnabled.value = on
        AutoMixNativeEngine.setEqEnabled(on)
        if (on) AutoMixNativeEngine.setEqBands(_eqGains.value.toFloatArray())
        persist { it[K_EQ_ON] = on }
    }

    fun setEqBand(band: Int, gainDb: Float) {
        if (band !in 0 until BAND_COUNT) return
        val v = gainDb.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
        val updated = _eqGains.value.toMutableList().also { it[band] = v }
        _eqGains.value = updated
        AutoMixNativeEngine.setEqBandGain(band, v)
        val pidx = matchEqPreset(updated)
        _eqPreset.value = pidx
        persist { it[K_EQ_GAINS] = updated.joinToString(","); it[K_EQ_PRESET] = pidx }
    }

    fun applyEqPreset(index: Int) {
        val preset = EQ_PRESETS.getOrNull(index) ?: return
        val list = preset.gains.toList()
        _eqGains.value = list
        _eqPreset.value = index
        AutoMixNativeEngine.setEqBands(preset.gains.copyOf())
        persist { it[K_EQ_GAINS] = list.joinToString(","); it[K_EQ_PRESET] = index }
    }

    fun setBassEnabled(on: Boolean) {
        _bassEnabled.value = on
        AutoMixNativeEngine.fxSetBassBoost(on, _bassFreq.value, _bassGain.value)
        persist { it[K_BASS_ON] = on }
    }

    fun setBassFreq(hz: Float) {
        val v = hz.coerceIn(BASS_FREQ_MIN, BASS_FREQ_MAX)
        _bassFreq.value = v
        AutoMixNativeEngine.fxSetBassBoost(_bassEnabled.value, v, _bassGain.value)
        persist { it[K_BASS_FREQ] = v }
    }

    fun setBassGain(db: Float) {
        val v = db.coerceIn(0f, 12f)
        _bassGain.value = v
        AutoMixNativeEngine.fxSetBassBoost(_bassEnabled.value, _bassFreq.value, v)
        persist { it[K_BASS_GAIN] = v }
    }

    fun setLoudnessEnabled(on: Boolean) {
        _loudnessEnabled.value = on
        AutoMixNativeEngine.fxSetLoudnessEnabled(on)
        if (on) refreshSystemVolume()
        persist { it[K_LOUD_ON] = on }
    }

    fun setStereoWidth(w: Float) {
        val v = w.coerceIn(0f, 2f)
        _stereoWidth.value = v
        AutoMixNativeEngine.fxSetStereoWidth(v)
        persist { it[K_WIDTH] = v }
    }

    fun setCompEnabled(on: Boolean) {
        _compEnabled.value = on
        val cp = COMP_PRESETS[_compPreset.value]
        AutoMixNativeEngine.fxSetCompressor(on, cp.threshDb, cp.ratio, cp.attackMs, cp.releaseMs)
        persist { it[K_COMP_ON] = on }
    }

    fun setCompPreset(index: Int) {
        val i = index.coerceIn(0, COMP_PRESETS.lastIndex)
        _compPreset.value = i
        val cp = COMP_PRESETS[i]
        AutoMixNativeEngine.fxSetCompressor(_compEnabled.value, cp.threshDb, cp.ratio, cp.attackMs, cp.releaseMs)
        persist { it[K_COMP_PRESET] = i }
    }

    fun setLimEnabled(on: Boolean) {
        _limEnabled.value = on
        AutoMixNativeEngine.fxSetLimiter(on, _limThreshold.value, LIM_RELEASE_MS)
        persist { it[K_LIM_ON] = on }
    }

    fun setLimThreshold(db: Float) {
        val v = db.coerceIn(-6f, 0f)
        _limThreshold.value = v
        AutoMixNativeEngine.fxSetLimiter(_limEnabled.value, v, LIM_RELEASE_MS)
        persist { it[K_LIM_THRESH] = v }
    }

    /** Полный сброс к дефолтам (Flat EQ + выкл эффекты, лимитер вкл). */
    fun resetAll() {
        setPreamp01(1f)
        applyEqPreset(0)
        setEqEnabled(false)
        setBassEnabled(false); setBassFreq(80f); setBassGain(6f)
        setLoudnessEnabled(false)
        setStereoWidth(1f)
        setCompEnabled(false); setCompPreset(1)
        setLimEnabled(true); setLimThreshold(-1f)
    }

    private fun matchEqPreset(gains: List<Float>): Int {
        for ((i, p) in EQ_PRESETS.withIndex())
            if (p.gains.indices.all { kotlin.math.abs(p.gains[it] - gains[it]) < 0.01f }) return i
        return EQ_PRESET_CUSTOM
    }
}
