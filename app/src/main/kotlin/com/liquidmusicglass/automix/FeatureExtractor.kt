package com.liquidmusicglass.automix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Извлечение фичей для AutoMix v2.
 *
 * Модель ожидает:
 *   mel_a:  [1, 431, 128]  — мел-спектрограмма конца трека A
 *   mel_b:  [1, 431, 128]  — мел-спектрограмма начала трека B
 *   aux:    [1, 32]         — вспомогательные фичи (BPM, chroma, energy и т.д.)
 */
object FeatureExtractor {

    private const val N_CHROMA = 12
    private const val AUX_PER_TRACK = 16  // BPM(1) + chroma(12) + energy(1) + centroid(1) + onset(1)

    // ── Вырезание сегмента из кэша ──
    // Байт на секунду точно не известен (VBR, разные битрейты), поэтому считаем
    // его от РЕАЛЬНОЙ длительности трека: bytesPerSec = contentLength / durationSec.
    // Ориентир «типового трека» тут не годится — на 200-секундном треке он давал
    // покрытие 0.9x, то есть куска не хватало даже на нужные 10 секунд звука.
    private const val SEGMENT_WITH_MARGIN_SEC = 20L       // 10 c нужных + запас на VBR
    private const val MIN_SEGMENT_BYTES = 512L * 1024L    // не меньше 512 КБ
    private const val FALLBACK_TRACK_SEC = 240L           // если длительность неизвестна
    // Запас на ID3-тег в начале файла: в реальном треке пользователя тег с
    // обложкой занимал 630 КБ (≈14 c эквивалента), и без этого запаса вырезанный
    // кусок почти целиком оказался бы тегами, а не звуком.
    private const val MAX_ID3_BYTES = 1024L * 1024L

    /**
     * Почему декод сегмента не дал звука.
     *
     * Раньше все эти ветки возвращали массив тишины, и молча: модель честно
     * считала мел-спектрограмму от −80 dB и выдавала рецепт, построенный на
     * пустоте. Внешне это выглядело как «модель запустилась, но работу не
     * сделала» — и на стриминге происходило постоянно, потому что декодер
     * открывал источник ЗАНОВО по временной ссылке, которая к тому моменту
     * могла истечь.
     *
     * Теперь причина называется явно и доходит до вызывающего, чтобы рецепт на
     * мусорных данных вообще не применялся.
     */
    enum class DecodeFailure {
        /** `setDataSource` не открыл источник: истёкшая ссылка, нет сети, нет прав. */
        SOURCE_UNAVAILABLE,

        /** В контейнере нет аудио-дорожки (или это не медиа-файл). */
        NO_AUDIO_TRACK,

        /** Для MIME контейнера нет декодера на этом устройстве. */
        NO_DECODER,

        /** Декодер отработал, но не отдал ни одного сэмпла. */
        EMPTY_OUTPUT
    }

    /** Итог декода сегмента: либо сэмплы, либо названная причина отказа. */
    sealed class SegmentResult {
        data class Success(val samples: FloatArray) : SegmentResult()
        data class Failure(val reason: DecodeFailure, val detail: String = "") : SegmentResult()
    }

    /**
     * Результат извлечения фичей для пары треков.
     */
    data class PairFeatures(
        val melA: Array<Array<FloatArray>>,    // [1, 431, 128]
        val melB: Array<Array<FloatArray>>,    // [1, 431, 128]
        val aux: Array<FloatArray>,            // [1, 32]
        /**
         * Диагностика входа модели: RMS декодированных сэмплов A/B (если ≈0 —
         * декод пустой/тишина), реальные BPM, увиденные ЭТИМ экстрактором, и
         * разброс сырого мела до нормировки. Позволяет понять, зависит ли вход
         * модели от трека вообще.
         */
        val debug: String
    )

