package com.lethargicrunner.defloute

import kotlin.math.abs

/**
 * Détection de flou par variance du Laplacien, multi-échelle (V0.2.0+).
 * Pur Kotlin, zéro dépendance. Seuils réglables (persistés côté UI).
 */
object BlurDetector {

    data class Settings(
        val threshold: Double = 100.0,   // seuil absolu du score fin
        val ratioThreshold: Float = 0.5f // ratio grossier/fin (V0.2.1)
    )

    data class Result(
        val fineScore: Double,
        val coarseScore: Double,
        val ratio: Double,
        val isBlurry: Boolean
    )

    /** Variance du Laplacien sur une grille de pixels ARGB. */
    fun laplacianVariance(px: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = 4f * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Décision multi-échelle : score fin (~512 px) + grossier (~128 px). */
    fun evaluate(finePx: IntArray, fineW: Int, fineH: Int, coarsePx: IntArray, coarseW: Int, coarseH: Int, s: Settings): Result {
        val fine = laplacianVariance(finePx, fineW, fineH)
        val coarse = laplacianVariance(coarsePx, coarseW, coarseH)
        val ratio = if (fine > 0.0) coarse / fine else 0.0
        val blurry = fine < s.threshold || ratio > s.ratioThreshold
        return Result(fine, coarse, ratio, blurry)
    }

    /** Score simple 0..500 affiché dans la liste. */
    fun displayScore(fine: Double): Int = abs(fine).toInt().coerceIn(0, 500)
}
