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
 * Процессор синхронизации лирики для LRCLIB (ПОСТРОЧНЫЕ тайминги, без word-таймингов).
 *
 * Закрас строки считается СЕГМЕНТНО-ПУНКТУАЦИОННОЙ моделью по содержимому, а не по
 * растяжке слов до следующей строки (это убирает «улитку» — медленное ползание
 * закраса во время проигрыша):
 *  - длительность строки оценивается по длине текста (MS_PER_CHAR), но не больше
 *    реального гэпа до следующей строки (закрас всегда укладывается в вокал);
 *  - строка режется на сегменты по знакам препинания; после знака — короткий HOLD
 *    (закрас держится, НЕ Waiting), маленький и адаптивный (кап ≤18% длительности),
 *    чтобы на быстром рэпе закрас не отставал;
 *  - внутри сегмента закрас линейный по символам.
 *
 * Waiting (точки ожидания) — ОТДЕЛЬНЫЙ механизм: только когда строка докрашена
 * ПОЛНОСТЬЮ и до следующей строки разрыв > WAIT_GAP_MS (реальный проигрыш).
 *
 * VAD-модель НЕ используется (косячная): экран лирики её больше не запускает.
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

        // ─── Сегментно-пунктуационная модель ───
        /** Базовая оценка длительности на символ. Снижено 90→72: на капелле/быстром
         * пении 90мс/симв давало хвостовую «улитку» — закрас доползал уже после того,
         * как голос смолк. Меньше = закрас быстрее, лучше укладывается в вокал. */
        const val MS_PER_CHAR = 72L
        /** Нижняя граница оценки длительности строки (если гэп позволяет). */
        const val MIN_MS = 1200L
        /** Синтетическая длительность последней строки (нет следующей). */
        const val LAST_LINE_MS = 5000L
        /** Базовая пауза после сильного знака (. ! ? … — –). Маленькая. */
        const val STRONG_PAUSE_MS = 180L
        /** Базовая пауза после слабого знака (, ; :). Ещё меньше. */
        const val WEAK_PAUSE_MS = 100L
        /** Кап на суммарную паузу строки: не больше доли длительности. */
        const val PAUSE_CAP_FRAC = 0.18
        /** На сам закрас всегда отдаём минимум эту долю длительности. */
        const val FILL_FLOOR_FRAC = 0.5
        /** Разрыв после полной докраски, считающийся проигрышем → Waiting. */
        const val WAIT_GAP_MS = 2000L

        private val STRONG_PUNCT = setOf('.', '!', '?', '…', '—', '–')
        private val WEAK_PUNCT = setOf(',', ';', ':')

        /** Удаляем дуэт-теги ([M:/[F:/[D:…) — тайминг по видимому тексту. */
        private val DUET_TAG = Regex("""\[(M|F|D|Male|Female|Duet):?\s*""", RegexOption.IGNORE_CASE)
    }

    // pastWords/currentWords оставлены для совместимости API (рендер активной строки
    // идёт по currentLineProgress, не по словам).
    private val _pastWords = MutableStateFlow<List<WordToken>>(emptyList())
    val pastWords: StateFlow<List<WordToken>> = _pastWords.asStateFlow()

    private val _currentWords = MutableStateFlow<List<WordToken>>(emptyList())
    val currentWords: StateFlow<List<WordToken>> = _currentWords.asStateFlow()

    private val _currentLineProgress = MutableStateFlow(0f)
    val currentLineProgress: StateFlow<Float> = _currentLineProgress.asStateFlow()

    private val _currentLineIndex = MutableStateFlow(-1)
    val currentLineIndex: StateFlow<Int> = _currentLineIndex.asStateFlow()

    /** Идёт ли сейчас инструментальный проигрыш между строками → показывать Waiting. */
    private val _isInterlude = MutableStateFlow(false)
    val isInterlude: StateFlow<Boolean> = _isInterlude.asStateFlow()

    /** Прогресс текущего ожидания 0..1 (интро до первой строки или проигрыш):
     *  точки WaitingDots «наливаются» по нему и схлопываются к концу. */
    private val _interludeProgress = MutableStateFlow(0f)
    val interludeProgress: StateFlow<Float> = _interludeProgress.asStateFlow()

    /** Монотонный курсор по строкам. */
    private var lineCursor = 0
    private var lastProcessedPositionMs = -1L

    /**
     * Автокалибровка темпа: сырой сэмпл «мс на символ» ПО КАЖДОЙ строке
     * (гэп до следующей / длина текста), null = строка не показательна
     * (выкрик, проигрыш). VAD не используется (модель косячная).
     */
    private val rateSamples: List<Double?> = lyrics.lines.mapIndexed { idx, line ->
        val next = lyrics.lines.getOrNull(idx + 1)?.timeMs ?: return@mapIndexed null
        val text = line.text.replace(DUET_TAG, "").trim()
        if (text.length < 6) return@mapIndexed null      // выкрики не показательны
        val gap = next - line.timeMs
        if (gap <= 0L || gap > 15_000L) return@mapIndexed null  // проигрыши не считаем
        gap.toDouble() / text.length
    }

    /** П40 по набору сэмплов (чуть ниже медианы: гэпы включают дыхание —
     *  сам вокал быстрее), клампы 40..160 мс/симв. */
    private fun p40Rate(samples: List<Double>): Long? {
        if (samples.size < 4) return null
        val sorted = samples.sorted()
        return sorted[(sorted.size * 2) / 5].toLong().coerceIn(40L, 160L)
    }

    /** Глобальный темп трека — фолбэк, когда локального окна не хватает. */
    private val trackMsPerChar: Long =
        p40Rate(rateSamples.filterNotNull()) ?: MS_PER_CHAR

    /**
     * ЛОКАЛЬНЫЙ темп строки: п40 по окну ±5 соседних строк. Куплет и припев
     * одной песни часто идут с разной скоростью (Godzilla: рэп 10 слов/с и
     * тянучий припев) — глобальный темп мажет по обоим, окно даёт каждой
     * секции свой.
     */
    private fun rateForLine(idx: Int): Long {
        val local = ArrayList<Double>(11)
        for (j in (idx - 5)..(idx + 5)) {
            rateSamples.getOrNull(j)?.let { local.add(it) }
        }
        return p40Rate(local) ?: trackMsPerChar
    }

    /** Предрасчитанная заливка по строкам. */
    private val lineFills: List<LineFill>

    init {
        val fills = ArrayList<LineFill>(lyrics.lines.size)
        for ((idx, line) in lyrics.lines.withIndex()) {
            val next = lyrics.lines.getOrNull(idx + 1)?.timeMs
            val fill = if (line.words.isNotEmpty()) {
                // Word-level (Enhanced LRC): закрас идёт по таймкодам слов (караоке).
                buildWordLineFill(line, next)
            } else {
                val text = line.text.replace(DUET_TAG, "").trim()
                buildLineFill(line.timeMs, next, text, rateForLine(idx))
            }
            fills.add(fill)
        }
        lineFills = fills
    }

    /** Таймлайн заливки строки по ПОСЛОВНЫМ таймкодам (Enhanced LRC). */
    private fun buildWordLineFill(line: LyricsParser.LyricLine, nextStartMs: Long?): LineFill {
        val words = line.words
        val spans = ArrayList<WordSpan>(words.size)
        var charPos = 0
        for ((i, w) in words.withIndex()) {
            val start = w.timeMs
            // Последнее слово последней строки: длительность от темпа трека.
            val end = if (i + 1 < words.size) words[i + 1].timeMs
                      else (nextStartMs
                          ?: (start + (w.text.length * trackMsPerChar).coerceIn(500L, LAST_LINE_MS)))
            // длина слова + пробел после (кроме последнего) — закрас «течёт» и через пробел
            val len = (w.text.length + if (i < words.size - 1) 1 else 0).coerceAtLeast(1)
            spans.add(WordSpan(charPos, len, start, end.coerceAtLeast(start + 1)))
            charPos += len
        }
        val totalChars = charPos.coerceAtLeast(1)
        val lastEnd = spans.lastOrNull()?.endMs ?: (line.timeMs + LAST_LINE_MS)
        val estMs = (lastEnd - line.timeMs).coerceAtLeast(1L)
        return LineFill(
            startMs = line.timeMs,
            nextStartMs = nextStartMs ?: lastEnd,
            hasNext = nextStartMs != null,
            totalChars = totalChars,
            estMs = estMs,
            segments = emptyList(),
            wordTimeline = spans
        )
    }

    /** Строит таймлайн заливки одной строки ([rate] — локальный темп мс/симв). */
    private fun buildLineFill(startMs: Long, nextStartMs: Long?, text: String, rate: Long): LineFill {
        val length = text.length.coerceAtLeast(1)
        // Последняя строка (нет следующей): синтетический «гэп» от темпа ЭТОГО
        // трека, а не фикс LAST_LINE_MS — финал баллады держится дольше,
        // финал рэпа не тянется.
        val gap = if (nextStartMs != null) (nextStartMs - startMs).coerceAtLeast(1L)
                  else (length * rate * 6 / 5).coerceIn(2000L, 12_000L)

        // Floor длительности строки от ЛОКАЛЬНОГО темпа: короткий выкрик
        // в балладе держится как ~10 «средних» символов её секции.
        val minLine = (rate * 10).coerceIn(600L, 2200L)

        // estMs: длина × локальный темп секции (окно ±5 строк), но не больше
        // 90% реального гэпа (чтоб не отставать от вокала).
        var est = length * rate
        if (nextStartMs != null) est = minOf(est, gap * 9 / 10)
        est = est.coerceAtLeast(minOf(minLine, gap)).coerceAtLeast(1L)

        // Сегменты по знакам препинания: (длина, сырая пауза после).
        val rawSegs = splitSegments(text)
        val rawTotalPause = rawSegs.sumOf { it.second }

        // Кап на суммарную паузу + пропорциональное ужатие (быстрый рэп не отстаёт).
        val maxPause = (PAUSE_CAP_FRAC * est).toLong()
        val scale = if (rawTotalPause > 0) minOf(1.0, maxPause.toDouble() / rawTotalPause) else 0.0
        val holds = rawSegs.map { (it.second * scale).toLong() }
        val totalPause = holds.sum()

        val fillMs = maxOf(est - totalPause, (est * FILL_FLOOR_FRAC).toLong())
        val totalChars = rawSegs.sumOf { it.first }.coerceAtLeast(1)

        val segments = rawSegs.mapIndexed { i, seg ->
            val segFill = fillMs * seg.first / totalChars
            Segment(charLen = seg.first, fillMs = segFill, holdMs = holds[i])
        }
        // Точная длительность всей заливки (фактический момент «строка докрашена»).
        val realEst = segments.sumOf { it.fillMs + it.holdMs }.coerceAtLeast(1L)

        return LineFill(
            startMs = startMs,
            nextStartMs = nextStartMs ?: (startMs + gap),
            hasNext = nextStartMs != null,
            totalChars = totalChars,
            estMs = realEst,
            segments = segments
        )
    }

    /** Режет текст на сегменты по знакам: знак входит в сегмент, после него — пауза. */
    private fun splitSegments(text: String): List<Pair<Int, Long>> {
        val result = ArrayList<Pair<Int, Long>>()
        var len = 0
        for (ch in text) {
            len++
            when (ch) {
                in STRONG_PUNCT -> { result.add(len to STRONG_PAUSE_MS); len = 0 }
                in WEAK_PUNCT -> { result.add(len to WEAK_PAUSE_MS); len = 0 }
            }
        }
        if (len > 0) result.add(len to 0L)
        if (result.isEmpty()) result.add(text.length.coerceAtLeast(1) to 0L)
        return result
    }

    /**
     * Обновляет состояние по текущей позиции плеера.
     * @param positionMs позиция (уже интерполированная smooth time)
     */
    fun updatePosition(positionMs: Long) {
        if (!lyrics.isSynced || lineFills.isEmpty()) return

        val isSeek = lastProcessedPositionMs >= 0 &&
                kotlin.math.abs(positionMs - lastProcessedPositionMs) > SEEK_JUMP_THRESHOLD_MS
        val safePosition = if (!isSeek && lastProcessedPositionMs >= 0) {
            kotlin.math.max(lastProcessedPositionMs, positionMs)
        } else positionMs
        lastProcessedPositionMs = safePosition

        if (isSeek) lineCursor = findLineByPosition(safePosition)
        // Монотонное движение по строкам (граница — старт следующей строки по LRC).
        while (lineCursor + 1 < lineFills.size && safePosition >= lineFills[lineCursor + 1].startMs) {
            lineCursor++
        }

        // До первой строки — ничего активного.
        val currentLine = if (safePosition < lineFills[0].startMs) -1 else lineCursor
        _currentLineIndex.value = currentLine

        if (currentLine < 0) {
            _currentLineProgress.value = 0f
            _isInterlude.value = false
            // Интро: точки перед первой строкой наливаются от старта трека.
            _interludeProgress.value =
                (safePosition.toFloat() / lineFills[0].startMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            return
        }

        val lf = lineFills[currentLine]
        val progress = if (lf.wordTimeline != null) {
            wordProgress(lf, safePosition)            // караоке по словам
        } else {
            segmentProgress(lf, (safePosition - lf.startMs).coerceAtLeast(0L))
        }
        _currentLineProgress.value = progress.coerceIn(0f, 1f)

        // Waiting: строка докрашена полностью И до следующей строки реальный разрыв.
        val fullyDrawnAt = lf.startMs + lf.estMs
        val interlude = lf.hasNext &&
                safePosition >= fullyDrawnAt &&
                (lf.nextStartMs - fullyDrawnAt) >= WAIT_GAP_MS &&
                safePosition < lf.nextStartMs
        _isInterlude.value = interlude
        _interludeProgress.value = if (interlude) {
            ((safePosition - fullyDrawnAt).toFloat() /
                (lf.nextStartMs - fullyDrawnAt).coerceAtLeast(1L)).coerceIn(0f, 1f)
        } else 0f
    }

    /** Доля закрашенных символов строки в момент [now] (мс от старта строки). */
    private fun segmentProgress(lf: LineFill, now: Long): Float {
        var acc = 0L
        var charsBefore = 0
        for (seg in lf.segments) {
            val fillEnd = acc + seg.fillMs
            val holdEnd = fillEnd + seg.holdMs
            when {
                now < fillEnd -> {
                    val f = if (seg.fillMs > 0) (now - acc).toFloat() / seg.fillMs else 1f
                    return (charsBefore + seg.charLen * f.coerceIn(0f, 1f)) / lf.totalChars
                }
                now < holdEnd -> {
                    // Микропауза на знаке: сегмент закрашен, держим — БЕЗ Waiting.
                    return (charsBefore + seg.charLen).toFloat() / lf.totalChars
                }
                else -> {
                    acc = holdEnd
                    charsBefore += seg.charLen
                }
            }
        }
        return 1f
    }

    /** Доля закрашенных символов строки по ПОСЛОВНЫМ таймкодам (абсолютная позиция). */
    private fun wordProgress(lf: LineFill, posMs: Long): Float {
        val spans = lf.wordTimeline ?: return 0f
        var filled = 0f
        for (w in spans) {
            when {
                posMs >= w.endMs -> filled = (w.charStart + w.charLen).toFloat()
                posMs >= w.startMs -> {
                    val f = (posMs - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1L)
                    return ((w.charStart + w.charLen * f.coerceIn(0f, 1f)) / lf.totalChars).coerceIn(0f, 1f)
                }
                else -> return (w.charStart.toFloat() / lf.totalChars).coerceIn(0f, 1f)
            }
        }
        return (filled / lf.totalChars).coerceIn(0f, 1f)
    }

    /** Binary search по стартам строк — только при ручной перемотке. */
    private fun findLineByPosition(positionMs: Long): Int {
        var low = 0
        var high = lineFills.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lineFills[mid].startMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result.coerceIn(0, lineFills.size - 1)
    }

    /** Совместимость API — рендер активной строки идёт по currentLineProgress. */
    fun getCurrentLineWords(): List<WordToken> = emptyList()

    /** HSV-трансформация: boost saturation в SATURATION_BOOST раз. */
    fun boostSaturation(color: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[1] = (hsv[1] * SATURATION_BOOST).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** Сброс состояния при смене трека. */
    fun reset() {
        lineCursor = 0
        lastProcessedPositionMs = -1L
        _currentLineIndex.value = -1
        _currentLineProgress.value = 0f
        _pastWords.value = emptyList()
        _currentWords.value = emptyList()
        _isInterlude.value = false
        _interludeProgress.value = 0f
    }

    data class WordToken(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val progress: Float = 0f
    )

    /** Один сегмент строки: длина в символах, время закраса и пауза-hold после. */
    private class Segment(
        val charLen: Int,
        val fillMs: Long,
        val holdMs: Long
    )

    /** Слово с позицией в символах строки и временным диапазоном (Enhanced LRC). */
    private class WordSpan(
        val charStart: Int,
        val charLen: Int,
        val startMs: Long,
        val endMs: Long
    )

    /** Предрасчитанный таймлайн заливки строки. */
    private class LineFill(
        val startMs: Long,
        val nextStartMs: Long,
        val hasNext: Boolean,
        val totalChars: Int,
        val estMs: Long,
        val segments: List<Segment>,
        /** Непусто → закрас по словам (караоке), а не по сегментно-пунктуационной модели. */
        val wordTimeline: List<WordSpan>? = null
    )
}
