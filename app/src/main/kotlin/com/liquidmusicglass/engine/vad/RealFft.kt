package com.liquidmusicglass.engine.vad

import kotlin.math.cos
import kotlin.math.sin

/**
 * Минимальный итеративный radix-2 FFT (Cooley–Tukey) для вещественного входа
 * фиксированного размера [n] (степень двойки). Используется VAD-пайплайном
 * лирики, чтобы считать спектр НА УСТРОЙСТВЕ с float-точностью — 1:1 с обучением
 * (librosa.stft), а не через 8-битный Android Visualizer.getFft (который теряет
 * точность и даёт 512 бинов вместо 513).
 *
 * Окно — ПЕРИОДИЧЕСКОЕ Ханна (как librosa: scipy hann, fftbins=True):
 *   w[i] = 0.5 * (1 - cos(2π·i / n))
 *
 * power(k) = re(k)² + im(k)²  для k = 0..n/2  (n/2+1 бинов), без нормировки 1/N —
 * как |numpy.fft.rfft|² .
 */
class RealFft(private val n: Int) {

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two, got $n" }
    }

    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)
    private val hann = FloatArray(n)

    private val re = FloatArray(n)
    private val im = FloatArray(n)

    init {
        for (i in 0 until n / 2) {
            val a = -2.0 * Math.PI * i / n
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            rev[i] = Integer.reverse(i) ushr (32 - bits)
        }
        for (i in 0 until n) {
            // Периодическое окно Ханна (fftbins=True), совпадает с librosa.
            hann[i] = 0.5f * (1f - cos(2.0 * Math.PI * i / n).toFloat())
        }
    }

    /**
     * Считает power-спектр оконного кадра [frame] (длина [n]).
     * Заполняет [outPower] длиной n/2+1: outPower[k] = re²+im².
     */
    fun powerSpectrum(frame: FloatArray, outPower: FloatArray) {
        require(frame.size == n) { "frame size ${frame.size} != $n" }
        require(outPower.size == n / 2 + 1) { "outPower size ${outPower.size} != ${n / 2 + 1}" }

        // Применяем окно + bit-reversal копирование в рабочие массивы.
        for (i in 0 until n) {
            re[rev[i]] = frame[i] * hann[i]
            im[rev[i]] = 0f
        }

        // Итеративные «бабочки».
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                var t = 0
                while (k < half) {
                    val wr = cosT[t]
                    val wi = sinT[t]
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + half]
                    val bIm = im[i + k + half]
                    val tr = wr * bRe - wi * bIm
                    val ti = wr * bIm + wi * bRe
                    re[i + k] = aRe + tr
                    im[i + k] = aIm + ti
                    re[i + k + half] = aRe - tr
                    im[i + k + half] = aIm - ti
                    k++
                    t += step
                }
                i += len
            }
            len = len shl 1
        }

        for (k in 0..n / 2) {
            val r = re[k]
            val m = im[k]
            outPower[k] = r * r + m * m
        }
    }
}
