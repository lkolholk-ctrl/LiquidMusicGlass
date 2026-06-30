package com.liquidmusicglass.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.lyricsFxDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "lyrics_fx_settings")

/**
 * Настройки ПОСЛОВНОЙ (word-level) подсветки лирики: тип эффекта перетекания и
 * степень плавности. Применяются ТОЛЬКО к word-level лирике; строчный (line-level)
 * UI не трогаем. Персистятся в DataStore.
 */
object LyricsFxController {

    /** Эффекты перетекания подсветки по словам. */
    enum class WordEffect { FILL, FADE, RUNNING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dataStore: DataStore<Preferences>? = null

    private val K_EFFECT = intPreferencesKey("word_effect")
    private val K_SMOOTH = floatPreferencesKey("word_smoothness")

    private val _effect = MutableStateFlow(WordEffect.FILL)
    val effect: StateFlow<WordEffect> = _effect

    /** 0 = резче (быстрый, чёткий переход), 1 = очень плавно (мягкий, длинный). */
    private val _smoothness = MutableStateFlow(0.5f)
    val smoothness: StateFlow<Float> = _smoothness

    fun init(context: Context) {
        if (dataStore != null) return
        val ds = context.applicationContext.lyricsFxDataStore
        dataStore = ds
        scope.launch {
            runCatching {
                val p = ds.data.first()
                val idx = (p[K_EFFECT] ?: 0).coerceIn(0, WordEffect.values().lastIndex)
                _effect.value = WordEffect.values()[idx]
                _smoothness.value = (p[K_SMOOTH] ?: 0.5f).coerceIn(0f, 1f)
            }
        }
    }

    fun setEffect(e: WordEffect) {
        _effect.value = e
        persist { it[K_EFFECT] = e.ordinal }
    }

    fun setSmoothness(v: Float) {
        val x = v.coerceIn(0f, 1f)
        _smoothness.value = x
        persist { it[K_SMOOTH] = x }
    }

    /** Длительность tween (мс) для цветовой анимации активной строки — из плавности. */
    fun colorTweenMs(): Int = (90f + smoothness.value * 360f).toInt()      // 90..450

    /** Ширина мягкого края sweep как доля ширины строки — из плавности. */
    fun edgeSoftFrac(): Float = 0.02f + smoothness.value * 0.16f           // 0.02..0.18

    private fun persist(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val ds = dataStore ?: return
        scope.launch { runCatching { ds.edit { block(it) } } }
    }
}
