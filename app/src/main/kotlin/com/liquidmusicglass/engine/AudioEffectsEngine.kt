package com.liquidmusicglass.engine

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio Effects Engine — управляет EQ, Bass Boost, Virtualizer, Loudness.
 *
 * 10-полосный эквалайзер + пресеты + отдельные эффекты.
 */
object AudioEffectsEngine {

    // ── State ──
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _bandLevels = MutableStateFlow<IntArray>(IntArray(10))
    val bandLevels: StateFlow<IntArray> = _bandLevels

    private val _bassBoostStrength = MutableStateFlow(0)
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength

    private val _virtualizerStrength = MutableStateFlow(0)
    val virtualizerStrength: StateFlow<Int> = _virtualizerStrength

    private val _loudnessGain = MutableStateFlow(0)
    val loudnessGain: StateFlow<Int> = _loudnessGain

    private val _currentPreset = MutableStateFlow("Custom")
    val currentPreset: StateFlow<String> = _currentPreset

    // ── Audio effects ──
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentSessionId = 0

    // ── EQ info ──
    var numBands: Int = 5; private set
    var minLevel: Int = -1500; private set  // milliBel
    var maxLevel: Int = 1500; private set
    var bandFrequencies: IntArray = intArrayOf(60, 230, 910, 3000, 14000)
        private set

    // ── Presets ──
    data class Preset(val name: String, val levels: IntArray, val bass: Int, val virtualizer: Int)

    val presets = listOf(
        Preset("Flat", intArrayOf(0,0,0,0,0), 0, 0),
        Preset("Bass Boost", intArrayOf(600,400,0,0,0), 600, 0),
        Preset("Treble Boost", intArrayOf(0,0,0,400,600), 0, 0),
        Preset("V-Shape", intArrayOf(500,200,-200,200,500), 200, 0),
        Preset("Rock", intArrayOf(400,100,0,300,400), 300, 0),
        Preset("Pop", intArrayOf(-100,200,500,200,-100), 100, 0),
        Preset("Jazz", intArrayOf(300,100,0,200,400), 0, 200),
        Preset("Classical", intArrayOf(0,0,0,0,300), 0, 300),
        Preset("Hip-Hop", intArrayOf(500,300,0,100,300), 500, 200),
        Preset("EDM", intArrayOf(600,300,0,400,600), 700, 400),
        Preset("R&B", intArrayOf(300,400,0,200,200), 400, 200),
        Preset("Vocal", intArrayOf(-200,0,500,200,-200), 0, 300),
        Preset("Deep Bass", intArrayOf(800,500,0,0,0), 800, 0),
        Preset("Bright", intArrayOf(-200,0,0,500,700), 0, 0),
        Preset("Night Mode", intArrayOf(200,0,-200,0,200), 200, 500),
        Preset("Headphones", intArrayOf(300,100,0,200,400), 200, 600),
        Preset("Speaker", intArrayOf(500,200,0,300,500), 400, 0),
        Preset("Car", intArrayOf(400,100,0,200,500), 500, 300),
    )

