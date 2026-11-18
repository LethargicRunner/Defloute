package com.lethargicrunner.defloute

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Transformée de Fourier rapide (radix-2) en Kotlin pur, en flottants, pour la déconvolution.
 * Les tailles sont des puissances de 2. La transformée inverse est normalisée (÷ n² en 2D).
 */
class FftPlan(val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)
    private val rowRe = FloatArray(n)
    private val rowIm = FloatArray(n)

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "taille FFT = puissance de 2" }
        for (i in 0 until n / 2) {
            val a = -2.0 * PI * i / n
            cosT[i] = cos(a).toFloat(); sinT[i] = sin(a).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** FFT 1D sur rowRe/rowIm (non normalisée). */
    private fun run1d(inverse: Boolean) {
        val re = rowRe; val im = rowIm
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]
                    val wi = if (inverse) -sinT[j * step] else sinT[j * step]
                    val a = i + j; val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += len
            }
            len *= 2
        }
    }

    /** FFT 2D en place sur des tableaux n×n (ligne par ligne). */
    fun fft2d(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (y in 0 until n) {
            val o = y * n
            System.arraycopy(re, o, rowRe, 0, n); System.arraycopy(im, o, rowIm, 0, n)
            run1d(inverse)
            System.arraycopy(rowRe, 0, re, o, n); System.arraycopy(rowIm, 0, im, o, n)
        }
        for (x in 0 until n) {
            for (y in 0 until n) { rowRe[y] = re[y * n + x]; rowIm[y] = im[y * n + x] }
            run1d(inverse)
            for (y in 0 until n) { re[y * n + x] = rowRe[y]; im[y * n + x] = rowIm[y] }
        }
        if (inverse) {
            val s = 1f / (n.toFloat() * n)
            for (i in re.indices) { re[i] *= s; im[i] *= s }
        }
    }
}
