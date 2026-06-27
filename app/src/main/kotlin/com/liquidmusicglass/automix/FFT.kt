package com.liquidmusicglass.automix

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.sqrt

object FFT {

    fun magnitudeSpectrum(frame: FloatArray, fftSize: Int): FloatArray {
        val complex = FloatArray(fftSize * 2)
        val size = minOf(frame.size, fftSize)

        for (i in 0 until size) {
            complex[i] = frame[i]
        }

        val fft = FloatFFT_1D(fftSize.toLong())
        fft.realForwardFull(complex)

        val bins = fftSize / 2 + 1
        val magnitudes = FloatArray(bins)

        for (k in 0 until bins) {
            val real = complex[2 * k]
            val imag = complex[2 * k + 1]
            magnitudes[k] = sqrt(real * real + imag * imag)
        }

        return magnitudes
    }
}