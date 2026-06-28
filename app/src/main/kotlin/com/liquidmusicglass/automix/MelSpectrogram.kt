package com.liquidmusicglass.automix

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

object MelSpectrogram {

    const val SAMPLE_RATE = 22050
    const val N_MELS = 128
    const val HOP_LENGTH = 512
    const val N_FFT = 2048
    const val SEGMENT_DURATION_SEC = 10   // было 6, теперь 10 для v2
    const val TARGET_FRAMES = 431         // ceil(10 * 22050 / 512)

    private val hannWindow: FloatArray by lazy {
        FloatArray(N_FFT) { i ->
            val value = 0.5 - 0.5 * kotlin.math.cos((2.0 * Math.PI * i) / (N_FFT - 1))
            value.toFloat()
        }
    }

    private val melFilterBank: Array<FloatArray> by lazy {
        buildMelFilterBank(
            sampleRate = SAMPLE_RATE,
            nFft = N_FFT,
            nMels = N_MELS,
            fMin = 0f,
            fMax = SAMPLE_RATE / 2f
        )
    }

    fun generate(samples: FloatArray): Array<FloatArray> {
        val requiredSamples = SEGMENT_DURATION_SEC * SAMPLE_RATE
        val padded = if (samples.size >= requiredSamples) {
            samples.copyOf(requiredSamples)
        } else {
            FloatArray(requiredSamples).also { out ->
                samples.copyInto(out, endIndex = samples.size)
            }
        }

        val frameCount = ((requiredSamples - N_FFT) / HOP_LENGTH) + 1
        val powerFrames = Array(frameCount) { FloatArray(N_MELS) }

        var frameIndex = 0
        var start = 0
        var globalMax = 1e-10f

        // Проход 1: мел-СПЕКТР МОЩНОСТИ по кадрам + ГЛОБАЛЬНЫЙ максимум.
        while (start + N_FFT <= requiredSamples) {
            val frame = FloatArray(N_FFT)
            for (i in 0 until N_FFT) {
                frame[i] = padded[start + i] * hannWindow[i]
            }

            val spectrum = FFT.magnitudeSpectrum(frame, N_FFT)
            val powerSpectrum = FloatArray(spectrum.size) { i ->
                spectrum[i] * spectrum[i]
            }

            val mel = applyMelFilterBank(powerSpectrum, melFilterBank)
            powerFrames[frameIndex] = mel
            for (v in mel) if (v > globalMax) globalMax = v

            frameIndex++
            start += HOP_LENGTH
        }

        // Проход 2: power_to_db с ГЛОБАЛЬНЫМ ref=max и клипом top_db=80 → dB в
        // [-80, 0], ровно как librosa.power_to_db(S, ref=np.max). КРИТИЧНО: модель
        // обучалась именно на глобальном dB — пер-кадровый максимум (как было
        // раньше) убивал межкадровую динамику и насыщал сеть (compat≈0, entry=0,
        // start=1.0). Масштабирование в [0,1] делает FeatureExtractor.normalizeFrames.
        val invLn10 = 1f / ln(10f)
        val lnRef = ln(globalMax)
        val dbFrames = Array(frameCount) { f ->
            val src = powerFrames[f]
            FloatArray(N_MELS) { m ->
                val db = 10f * (ln(max(src[m], 1e-10f)) - lnRef) * invLn10
                max(db, -80f)
            }
        }

        return fitFramesToTarget(dbFrames, TARGET_FRAMES)
    }

    private fun applyMelFilterBank(
        powerSpectrum: FloatArray,
        filterBank: Array<FloatArray>
    ): FloatArray {
        val mel = FloatArray(filterBank.size)

        for (m in filterBank.indices) {
            var sum = 0f
            val filter = filterBank[m]

            for (k in filter.indices) {
                sum += powerSpectrum[k] * filter[k]
            }

            mel[m] = max(sum, 1e-10f)
        }

        return mel
    }

    private fun fitFramesToTarget(
        frames: Array<FloatArray>,
        targetFrames: Int
    ): Array<FloatArray> {
        return when {
            frames.size == targetFrames -> frames
            frames.size > targetFrames -> {
                Array(targetFrames) { index -> frames[index] }
            }
            else -> {
                Array(targetFrames) { index ->
                    if (index < frames.size) frames[index] else FloatArray(N_MELS)
                }
            }
        }
    }

    private fun hzToMel(hz: Float): Float {
        return 2595f * log10(1f + hz / 700f)
    }

    private fun melToHz(mel: Float): Float {
        return (700.0 * (10.0.pow(mel.toDouble() / 2595.0) - 1.0)).toFloat()
    }

    private fun buildMelFilterBank(
        sampleRate: Int,
        nFft: Int,
        nMels: Int,
        fMin: Float,
        fMax: Float
    ): Array<FloatArray> {
        val numFftBins = nFft / 2 + 1

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        val melPoints = FloatArray(nMels + 2) { i ->
            melMin + (melMax - melMin) * i / (nMels + 1)
        }

        val hzPoints = melPoints.map { melToHz(it) }
        val binPoints = hzPoints.map { hz ->
            floor((nFft + 1) * hz / sampleRate).toInt()
        }

        val filterBank = Array(nMels) { FloatArray(numFftBins) }

        for (m in 1..nMels) {
            val left = binPoints[m - 1]
            val center = binPoints[m]
            val right = binPoints[m + 1]

            for (k in left until center) {
                if (k in 0 until numFftBins && center != left) {
                    filterBank[m - 1][k] = (k - left).toFloat() / (center - left).toFloat()
                }
            }

            for (k in center until right) {
                if (k in 0 until numFftBins && right != center) {
                    filterBank[m - 1][k] = (right - k).toFloat() / (right - center).toFloat()
                }
            }
        }

        return filterBank
    }
}