    /**
     * Извлечь фичи для пары треков: конец A + начало B.
     *
     * Возвращает null, если хотя бы один сегмент не удалось декодировать: рецепт
     * на тишине хуже отсутствия рецепта — он тихо портит переход, тогда как
     * отсутствие честно откатывает на обычный свод. Причина отказа уходит в
     * DebugLog.
     *
     * @param trackAId,trackBId идентификаторы треков. Если заданы и трек лежит в
     *        кэше, сегмент читается С ДИСКА — без сети, без второго соединения к
     *        CDN и без риска, что временная ссылка истекла. Именно сетевой путь
     *        был причиной того, что на стриминге модель считала фичи от тишины.
     */
    fun extractPairFromUri(
        context: Context,
        trackAUri: Uri,
        trackBUri: Uri,
        trackADurationMs: Long,
        trackAId: String? = null,
        trackBId: String? = null
    ): PairFeatures? {
        // Конец трека A
        val resultA = decodeSegmentPreferCache(
            context, trackAId, trackAUri, fromEnd = true, trackDurationMs = trackADurationMs
        )
        if (resultA is SegmentResult.Failure) {
            logDecodeFailure("A", resultA)
            return null
        }
        val samplesA = (resultA as SegmentResult.Success).samples
        val melA = MelSpectrogram.generate(samplesA)
        val normalizedA = normalizeFrames(melA)
        val auxA = computeAuxFeatures(samplesA)

        // Начало трека B
        val resultB = decodeSegmentPreferCache(
            context, trackBId, trackBUri, fromEnd = false, trackDurationMs = 0L
        )
        if (resultB is SegmentResult.Failure) {
            logDecodeFailure("B", resultB)
            return null
        }
        val samplesB = (resultB as SegmentResult.Success).samples
        val melB = MelSpectrogram.generate(samplesB)
        val normalizedB = normalizeFrames(melB)
        val auxB = computeAuxFeatures(samplesB)

        // Объединяем aux: [auxA(16) + auxB(16)] = [32]
        val combinedAux = FloatArray(AUX_PER_TRACK * 2)
        auxA.copyInto(combinedAux, 0)
        auxB.copyInto(combinedAux, AUX_PER_TRACK)

        // Диагностика входа: зависит ли он от трека вообще.
        val rmsA = computeRms(samplesA)
        val rmsB = computeRms(samplesB)
        val (melAmin, melAmax) = rawRange(melA)
        // F6: декодер при любой ошибке отдаёт тишину МОЛЧА (5 разных веток), и
        // отличить «трек не проанализирован» от «трек тихий» по цифрам сложно.
        // Ставим явные маркеры — они сразу видны в логе.
        val silentMark = buildString {
            if (rmsA < 1e-6f) append(" !A_SILENT")
            if (rmsB < 1e-6f) append(" !B_SILENT")
        }
        val debug = "rmsA=%.4f rmsB=%.4f bpmA=%.0f bpmB=%.0f melRaw[%.2f..%.2f] nA=%d nB=%d%s".format(
            rmsA, rmsB, auxA[0] * 200f, auxB[0] * 200f, melAmin, melAmax,
            samplesA.size, samplesB.size, silentMark
        )

        return PairFeatures(
            melA = Array(1) { normalizedA },
            melB = Array(1) { normalizedB },
            aux = Array(1) { combinedAux },
            debug = debug
        )
    }

    /**
     * Упрощённый вариант: извлечь фичи только для начала трека B
     * (для обратной совместимости, когда трек A ещё не закончился
     * и мы анализируем заранее). null — сегмент не декодировался.
     */
    fun extractSingleFromUri(
        context: Context,
        uri: Uri
    ): Array<Array<FloatArray>>? {
        val result = decodePcmSegment(context, uri, fromEnd = false, trackDurationMs = 0L)
        if (result is SegmentResult.Failure) {
            logDecodeFailure("single", result)
            return null
        }
        val samples = (result as SegmentResult.Success).samples
        val melFrames = MelSpectrogram.generate(samples)
        val normalized = normalizeFrames(melFrames)
        return Array(1) { normalized }
    }

