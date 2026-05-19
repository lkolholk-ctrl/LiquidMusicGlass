package com.liquidmusicglass.ui.lyrics

import android.view.animation.PathInterpolator
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.liquidmusicglass.engine.LyricsParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Оптимизированный процессор синхронизации лирики.
 *
 * Разделяет слова активной строки на три Flow-стейта (past/current/upcoming)
 * для исключения тяжёлого перебора массива в UI-потоке.
 *
 * Anti-jitter: позиция никогда не уменьшается (Math.max(lastSaved, current)).
 */
@Stable
class LyricsTimeProcessor(
    private val lyrics: LyricsParser.Lyrics
) {
    companion object {
        /** Saturation boost — как в графическом слое Apple Music. */
        const val SATURATION_BOOST = 2.5f

        /** Alpha для уже пропетых слов. */
        const val PAST_ALPHA = 0.5f

        /** Alpha для будущих слов. */
        const val UPCOMING_ALPHA = 0.35f

        /** Alpha чёрного scrim-слоя. */
        const val BLACK_SCRIM_ALPHA = 0.30f

        /** Alpha белого scrim-слоя (глянцевое свечение). */
        const val WHITE_SCRIM_ALPHA = 0.08f

        /** Blur radius для фона. */
        const val BACKGROUND_BLUR_DP = 25

        /** Интерполятор для плавного наплыва цвета (PathInterpolator). */
        val LYRIC_PROGRESS_INTERPOLATOR = PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)
    }

    private val _pastWords = MutableStateFlow<List<WordToken>>(emptyList())
    val pastWords: StateFlow<List<WordToken>> = _pastWords.asStateFlow()

    private val _currentWords = MutableStateFlow<List<WordToken>>(emptyList())
    val currentWords: StateFlow<List<WordToken>> = _currentWords.asStateFlow()

    private val _upcomingWords = MutableStateFlow<List<WordToken>>(emptyList())
    val upcomingWords: StateFlow<List<WordToken>> = _upcomingWords.asStateFlow()

    private val _currentLineIndex = MutableStateFlow(-1)
    val currentLineIndex: StateFlow<Int> = _currentLineIndex.asStateFlow()

    private var lastSavedPositionMs: Long = 0L

    /** Парсим строки на слова с равномерным распределением времени. */
    private val parsedLines: List<ParsedLine> = lyrics.lines.map { line ->
        val words = line.text.split(" ").filter { it.isNotBlank() }
        val wordDuration = if (words.isNotEmpty()) {
            // Предполагаем равномерное распределение; точные тайминги
            // слов в стандартном LRC отсутствуют — используем эвристику.
            val nextLineTime = lyrics.lines
                .dropWhile { it.timeMs <= line.timeMs }
                .firstOrNull()?.timeMs ?: (line.timeMs + 5000)
            (nextLineTime - line.timeMs).coerceAtLeast(500L) / words.size
        } else 500L

        ParsedLine(
            timeMs = line.timeMs,
            words = words.mapIndexed { idx, text ->
                WordToken(
                    text = text,
                    startMs = line.timeMs + idx * wordDuration,
                    endMs = line.timeMs + (idx + 1) * wordDuration
                )
            }
        )
    }

    /**
     * Обновляет состояние на основе текущей позиции плеера.
     * @param positionMs текущая позиция от ExoPlayer
     */
    fun updatePosition(positionMs: Long) {
        // Anti-jitter: позиция никогда не идёт назад
        val safePosition = kotlin.math.max(lastSavedPositionMs, positionMs)
        lastSavedPositionMs = safePosition

        if (!lyrics.isSynced || parsedLines.isEmpty()) return

        // Находим текущую строку
        var lineIdx = -1
        for (i in parsedLines.indices) {
            if (parsedLines[i].timeMs <= safePosition) {
                lineIdx = i
            } else {
                break
            }
        }

        _currentLineIndex.value = lineIdx

        if (lineIdx < 0 || lineIdx >= parsedLines.size) {
            _pastWords.value = emptyList()
            _currentWords.value = emptyList()
            _upcomingWords.value = emptyList()
            return
        }

        val line = parsedLines[lineIdx]
        val past = mutableListOf<WordToken>()
        val current = mutableListOf<WordToken>()
        val upcoming = mutableListOf<WordToken>()

        for (word in line.words) {
            when {
                safePosition >= word.endMs -> past.add(word)
                safePosition in word.startMs..word.endMs -> current.add(
                    word.copy(
                        progress = LYRIC_PROGRESS_INTERPOLATOR.getInterpolation(
                            ((safePosition - word.startMs).toFloat() /
                                    (word.endMs - word.startMs).toFloat()).coerceIn(0f, 1f)
                        )
                    )
                )
                else -> upcoming.add(word)
            }
        }

        _pastWords.value = past
        _currentWords.value = current
        _upcomingWords.value = upcoming
    }

    /**
     * Применяет HSV-трансформацию: boost saturation в SATURATION_BOOST раз.
     */
    fun boostSaturation(color: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[1] = (hsv[1] * SATURATION_BOOST).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** Сброс anti-jitter состояния при смене трека. */
    fun reset() {
        lastSavedPositionMs = 0L
        _currentLineIndex.value = -1
        _pastWords.value = emptyList()
        _currentWords.value = emptyList()
        _upcomingWords.value = emptyList()
    }

    data class WordToken(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val progress: Float = 0f
    )

    private data class ParsedLine(
        val timeMs: Long,
        val words: List<WordToken>
    )
}
