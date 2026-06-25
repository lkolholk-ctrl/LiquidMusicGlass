package com.liquidmusicglass.ui.lyrics

import android.view.animation.PathInterpolator
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.liquidmusicglass.engine.LyricsParser
import com.liquidmusicglass.engine.vad.VocalState
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

        /** Максимум на «визуальную» заливку слова — чтобы строка докрашивалась
         * за разумное время, а не ползла весь проигрыш до следующей строки. */
        const val MAX_WORD_FILL_MS = 700L

        /** Минимальная длительность визуальной заливки всей строки. */
        const val MIN_LINE_FILL_MS = 1500L

        /** Какой разрыв между концом заливки строки и след. строкой считаем
         * инструментальным проигрышем (для показа Waiting по VAD). */
        const val INTERLUDE_GAP_MS = 1500L
    }

    private val _pastWords = MutableStateFlow<List<WordToken>>(emptyList())
    val pastWords: StateFlow<List<WordToken>> = _pastWords.asStateFlow()

    private val _currentWords = MutableStateFlow<List<WordToken>>(emptyList())
    val currentWords: StateFlow<List<WordToken>> = _currentWords.asStateFlow()

    private val _currentLineProgress = MutableStateFlow(0f)
    val currentLineProgress: StateFlow<Float> = _currentLineProgress.asStateFlow()

    private val _currentLineIndex = MutableStateFlow(-1)
    val currentLineIndex: StateFlow<Int> = _currentLineIndex.asStateFlow()

    /**
     * Идёт ли сейчас инструментал/проигрыш по данным VAD-модели.
     * Пока true — заливка слов заморожена, UI показывает «Waiting».
     */
    private val _isInterlude = MutableStateFlow(false)
    val isInterlude: StateFlow<Boolean> = _isInterlude.asStateFlow()

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
            val n = wordsInLine.size.coerceAtLeast(1)
            // Растянутая длительность слова — задаёт ГРАНИЦЫ слова для курсора и
            // принадлежности к строке (курсор остаётся на строке до старта следующей,
            // поэтому в зазоре нет «бега вперёд»).
            val wordDuration = if (wordsInLine.isNotEmpty()) {
                (lineEndMs - lineStartMs).coerceAtLeast(500L) / n
            } else 500L
            // Капнутая длительность ЗАЛИВКИ — слова докрашиваются за разумное время
            // (≤700мс/слово), а не ползут весь проигрыш. Отвязана от границ курсора.
            val cappedLineFill = minOf(
                (lineEndMs - lineStartMs).coerceAtLeast(500L),
                (n * MAX_WORD_FILL_MS).coerceAtLeast(MIN_LINE_FILL_MS)
            )
            val fillWordDuration = (cappedLineFill / n).coerceAtLeast(1L)

            val rangeStart = wordGlobalIdx
            for ((wordIdx, text) in wordsInLine.withIndex()) {
                words.add(
                    FlatWord(
                        text = text,
                        startMs = lineStartMs + wordIdx * wordDuration,
                        endMs = lineStartMs + (wordIdx + 1) * wordDuration,
                        fillStartMs = lineStartMs + wordIdx * fillWordDuration,
                        fillEndMs = lineStartMs + (wordIdx + 1) * fillWordDuration,
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

        // ВАЖНО: VAD здесь НЕ трогает заливку и курсор. Заливка идёт строго по
        // (капнутым) таймингам LRC и всегда докрашивает строку до конца. VAD влияет
        // ТОЛЬКО на флаг _isInterlude (Waiting в зазоре между строками), считаемый
        // в самом конце метода.

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
            _isInterlude.value = false
            return
        }

        val range = lineWordRanges[currentLine]
        val past = mutableListOf<WordToken>()
        val current = mutableListOf<WordToken>()
        val upcoming = mutableListOf<WordToken>()

        for (idx in range) {
            val word = allWords[idx]
            when {
                safePosition >= word.fillEndMs -> {
                    past.add(word.toToken(progress = 1f))
                }
                safePosition in word.fillStartMs..word.fillEndMs -> {
                    val rawProgress = (safePosition - word.fillStartMs).toFloat() /
                            (word.fillEndMs - word.fillStartMs).toFloat()
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

        // Прогресс всей строки (0f..1f) по КАПНУТОЙ заливке — совпадает с пословной
        // заливкой выше (fillEnd), поэтому строка докрашивается ровно к lineFillEndMs.
        val lineStartMs = allWords[range.first].fillStartMs
        val lineFillEndMs = allWords[range.last].fillEndMs
        val fillDuration = (lineFillEndMs - lineStartMs).coerceAtLeast(1L)
        val rawLineProgress = (safePosition - lineStartMs).toFloat() / fillDuration.toFloat()
        _currentLineProgress.value = LYRIC_PROGRESS_INTERPOLATOR.getInterpolation(
            rawLineProgress.coerceIn(0f, 1f)
        )

        // ── VAD Waiting (единственное, на что влияет VAD). Показываем ТОЛЬКО когда:
        //   • строка уже полностью докрашена (pos >= lineFillEndMs),
        //   • до следующей строки заметный разрыв (инструментал),
        //   • мы ещё не дошли до следующей строки,
        //   • и VAD устойчиво говорит «нет вокала» (сглаживание+гистерезис+1.5с в движке).
        // Заливку это НЕ трогает — она уже завершена к этому моменту. ──
        val nextLineStart = lyrics.lines.getOrNull(currentLine + 1)?.timeMs
        val lineFullyDrawn = safePosition >= lineFillEndMs
        val bigGap = nextLineStart != null && (nextLineStart - lineFillEndMs) >= INTERLUDE_GAP_MS
        val beforeNextLine = nextLineStart == null || safePosition < nextLineStart
        val vadInstrumental = VocalState.enabled && !VocalState.isVocal
        _isInterlude.value = lineFullyDrawn && bigGap && beforeNextLine && vadInstrumental
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
            // Заливка слова по КАПНУТОМУ окну (как в updatePosition), не растянутому.
            val progress = when {
                safePosition >= word.fillEndMs -> 1f
                safePosition <= word.fillStartMs -> 0f
                else -> (safePosition - word.fillStartMs).toFloat() / (word.fillEndMs - word.fillStartMs).toFloat()
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
        _isInterlude.value = false
    }

    data class WordToken(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val progress: Float = 0f
    )

    /**
     * Внутреннее представление слова.
     * startMs/endMs — РАСТЯНУТЫЕ границы (до следующей строки): задают курсор и
     *   принадлежность к строке (в зазоре курсор остаётся на строке — нет «бега вперёд»).
     * fillStartMs/fillEndMs — КАПНУТОЕ окно ЗАЛИВКИ (≤700мс/слово): по нему красятся
     *   слова, чтобы строка докрашивалась за разумное время, а не ползла весь проигрыш.
     */
    private data class FlatWord(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val fillStartMs: Long,
        val fillEndMs: Long,
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