    /**
     * Причина отказа — в DebugLog: экран диагностики на телефоне доступен, а
     * logcat нет. Тег совпадает с остальными записями AutoMix, чтобы искать
     * одним фильтром.
     */
    private fun logDecodeFailure(which: String, failure: SegmentResult.Failure) {
        val detail = if (failure.detail.isEmpty()) "" else " (${failure.detail})"
        com.liquidmusicglass.debug.DebugLog.add(
            "AutoMix.decode FAIL $which: ${failure.reason}$detail"
        )
    }

    /**
     * Сегмент из КЭША, если он там есть; иначе — прежний путь по URI.
     *
     * Порядок именно такой: кэш не может истечь, не открывает второе соединение и
     * не делает range-запрос к чужому CDN. Префетч (preloadLeadSeconds, 30..90 c)
     * кладёт следующий трек на диск целиком, а текущий к концу воспроизведения
     * уже там — значит для анализа пары обычно доступны оба.
     *
     * Сетевой фолбэк оставлен осознанно: кэш может быть выключен пользователем
     * (размер 0), префетч мог не успеть, LRU мог вытеснить трек. Тогда работает
     * старый путь — с честными причинами отказа из батча 1.
     */
    private fun decodeSegmentPreferCache(
        context: Context,
        trackId: String?,
        uri: Uri,
        fromEnd: Boolean,
        trackDurationMs: Long
    ): SegmentResult {
        if (trackId != null) {
            val cached = decodeSegmentFromCache(context, trackId, fromEnd, trackDurationMs)
            if (cached != null) {
                return cached
            }
        }
        return decodePcmSegment(context, uri, fromEnd, trackDurationMs)
    }

    /**
     * Пытается взять сегмент из дискового кэша media3.
     *
     * @return null, если кэш недоступен или нужных байт в нём нет — это не ошибка,
     *         а сигнал «пробуй сеть». Ошибки самого декода возвращаются как
     *         SegmentResult.Failure.
     */
    private fun decodeSegmentFromCache(
        context: Context,
        trackId: String,
        fromEnd: Boolean,
        trackDurationMs: Long
    ): SegmentResult? {
        val cacheManager = com.liquidmusicglass.engine.MediaCacheManager
        val contentLength = cacheManager.cachedContentLength(trackId)
        if (contentLength <= 0L) {
            return null // длина неизвестна — трека в кэше нет
        }

        // Сколько байт вырезать. Считаем от фактической длительности трека: на VBR
        // и разных битрейтах это единственная честная оценка. Длительность знает
        // только вызывающий (для хвоста A она передана; для головы B её нет —
        // тогда берём ориентир).
        val durationSec = if (trackDurationMs > 0L) trackDurationMs / 1000L else FALLBACK_TRACK_SEC
        val bytesPerSec = (contentLength / durationSec.coerceAtLeast(1L)).coerceAtLeast(1L)

        val position: Long
        val length: Long
        if (fromEnd) {
            // ХВОСТ. Вырезаем ровно нужные секунды с конца и НЕ больше: декодер
            // читает кусок с начала, поэтому кусок на 20 c дал бы секунды −20..−10,
            // то есть не конец трека, а середину — ошибка ровно на длину запаса.
            val tailBytes = (bytesPerSec * MelSpectrogram.SEGMENT_DURATION_SEC)
                .coerceAtLeast(MIN_SEGMENT_BYTES)
                .coerceAtMost(contentLength)
            position = (contentLength - tailBytes).coerceAtLeast(0L)
            length = contentLength - position
        } else {
            // ГОЛОВА. Здесь запас обязателен, причём большой: в начале mp3 лежит
            // ID3-тег с обложкой, и он бывает огромным — в реальном файле
            // пользователя 630 КБ, то есть 14 с эквивалента. Без запаса кусок
            // почти целиком ушёл бы в теги, и до звука дело не дошло.
            position = 0L
            length = (bytesPerSec * SEGMENT_WITH_MARGIN_SEC + MAX_ID3_BYTES)
                .coerceAtLeast(MIN_SEGMENT_BYTES)
                .coerceAtMost(contentLength)
        }

        val available = cacheManager.cachedBytes(trackId, position, length)
        if (available < length) {
            com.liquidmusicglass.debug.DebugLog.add(
                "AutoMix.cache MISS ${if (fromEnd) "A" else "B"} id=$trackId " +
                    "есть=$available нужно=$length → пробуем сеть"
            )
            return null
        }

        val tmp = File(context.cacheDir, "automix_seg_${if (fromEnd) "a" else "b"}.bin")
        return try {
            if (!cacheManager.extractCachedRangeToFile(trackId, position, length, tmp)) {
                return null
            }
            com.liquidmusicglass.debug.DebugLog.add(
                "AutoMix.cache HIT ${if (fromEnd) "A" else "B"} id=$trackId байт=$length"
            )
            // Хвост читаем с самого начала вырезанного куска: он и есть конец трека.
            decodePcmSegment(
                context,
                Uri.fromFile(tmp),
                fromEnd = false,
                trackDurationMs = 0L
            )
        } finally {
            runCatching { tmp.delete() }
        }
    }

