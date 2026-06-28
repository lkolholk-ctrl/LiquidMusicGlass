package com.liquidmusicglass.engine.automix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stage 7b/7c — единый извлекатель отрезка аудио в WAV-файл для JUCE.
 *
 * JUCE-движок умеет читать только локальные файлы (свой WAV-ридер). Источники
 * же разные: локальные content:// (MediaStore) и стриминговые https:// (ICM).
 * MediaExtractor умеет ОБА (для https сам делает Range/докачку), поэтому здесь
 * один путь: декодируем нужное окно [startMs, endMs) во временный WAV 16-bit,
 * а JUCE просто открывает этот файл. Так хвост A и начало B готовятся одинаково
 * и для локального, и для стримингового трека.
 *
 * Всё в фоне (IO). При любой ошибке возвращает false — вызывающий код делает
 * graceful fallback (обычный переход Media3), не падает.
 */
object SegmentExtractor {

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE = 400
    private const val KEY_PCM_ENCODING = "pcm-encoding"

    /**
     * Декодирует [startMs, endMs) трека [uri] в WAV [out]. endMs <= 0 — до конца.
     * @return true если в файл записан валидный WAV с непустым PCM.
     */
    suspend fun extractToWav(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        out: File
    ): Boolean = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (_: Exception) {
            runCatching { extractor.release() }
            return@withContext false
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
        if (trackIndex < 0 || format == null || mime == null) {
            runCatching { extractor.release() }
            return@withContext false
        }
        extractor.selectTrack(trackIndex)
        if (startMs > 0L) {
            runCatching { extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
        }
        val endUs = if (endMs > 0L) endMs * 1000L else Long.MAX_VALUE

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (_: Exception) {
            runCatching { extractor.release() }
            return@withContext false
        }

        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var pcmIsFloat = false

        // WAV: пишем 44-байтную заглушку заголовка, потом PCM, в конце патчим размеры.
        val raf = try {
            RandomAccessFile(out, "rw").apply { setLength(0); write(ByteArray(44)) }
        } catch (_: Exception) {
            runCatching { codec.release() }
            runCatching { extractor.release() }
            return@withContext false
        }

        var dataBytes = 0L
        var ok = false
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            val scratch = ByteArray(64 * 1024)

            while (!outputDone) {
                if (idle > MAX_IDLE) break

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime > endUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        idle = 0
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                dataBytes += writePcm16(outBuf, pcmIsFloat, scratch, raf)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        // Хватит, как только перешли конец окна по времени представления.
                        if (info.presentationTimeUs >= endUs) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(KEY_PCM_ENCODING))
                            pcmIsFloat = of.getInteger(KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> idle++
                    else -> { /* buffers changed — ignore */ }
                }
            }
            ok = dataBytes > 0
        } catch (_: Exception) {
            ok = false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        if (ok) {
            runCatching { patchWavHeader(raf, channels.coerceAtLeast(1), sampleRate, dataBytes) }
        }
        runCatching { raf.close() }
        if (!ok) runCatching { out.delete() }
        ok
    }

    /** Пишет блок декодера как 16-bit LE PCM в [raf]. Возвращает число записанных байт. */
    private fun writePcm16(src: ByteBuffer, isFloat: Boolean, scratch: ByteArray, raf: RandomAccessFile): Long {
        src.order(ByteOrder.LITTLE_ENDIAN)
        var written = 0L
        if (isFloat) {
            val fb = src.asFloatBuffer()
            var i = 0
            while (fb.hasRemaining()) {
                val v = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt()
                scratch[i++] = (v and 0xFF).toByte()
                scratch[i++] = ((v shr 8) and 0xFF).toByte()
                if (i >= scratch.size) { raf.write(scratch, 0, i); written += i; i = 0 }
            }
            if (i > 0) { raf.write(scratch, 0, i); written += i }
        } else {
            val n = src.remaining()
            // Уже 16-bit PCM — копируем как есть.
            var remaining = n
            while (remaining > 0) {
                val chunk = minOf(remaining, scratch.size)
                src.get(scratch, 0, chunk)
                raf.write(scratch, 0, chunk)
                written += chunk
                remaining -= chunk
            }
        }
        return written
    }

    private fun patchWavHeader(raf: RandomAccessFile, channels: Int, sampleRate: Int, dataBytes: Long) {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val riffSize = 36 + dataBytes
        raf.seek(0)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt(riffSize.toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)                 // PCM fmt chunk size
        h.putShort(1)                // PCM
        h.putShort(channels.toShort())
        h.putInt(sampleRate)
        h.putInt(byteRate)
        h.putShort(blockAlign.toShort())
        h.putShort(16)               // bits per sample
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataBytes.toInt())
        raf.write(h.array())
    }
}
