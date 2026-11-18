package com.lethargicrunner.defloute

import kotlin.math.max
import kotlin.math.min

/**
 * Déconvolution de Wiener par tuiles (FFT), sur la luminance.
 *
 * F = (1 + nsr) · conj(H) / (|H|² + nsr) · G
 * - nsr : rapport bruit/signal, la régularisation. Plus l'image est bruitée, plus il est grand.
 * - (1 + nsr) : gain unitaire à fréquence nulle. Sans lui, la régularisation assombrit l'image
 *   (défaut trouvé grâce au banc d'essai).
 * Les tuiles se recouvrent d'une marge ≥ rayon du noyau (bords réfléchis), puis on ne garde que le centre.
 */
object Deconvolution {

    private const val FFT_SIZE = 1024

    private fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        var k = i
        val period = 2 * (n - 1)
        k %= period
        if (k < 0) k += period
        return if (k < n) k else period - k
    }

    /**
     * Déconvolue [plane] (w×h, valeurs 0..255) avec [kernel]. Renvoie un nouveau plan (non borné :
     * le recadrage 0..255 est fait par l'appelant).
     */
    fun wiener(plane: FloatArray, w: Int, h: Int, kernel: Kernel, nsr: Float, size: Int = FFT_SIZE): FloatArray {
        val margin = kernel.size / 2 + 6
        // taille FFT : puissance de 2 suffisante pour l'image + marges, plafonnée
        var s = 64
        while (s < min(max(w, h) + 2 * margin, size)) s *= 2
        val core = s - 2 * margin
        require(core > 0) { "noyau trop grand pour la tuile" }

        val plan = FftPlan(s)
        // transformée du noyau (centrée sur l'origine par enroulement)
        val hRe = FloatArray(s * s); val hIm = FloatArray(s * s)
        val kc = kernel.size / 2
        for (ky in 0 until kernel.size) for (kx in 0 until kernel.size) {
            val x = ((kx - kc) % s + s) % s; val y = ((ky - kc) % s + s) % s
            hRe[y * s + x] += kernel.data[ky * kernel.size + kx]
        }
        plan.fft2d(hRe, hIm, false)
        // filtre de Wiener H* (1+nsr) / (|H|² + nsr), précalculé une fois
        val fRe = FloatArray(s * s); val fIm = FloatArray(s * s)
        for (i in 0 until s * s) {
            val a = hRe[i]; val b = hIm[i]
            val d = a * a + b * b + nsr
            fRe[i] = (1f + nsr) * a / d
            fIm[i] = -(1f + nsr) * b / d
        }

        val out = FloatArray(w * h)
        val gRe = FloatArray(s * s); val gIm = FloatArray(s * s)
        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                // tuile (avec marge, bords réfléchis)
                for (y in 0 until s) {
                    val sy = reflect(ty - margin + y, h)
                    for (x in 0 until s) {
                        gRe[y * s + x] = plane[sy * w + reflect(tx - margin + x, w)]
                        gIm[y * s + x] = 0f
                    }
                }
                plan.fft2d(gRe, gIm, false)
                for (i in 0 until s * s) {
                    val a = gRe[i]; val b = gIm[i]
                    gRe[i] = a * fRe[i] - b * fIm[i]
                    gIm[i] = a * fIm[i] + b * fRe[i]
                }
                plan.fft2d(gRe, gIm, true)
                val cw = min(core, w - tx); val ch = min(core, h - ty)
                for (y in 0 until ch) for (x in 0 until cw) {
                    out[(ty + y) * w + tx + x] = gRe[(y + margin) * s + x + margin]
                }
                tx += core
            }
            ty += core
        }
        return out
    }
}