    // ──────────────────────────────────────
    // Auxiliary features
    // ──────────────────────────────────────

    /**
     * Вычислить вспомогательные фичи для сегмента:
     * [BPM(1), chroma(12), rms_energy(1), spectral_centroid(1), onset_strength(1)]
     */
    private fun computeAuxFeatures(samples: FloatArray): FloatArray {
        val features = FloatArray(AUX_PER_TRACK)
        var idx = 0

        // 1. BPM (нормализованный: /200)
        val bpm = estimateBpm(samples)
        features[idx++] = bpm / 200f

        // 2. Chroma (12 нот, средние значения)
        val chroma = computeChroma(samples)
        for (c in chroma) {
            features[idx++] = c
        }

        // 3. RMS энергия
        features[idx++] = computeRms(samples)

        // 4. Spectral centroid (нормализованный)
        features[idx++] = computeSpectralCentroid(samples)

        // 5. Onset strength (нормализованный)
        features[idx++] = computeOnsetStrength(samples)

        return features
    }

    /**
     * Оценка BPM через автокорреляцию.
     */
    private fun estimateBpm(samples: FloatArray): Float {
        val sr = MelSpectrogram.SAMPLE_RATE
        val hopLength = MelSpectrogram.HOP_LENGTH

        // Вычисляем onset envelope (спектральный поток)
        val envelope = computeOnsetEnvelope(samples)
        if (envelope.size < 4) return 120f

        // Автокорреляция
        val minLag = (60.0 / 200.0 * sr / hopLength).toInt()  // 200 BPM max
        val maxLag = (60.0 / 60.0 * sr / hopLength).toInt()    // 60 BPM min

        val safeLag = maxLag.coerceAtMost(envelope.size - 1)

        var bestLag = minLag
        var bestCorr = -1f

        for (lag in minLag..safeLag) {
            var corr = 0f
            var count = 0
            for (i in 0 until envelope.size - lag) {
                corr += envelope[i] * envelope[i + lag]
                count++
            }
            if (count > 0) {
                corr /= count
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestLag = lag
                }
            }
        }

