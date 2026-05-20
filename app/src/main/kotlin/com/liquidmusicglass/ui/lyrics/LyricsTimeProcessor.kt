package com.liquidmusicglass.ui.lyrics

import android.view.animation.PathInterpolator
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.liquidmusicglass.engine.LyricsParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Оптимизированный процессор синхронизации лирики.
 *
 * Архитектура:
 * - Monotonic Cursor Index: последовательный указатель на текущее слово,
 *   никакого глобального поиска по всему массиву каждый кадр.
 * - O(1) обновление: только сравнение с границами текущего слова.
 * - Anti-jitter: позиция и индекс никогда не уменьшаются в обычном режиме.
 *   Сброс только при ручном перемотке (>500мс скачок).
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

        /** Порог скачка позиции для сброса курсора (ручная перемотка). */
        const val SEEK_JUMP_THRESHOLD_MS = 500L
    }

    private val _pastWords = MutableStateFlow<List<WordToken>>(emptyList())
    val pastWords: StateFlow<List<WordToken>> = _pastWords.asStateFlow()

    private val _currentWords = MutableStateFlow<List<WordToken>>(emptyList())
    val currentWords: StateFlow<List<WordToken>> = _currentWords.asStateFlow()

    private val _currentLineProgress = MutableStateFlow(0f)
    val currentLineProgress: StateFlow<Float> = _currentLineProgress.asStateFlow()

    private val _currentLineIndex = MutableStateFlow(-1)
    val currentLineIndex: StateFlow<Int> = _currentLineIndex.asStateFlow()

    /** Monotonic cursor: индекс текущего слова во flat-списке всех слов. */
    private var currentWordIndex: Int = 0

    /** Последняя обработанная позиция (для anti-jitter и детекции перемотки). */
    private var lastProcessedPositionMs: Long = -1L

    /** Flat-список всех слов трека с глобальными таймингами. */
    private val allWords: List<FlatWord>

    /** Карта: индекс строки → диапазон индексов слов в allWords. */
    private val lineWordRanges: List<IntRange>

    init {
        val words = mutableListOf<FlatWord>()
        val ranges = mutableListOf<IntRange>()
        var wordGlobalIdx = 0

        for ((lineIdx, line) in lyrics.lines.withIndex()) {
            val wordsInLine = line.text.split(" ").filter { it.isNotBlank() }
            val lineStartMs = line.timeMs
            val lineEndMs = lyrics.lines.getOrNull(lineIdx + 1)?.timeMs
                ?: (line.timeMs + 5000L)
            val wordDuration = if (wordsInLine.isNotEmpty()) {
                (lineEndMs - lineStartMs).coerceAtLeast(500L) / wordsInLine.size
            } else 500L

            val rangeStart = wordGlobalIdx
            for ((wordIdx, text) in wordsInLine.withIndex()) {
                val startMs = lineStartMs + wordIdx * wordDuration
                val endMs = lineStartMs + (wordIdx + 1) * wordDuration
                words.add(
                    FlatWord(
                        text = text,
                        startMs = startMs,
                        endMs = endMs,
                        lineIndex = lineIdx,
                        wordIndexInLine = wordIdx
                    )
                )
                wordGlobalIdx++
            }
            ranges.add(rangeStart until wordGlobalIdx)
        }

        allWords = words
        lineWordRanges = ranges
    }

    /**
     * Обновляет состояние на основе текущей позиции плеера.
     * Использует Monotonic Cursor Index — O(1) вместо O(n).
     *
     * @param positionMs текущая позиция (уже интерполированная smooth time)
     */
    fun updatePosition(positionMs: Long) {
        if (!lyrics.isSynced || allWords.isEmpty()) return

        // Детекция ручной перемотки: если скачок > 500мс — сбрасываем курсор
        val isSeek = lastProcessedPositionMs >= 0 &&
                kotlin.math.abs(positionMs - lastProcessedPositionMs) > SEEK_JUMP_THRESHOLD_MS

        // Monotonic Time Guard: позиция никогда не идёт назад (только если не было seek)
        val safePosition = if (!isSeek && lastProcessedPositionMs >= 0) {
            kotlin.math.max(lastProcessedPositionMs, positionMs)
        } else positionMs

        lastProcessedPositionMs = safePosition

        if (isSeek) {
            // При перемотке — binary search для быстрого позиционирования
            currentWordIndex = findWordIndexByPosition(safePosition)
        }

        // Последовательное движение курсора вперёд
        while (currentWordIndex < allWords.size &&
            safePosition > allWords[currentWordIndex].endMs
        ) {
            currentWordIndex++
        }

        // Определяем текущую строку
        val currentLine = if (currentWordIndex < allWords.size) {
            allWords[currentWordIndex].lineIndex
        } else if (allWords.isNotEmpty()) {
            allWords.last().lineIndex
        } else -1

        _currentLineIndex.value = currentLine

        if (currentLine < 0 || currentLine >= lineWordRanges.size) {
            _pastWords.value = emptyList()
            _currentWords.value = emptyList()
            _currentLineProgress.value = 0f
            return
        }

        val range = lineWordRanges[currentLine]
        val past = mutableListOf<WordToken>()
        val current = mutableListOf<WordToken>()
        val upcoming = mutableListOf<WordToken>()

        for (idx in range) {
            val word = allWords[idx]
            when {
                safePosition >= word.endMs -> {
                    past.add(word.toToken(progress = 1f))
                }
                safePosition in word.startMs..word.endMs -> {
                    val rawProgress = (safePosition - word.startMs).toFloat() /
                            (word.endMs - word.startMs).toFloat()
                    val interpolated = LYRIC_PROGRESS_INTERPOLATOR.getInterpolation(
                        rawProgress.coerceIn(0f, 1f)
                    )
                    current.add(word.toToken(progress = interpolated))
                }
                else -> {
                    upcoming.add(word.toToken(progress = 0f))
                }
            }
        }

        _pastWords.value = past
        _currentWords.value = current

        // Вычисляем прогресс для всей строки целиком (0f..1f)
        val lineStartMs = allWords[range.first].startMs
        val lineEndMs = allWords[range.last].endMs
        val lineDuration = (lineEndMs - lineStartMs).coerceAtLeast(1L)
        val rawLineProgress = (safePosition - lineStartMs).toFloat() / lineDuration.toFloat()
        _currentLineProgress.value = LYRIC_PROGRESS_INTERPOLATOR.getInterpolation(
            rawLineProgress.coerceIn(0f, 1f)
        )
    }

    /**
     * Binary search для быстрого позиционирования при ручной перемотке.
     * O(log n) — вызывается только при seek, не каждый кадр.
     */
    private fun findWordIndexByPosition(positionMs: Long): Int {
        var low = 0
        var high = allWords.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (allWords[mid].startMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result.coerceIn(0, allWords.size - 1)
    }

    /**
     * Возвращает список слов текущей строки с их прогрессом.
     * Используется для пословного караоке-рендеринга.
     */
    fun getCurrentLineWords(): List<WordToken> {
        val currentLine = _currentLineIndex.value
        if (currentLine < 0 || currentLine >= lineWordRanges.size) return emptyList()

        val range = lineWordRanges[currentLine]
        return range.map { idx ->
            val word = allWords[idx]
            val safePosition = lastProcessedPositionMs
            val progress = when {
                safePosition >= word.endMs -> 1f
                safePosition <= word.startMs -> 0f
                else -> (safePosition - word.startMs).toFloat() / (word.endMs - word.startMs).toFloat()
            }
            word.toToken(progress = progress.coerceIn(0f, 1f))
        }
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

    /** Сброс состояния при смене трека. */
    fun reset() {
        currentWordIndex = 0
        lastProcessedPositionMs = -1L
        _currentLineIndex.value = -1
        _currentLineProgress.value = 0f
        _pastWords.value = emptyList()
        _currentWords.value = emptyList()
    }

    data class WordToken(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val progress: Float = 0f
    )

    /** Внутреннее представление слова с глобальными таймингами. */
    private data class FlatWord(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val lineIndex: Int,
        val wordIndexInLine: Int
    ) {
        fun toToken(progress: Float): WordToken = WordToken(
            text = text,
            startMs = startMs,
            endMs = endMs,
            progress = progress
        )
    }
}
