package com.lethargicrunner.defloute

import kotlin.math.exp
import kotlin.math.max

/**
 * Réduction de bruit par filtre non-local means (NLM), en complément du filtre bilatéral
 * déjà présent dans ImageEnhancer. Meilleur sur le grain fort des photos de nuit :
 * chaque pixel est moyenné avec les pixels de la fenêtre de recherche dont le voisinage
 * (patch) ressemble au sien.
 *
 * Coût O(search^2 * patch^2) par pixel : prévu pour les images de travail ≤ 1024 px.
 * Pur Kotlin, testable en JVM.
 */
object NlmDenoise {

    /**
     * [strength] 0..1 mélange avec l'original. [searchR] rayon de recherche (2..6),
     * [patchR] rayon du patch comparé (1 ou 2). [sigma] force de l'égalisation des poids.
     */
    fun denoise(
        px: IntArray, w: Int, h: Int,
        strength: Float = 0.8f,
        searchR: Int = 5,
        patchR: Int = 1,
        sigma: Float = 14f
    ): IntArray {
        if (w < 2 * searchR + 3 || h < 2 * searchR + 3) return px.copyOf()
        val n = w * h
        // luminance précalculée pour comparer les patches
        val lum = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            lum[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        val out = IntArray(n)
        val twoSigmaSq = 2f * max(sigma * sigma, 1f)
        val maxWeight = exp(-0f) // 1f

        for (y in 0 until h) {
            val yLo = maxOf(searchR, y - searchR)
            val yHi = minOf(h - 1 - searchR, y + searchR)
            for (x in 0 until w) {
                val xLo = maxOf(searchR, x - searchR)
                val xHi = minOf(w - 1 - searchR, x + searchR)
                val iCenter = y * w + x

                var wSum = 0f
                var rAcc = 0f; var gAcc = 0f; var bAcc = 0f
                for (ny in yLo..yHi) {
                    for (nx in xLo..xHi) {
                        // poids = ressemblance des patches (distance quadratique sur la luminance)
                        var ssd = 0f
                        for (py in -patchR..patchR) {
                            val rowC = (y + py) * w
                            val rowN = (ny + py) * w
                            for (pxx in -patchR..patchR) {
                                val d = lum[rowC + x + pxx] - lum[rowN + nx + pxx]
                                ssd += d * d
                            }
                        }
                        val weight = if (nx == x && ny == y) maxWeight else exp(-ssd / twoSigmaSq / ((2 * patchR + 1) * (2 * patchR + 1)))
                        val j = ny * w + nx
                        val p = px[j]
                        rAcc += weight * ((p shr 16) and 0xFF)
                        gAcc += weight * ((p shr 8) and 0xFF)
                        bAcc += weight * (p and 0xFF)
                        wSum += weight
                    }
                }
                if (wSum <= 0f) { out[iCenter] = px[iCenter]; continue }
                val mix = strength.coerceIn(0f, 1f)
                val pC = px[iCenter]
                val mixCh = { orig: Int, filt: Float ->
                    val v = orig + (filt / wSum - orig) * mix
                    v.toInt().coerceIn(0, 255)
                }
                out[iCenter] = (0xFF shl 24) or
                    (mixCh((pC shr 16) and 0xFF, rAcc) shl 16) or
                    (mixCh((pC shr 8) and 0xFF, gAcc) shl 8) or
                    mixCh(pC and 0xFF, bAcc)
            }
        }
        return out
    }
}