        val bpm = 60f * sr / hopLength / bestLag
        return bpm.coerceIn(60f, 200f)
    }

    /**
     * Chroma features: 12-мерный вектор (C, C#, D, ... B).
     */
    private fun computeChroma(samples: FloatArray): FloatArray {
        val nFft = MelSpectrogram.N_FFT
        val hopLength = MelSpectrogram.HOP_LENGTH
        val sr = MelSpectrogram.SAMPLE_RATE

        val chroma = FloatArray(N_CHROMA)
        var frameCount = 0

        var start = 0
        while (start + nFft <= samples.size) {
            val frame = FloatArray(nFft)
            for (i in 0 until nFft) {
                frame[i] = samples[start + i]
            }

            val spectrum = FFT.magnitudeSpectrum(frame, nFft)

            // Маппинг FFT bin → chroma bin
            for (k in 1 until spectrum.size) {
                val freq = k.toFloat() * sr / nFft
                if (freq < 20f || freq > 5000f) continue

                val midiNote = 12f * (ln(freq / 440f) / ln(2f)) + 69f
                val chromaBin = ((midiNote % 12 + 12) % 12).toInt()
                chroma[chromaBin] += spectrum[k] * spectrum[k]
            }

            frameCount++
            start += hopLength
        }

        // Нормализация
        if (frameCount > 0) {
            val maxVal = chroma.max().coerceAtLeast(1e-10f)
            for (i in chroma.indices) {
                chroma[i] = chroma[i] / (frameCount * maxVal)
            }
        }

        return chroma
    }

    /**
     * RMS энергия.
     */
    private fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) {
            sumSq += s * s
        }
        return sqrt(sumSq / samples.size).toFloat()
    }

    /**
     * Spectral centroid (нормализованный 0..1).
     */
    private fun computeSpectralCentroid(samples: FloatArray): Float {
        val nFft = MelSpectrogram.N_FFT
        val sr = MelSpectrogram.SAMPLE_RATE

        // Берём один фрейм из середины
        val midStart = ((samples.size - nFft) / 2).coerceAtLeast(0)
        val frame = FloatArray(nFft)
        for (i in 0 until nFft) {
            val idx = midStart + i
            if (idx < samples.size) frame[i] = samples[idx]
        }

        val spectrum = FFT.magnitudeSpectrum(frame, nFft)

        var weightedSum = 0.0
        var sum = 0.0
        for (k in spectrum.indices) {
            val freq = k.toDouble() * sr / nFft
            weightedSum += freq * spectrum[k]
            sum += spectrum[k]
        }

        val centroid = if (sum > 0) weightedSum / sum else 0.0
        return (centroid / (sr / 2.0)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Onset strength (средняя спектральная разница между фреймами).
     */
    private fun computeOnsetStrength(samples: FloatArray): Float {
        val envelope = computeOnsetEnvelope(samples)
        if (envelope.isEmpty()) return 0f
        return (envelope.average() / 10.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Onset envelope через спектральный поток.
     */
    private fun computeOnsetEnvelope(samples: FloatArray): FloatArray {
        val nFft = MelSpectrogram.N_FFT
        val hopLength = MelSpectrogram.HOP_LENGTH
        val bins = nFft / 2 + 1

        val frames = mutableListOf<FloatArray>()

        var start = 0
        while (start + nFft <= samples.size) {
            val frame = FloatArray(nFft)
            for (i in 0 until nFft) {
                frame[i] = samples[start + i]
            }
            val spectrum = FFT.magnitudeSpectrum(frame, nFft)
            frames.add(spectrum)
            start += hopLength
        }

        if (frames.size < 2) return floatArrayOf()

        val envelope = FloatArray(frames.size - 1)
        for (i in 1 until frames.size) {
            var flux = 0f
            for (k in 0 until bins) {
                val diff = frames[i][k] - frames[i - 1][k]
                if (diff > 0) flux += diff
            }
            envelope[i - 1] = flux
        }

        return envelope
    }

    // ──────────────────────────────────────
    // Audio decoding
    // ──────────────────────────────────────

    /** Мин/макс сырого мела (до нормировки) — для диагностики входа. */
    private fun rawRange(frames: Array<FloatArray>): Pair<Float, Float> {
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        for (row in frames) for (v in row) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        if (mn == Float.POSITIVE_INFINITY) return 0f to 0f
        return mn to mx
    }

    /**
     * Нормировка мела ровно как при обучении модели: глобальный dB в [-80, 0]
     * (его уже выдаёт MelSpectrogram.generate) → (dB + 80) / 80 → [0, 1].
     *
     * Раньше тут был z-score ((x-mean)/std). Это и убивало модель: вход уходил
     * из тренировочного распределения, сеть насыщалась и выдавала один и тот же
     * вырожденный ответ (compat≈0, entry=0, start=1.0) на ЛЮБОЙ трек. Проверено
     * прогоном самой модели: на [0,1]-мел она оживает (compat варьируется,
     * entry_offset и type реагируют на вход).
     */
    private fun normalizeFrames(frames: Array<FloatArray>): Array<FloatArray> {
        return Array(frames.size) { t ->
            FloatArray(frames[t].size) { m ->
                ((frames[t][m] + 80f) / 80f).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Декодируем РЕАЛЬНЫЙ PCM моно из URI — через MediaCodec, а не сырые байты.
     *
     * Раньше тут стоял MediaExtractor.readSampleData(), который для MP3/AAC
     * отдаёт СЖАТЫЕ (encoded) кадры, а не PCM — и эти байты интерпретировались
     * как PCM16. То есть mel-спектрограмма и aux-фичи (а значит и весь вход
     * модели) считались на мусоре для всех сжатых форматов. Теперь честный
     * декодер: extractor → MediaCodec → PCM float, стерео сводится в моно,
     * ресэмплинг до MelSpectrogram.SAMPLE_RATE. Модель получает настоящий звук.
     *
     * @param fromEnd если true, берём последние SEGMENT_DURATION_SEC секунд (конец A);
     *                иначе — первые секунды (начало B).
     * @param trackDurationMs длительность трека (нужна для fromEnd).
     */
    private fun decodePcmSegment(
        context: Context,
        uri: Uri,
        fromEnd: Boolean,
        trackDurationMs: Long
    ): SegmentResult {
        val targetRate = MelSpectrogram.SAMPLE_RATE
        val targetSamples = MelSpectrogram.SEGMENT_DURATION_SEC * targetRate

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            // Самая частая ветка на стриминге: временная ссылка истекла, сети нет,
            // либо это HLS-плейлист вместо медиа. Раньше здесь молча возвращалась
            // тишина, и модель считала фичи от неё.
            return SegmentResult.Failure(
                DecodeFailure.SOURCE_UNAVAILABLE,
                "${e.javaClass.simpleName}: ${e.message ?: "нет описания"}"
            )
        }

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = fmt
                break
            }
        }

        val format = inputFormat
        val mime = format?.getString(MediaFormat.KEY_MIME)
        if (trackIndex == -1 || format == null || mime == null) {
            extractor.release()
            return SegmentResult.Failure(
                DecodeFailure.NO_AUDIO_TRACK,
                "дорожек=${extractor.trackCount}"
            )
        }

        extractor.selectTrack(trackIndex)

        val sourceRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            targetRate
        }

        // fromEnd → перематываемся к последним SEGMENT_DURATION_SEC секундам трека.
        if (fromEnd && trackDurationMs > 0L) {
            val seekToUs = (trackDurationMs - MelSpectrogram.SEGMENT_DURATION_SEC * 1000L)
                .coerceAtLeast(0L) * 1000L
            try {
                extractor.seekTo(seekToUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            } catch (_: Exception) {
                // не критично — декодируем с того, что есть
            }
        }

        // WAV/PCM (audio/raw) уже несжатый — readSampleData отдаёт настоящий PCM.
        // Декодер для него есть не на всех устройствах, поэтому читаем напрямую.
        if (mime.startsWith("audio/raw")) {
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
            val raw = decodeRawPcm(extractor, channels, sourceRate)
            extractor.release()
            if (raw.isEmpty()) {
                return SegmentResult.Failure(DecodeFailure.EMPTY_OUTPUT, "audio/raw, 0 сэмплов")
            }
            val resampledRaw = if (sourceRate == targetRate) raw
                else resampleLinear(raw, sourceRate, targetRate)
            return SegmentResult.Success(fitToExactSamples(resampledRaw, targetSamples))
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            extractor.release()
            return SegmentResult.Failure(DecodeFailure.NO_DECODER, "mime=$mime")
        }

        // Берём небольшой запас по длине (декодер может отдать чуть больше).
        val maxMonoSamples = MelSpectrogram.SEGMENT_DURATION_SEC * sourceRate + sourceRate
        val mono = FloatArray(maxMonoSamples)
        var monoCount = 0

        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            2
        }
        var pcmIsFloat = false

        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleIterations = 0

            while (!outputDone && monoCount < maxMonoSamples) {
                if (idleIterations > MAX_IDLE_ITERATIONS) break

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inIndex, 0, sampleSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        idleIterations = 0
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                monoCount = appendPcm(outBuf, mono, monoCount, channels, pcmIsFloat)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outFormat.containsKey(KEY_PCM_ENCODING)) {
                            pcmIsFloat = outFormat.getInteger(KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> idleIterations++

                    else -> { /* INFO_OUTPUT_BUFFERS_CHANGED и прочее — игнорируем */ }
                }
            }
        } catch (_: Exception) {
            // вернём то, что успели декодировать
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            extractor.release()
        }

        if (monoCount == 0) {
            return SegmentResult.Failure(
                DecodeFailure.EMPTY_OUTPUT,
                "декодер не отдал сэмплов, mime=$mime"
            )
        }

        val decoded = mono.copyOf(monoCount)
        val resampled = if (sourceRate == targetRate) {
            decoded
        } else {
            resampleLinear(decoded, sourceRate, targetRate)
        }

        return SegmentResult.Success(fitToExactSamples(resampled, targetSamples))
    }

    /**
     * PCM из буфера декодера → моно float [-1f, 1f], дописывает в [dst] с [dstIndex].
     * 16-bit signed PCM (основной случай) и float PCM; стерео сводится в моно.
     */
    private fun appendPcm(
        src: ByteBuffer,
        dst: FloatArray,
        dstIndex: Int,
        channels: Int,
        isFloat: Boolean
    ): Int {
        var idx = dstIndex
        val ch = channels.coerceAtLeast(1)
        src.order(ByteOrder.LITTLE_ENDIAN)

        if (isFloat) {
            val fb = src.asFloatBuffer()
            val frames = fb.remaining() / ch
            var f = 0
            while (f < frames && idx < dst.size) {
                var sum = 0f
                for (c in 0 until ch) sum += fb.get()
                dst[idx++] = (sum / ch).coerceIn(-1f, 1f)
                f++
            }
        } else {
            val sb = src.asShortBuffer()
            val frames = sb.remaining() / ch
            var f = 0
            while (f < frames && idx < dst.size) {
                var sum = 0f
                for (c in 0 until ch) sum += sb.get() / 32768f
                dst[idx++] = sum / ch
                f++
            }
        }
        return idx
    }

    /**
     * Прямое чтение несжатого PCM (audio/raw / WAV) из экстрактора в моно float.
     * Читает до SEGMENT_DURATION_SEC секунд от текущей позиции (учитывает seek).
     */
    private fun decodeRawPcm(
        extractor: MediaExtractor,
        channels: Int,
        sourceRate: Int
    ): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val maxMonoSamples = MelSpectrogram.SEGMENT_DURATION_SEC * sourceRate + sourceRate
        val mono = FloatArray(maxMonoSamples)
        var monoCount = 0
        val buffer = ByteBuffer.allocate(64 * 1024)

        while (monoCount < maxMonoSamples) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            monoCount = appendPcm(buffer, mono, monoCount, ch, isFloat = false)
            if (!extractor.advance()) break
        }

        return if (monoCount == 0) FloatArray(0) else mono.copyOf(monoCount)
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_ITERATIONS = 200
    private const val KEY_PCM_ENCODING = "pcm-encoding"

    private fun resampleLinear(
        input: FloatArray,
        inputRate: Int,
        outputRate: Int
    ): FloatArray {
        if (inputRate == outputRate) return input

        val ratio = outputRate.toFloat() / inputRate.toFloat()
        val outLength = (input.size * ratio).toInt().coerceAtLeast(1)
        val output = FloatArray(outLength)

        for (i in output.indices) {
            val position = i / ratio
            val left = position.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceIn(0, input.lastIndex)
            val frac = position - left

            output[i] = input[left] * (1f - frac) + input[right] * frac
        }

        return output
    }

    private fun fitToExactSamples(
        input: FloatArray,
        targetSize: Int
    ): FloatArray {
        return when {
            input.size == targetSize -> input
            input.size > targetSize -> input.copyOf(targetSize)
            else -> FloatArray(targetSize).also { out ->
                input.copyInto(out, endIndex = input.size)
            }
        }
    }
}