    /**
     * Инициализация эффектов с audioSessionId.
     * При смене сессии (AutoMix swap) пересоздаёт эффекты
     * и применяет текущие настройки.
     * 
     * CRITICAL: На OEM-устройствах (Huawei/Honor/MagicOS) инициализация
     * Equalizer/Virtualizer может вызвать системный setParameters() для
     * DTS-эффектов, что блокирует поток из-за отсутствия разрешения.
     * При любой ошибке — полностью отключаем эффекты.
     */
    fun init(audioSessionId: Int) {
        if (audioSessionId == 0) return
        // При смене сессии — пересоздаём
        if (audioSessionId == currentSessionId && equalizer != null) return

        // Сохраняем текущие настройки
        val savedEnabled = _enabled.value
        val savedLevels = _bandLevels.value.copyOf()
        val savedBass = _bassBoostStrength.value
        val savedVirt = _virtualizerStrength.value
        val savedLoud = _loudnessGain.value

        release()
        currentSessionId = audioSessionId

        // CRITICAL FIX: Обернуть ВСЮ инициализацию в try-catch.
        // На Huawei/Honor создание Equalizer/Virtualizer вызывает
        // AudioManager.setParameters("dts_effect_enable") который
        // блокирует Main Thread из-за SecurityException.
        try {
            equalizer = Equalizer(0, audioSessionId).apply {
                val nb = numberOfBands.toInt()
                this@AudioEffectsEngine.numBands = nb
                val range = bandLevelRange
                this@AudioEffectsEngine.minLevel = range[0].toInt()
                this@AudioEffectsEngine.maxLevel = range[1].toInt()

                val freqs = IntArray(nb)
                for (i in 0 until nb) {
                    freqs[i] = (getCenterFreq(i.toShort()) / 1000)
                }
                this@AudioEffectsEngine.bandFrequencies = freqs

                // Применяем сохранённые уровни
                val levels = IntArray(nb)
                for (i in 0 until nb) {
                    val level = if (i < savedLevels.size) savedLevels[i] else 0
                    levels[i] = level.coerceIn(this@AudioEffectsEngine.minLevel, this@AudioEffectsEngine.maxLevel)
                    setBandLevel(i.toShort(), levels[i].toShort())
                }
                _bandLevels.value = levels

                enabled = savedEnabled
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioEffectsEngine", "Equalizer init failed (OEM blocked?): ${e.message}")
            equalizer = null
        }

        try {
            bassBoost = BassBoost(0, audioSessionId).apply {
                setStrength(savedBass.toShort())
                enabled = savedEnabled && savedBass > 0
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioEffectsEngine", "BassBoost init failed: ${e.message}")
            bassBoost = null
        }

        try {
            virtualizer = Virtualizer(0, audioSessionId).apply {
                setStrength(savedVirt.toShort())
                enabled = savedEnabled && savedVirt > 0
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioEffectsEngine", "Virtualizer init failed (OEM blocked?): ${e.message}")
            virtualizer = null
        }

        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(savedLoud)
                enabled = savedEnabled && savedLoud > 0
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioEffectsEngine", "LoudnessEnhancer init failed: ${e.message}")
            loudnessEnhancer = null
        }

        // Если ни один эффект не инициализировался — сбрасываем флаг
        if (equalizer == null && bassBoost == null && virtualizer == null && loudnessEnhancer == null) {
            android.util.Log.w("AudioEffectsEngine", "All effects failed to init — disabling EQ on this device")
            _enabled.value = false
        } else {
            _enabled.value = savedEnabled
        }
    }

    /**
     * Включение/выключение всех эффектов.
     * Безопасно для OEM-устройств — проверяет что эффекты инициализированы.
     */
    fun setEnabled(enable: Boolean) {
        _enabled.value = enable
        try { equalizer?.enabled = enable } catch (e: Exception) {
            android.util.Log.e("AudioEffectsEngine", "setEnabled EQ failed: ${e.message}")
        }
        try { bassBoost?.enabled = enable && _bassBoostStrength.value > 0 } catch (e: Exception) {
            android.util.Log.e("AudioEffectsEngine", "setEnabled Bass failed: ${e.message}")
        }
        try { virtualizer?.enabled = enable && _virtualizerStrength.value > 0 } catch (e: Exception) {
            android.util.Log.e("AudioEffectsEngine", "setEnabled Virt failed: ${e.message}")
        }
        try { loudnessEnhancer?.enabled = enable && _loudnessGain.value > 0 } catch (e: Exception) {
            android.util.Log.e("AudioEffectsEngine", "setEnabled Loud failed: ${e.message}")
        }
    }

    /**
     * Установка уровня одной полосы.
     * @param band индекс полосы (0..numBands-1)
     * @param level уровень в milliBel (minLevel..maxLevel)
     */
    fun setBandLevel(band: Int, level: Int) {
        val clamped = level.coerceIn(minLevel, maxLevel)
        try {
            equalizer?.setBandLevel(band.toShort(), clamped.toShort())
        } catch (_: Exception) {}

        val levels = _bandLevels.value.copyOf()
        if (band in levels.indices) {
            levels[band] = clamped
            _bandLevels.value = levels
        }
        _currentPreset.value = "Custom"
    }

    /**
     * Bass Boost (0–1000).
     */
    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _bassBoostStrength.value = clamped
        try {
            bassBoost?.apply {
                setStrength(clamped.toShort())
                enabled = _enabled.value && clamped > 0
            }
        } catch (_: Exception) {}
    }

    /**
     * Virtualizer / Surround (0–1000).
     */
    fun setVirtualizer(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _virtualizerStrength.value = clamped
        try {
            virtualizer?.apply {
                setStrength(clamped.toShort())
                enabled = _enabled.value && clamped > 0
            }
        } catch (_: Exception) {}
    }

    /**
     * Loudness Enhancer (0–1000 mB gain).
     */
    fun setLoudness(gainMb: Int) {
        val clamped = gainMb.coerceIn(0, 1000)
        _loudnessGain.value = clamped
        try {
            loudnessEnhancer?.apply {
                setTargetGain(clamped)
                enabled = _enabled.value && clamped > 0
            }
        } catch (_: Exception) {}
    }

    /**
     * Применение пресета.
     */
    fun applyPreset(preset: Preset) {
        _currentPreset.value = preset.name

        val levels = IntArray(numBands)
        for (i in 0 until numBands) {
            val level = if (i < preset.levels.size) preset.levels[i] else 0
            levels[i] = level.coerceIn(minLevel, maxLevel)
            try {
                equalizer?.setBandLevel(i.toShort(), levels[i].toShort())
            } catch (_: Exception) {}
        }
        _bandLevels.value = levels

        setBassBoost(preset.bass)
        setVirtualizer(preset.virtualizer)
    }

    /**
     * Сброс к Flat.
     */
    fun reset() {
        applyPreset(presets.first()) // Flat
        setLoudness(0)
    }

    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { virtualizer?.release() } catch (_: Exception) {}
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        currentSessionId = 0
    }

    /**
     * Форматирование частоты.
     */
    fun formatFreq(hz: Int): String {
        return if (hz >= 1000) "${hz / 1000}k" else "${hz}"
    }

    /**
     * Форматирование уровня.
     */
    fun formatLevel(mb: Int): String {
        val db = mb / 100f
        return if (db >= 0) "+%.1fdB".format(db) else "%.1fdB".format(db)
    }
}
