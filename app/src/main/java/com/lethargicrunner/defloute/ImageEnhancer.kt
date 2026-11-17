package com.lethargicrunner.defloute

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Prétraitements classiques (pur Kotlin, testables en JVM) appliqués AVANT le renforcement :
 *  1. réduction du bruit (filtre bilatéral : lisse les aplats, garde les contours) ;
 *  2. niveaux automatiques (étire le contraste d'une photo voilée) ;
 *  3. courbe de tons (remonte les ombres, tasse les hautes lumières) ;
 *  4. clarté (contraste local à grand rayon).
 *
 * Les pixels sont de vrais ARGB Android : 0xAARRGGBB.
 */
object ImageEnhancer {

    data class ToneParams(
        val autoLevels: Boolean = true,
        /** 0..1 : remonte les zones sombres. */
        val shadows: Float = 0.5f,
        /** 0..1 : tasse les zones claires (ne recrée pas ce qui est complètement brûlé). */
        val highlights: Float = 0.6f,
        /** 0..1 : contraste local. */
        val clarity: Float = 0.3f
    )

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun luma(p: Int): Float = 0.299f * r(p) + 0.587f * g(p) + 0.114f * b(p)
    /** Luminance arrondie 0..255 (l'arrondi évite qu'un gris 38 tombe sur l'indice 37 à cause des flottants). */
    private fun lumaInt(p: Int): Int = (luma(p) + 0.5f).toInt().coerceIn(0, 255)

    // ------------------------------------------------------------ bruit

    /**
     * Filtre bilatéral 5x5 guidé par la luminance. [strength] 0..1 (0 = rien).
     * La tolérance de contraste augmente avec la force : plus on lisse, plus on fusionne les nuances.
     */
    fun denoise(px: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f || w < 5 || h < 5) return px.copyOf()

        val radius = 2
        val sigmaRange = 8f + 32f * s            // 8..40 niveaux de gris
        val rangeLut = FloatArray(256) { d -> exp(-(d * d) / (2f * sigmaRange * sigmaRange)) }
        val sigmaSpace = 1.2f + 0.8f * s
        val spatial = FloatArray((2 * radius + 1) * (2 * radius + 1))
        for (dy in -radius..radius) for (dx in -radius..radius) {
            spatial[(dy + radius) * (2 * radius + 1) + dx + radius] =
                exp(-(dx * dx + dy * dy) / (2f * sigmaSpace * sigmaSpace))
        }

        val lum = IntArray(w * h) { lumaInt(px[it]) }
        val out = IntArray(px.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val lc = lum[i]
                var sw = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val j = yy * w + xx
                        val d = lum[j] - lc
                        val wt = spatial[(dy + radius) * (2 * radius + 1) + dx + radius] *
                            rangeLut[if (d < 0) -d else d]
                        val p = px[j]
                        sw += wt; sr += wt * r(p); sg += wt * g(p); sb += wt * b(p)
                    }
                }
                out[i] = argb(
                    (px[i] ushr 24) and 0xFF,
                    (sr / sw + 0.5f).toInt().coerceIn(0, 255),
                    (sg / sw + 0.5f).toInt().coerceIn(0, 255),
                    (sb / sw + 0.5f).toInt().coerceIn(0, 255)
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ tons

    /** Étire l'histogramme de luminance entre les percentiles 0,5 % et 99,5 %. Modifie [px] en place. */
    fun autoLevels(px: IntArray) {
        if (px.isEmpty()) return
        val hist = IntArray(256)
        for (p in px) hist[lumaInt(p)]++
        val cut = (px.size * 0.005).toInt()
        var acc = 0; var lo = 0
        while (lo < 255 && acc + hist[lo] <= cut) { acc += hist[lo]; lo++ }
        acc = 0; var hi = 255
        while (hi > 0 && acc + hist[hi] <= cut) { acc += hist[hi]; hi-- }
        if (hi - lo < 24) return                   // image déjà très plate : on ne touche pas
        val scale = 255f / (hi - lo)
        val lut = IntArray(256) { v -> ((v - lo) * scale + 0.5f).toInt().coerceIn(0, 255) }
        for (i in px.indices) {
            val p = px[i]
            px[i] = argb((p ushr 24) and 0xFF, lut[r(p)], lut[g(p)], lut[b(p)])
        }
    }

    /** Courbe luminance : remonte les ombres, tasse les hautes lumières (monotone). En place. */
    fun toneCurve(px: IntArray, shadows: Float, highlights: Float) {
        val sh = shadows.coerceIn(0f, 1f)
        val hl = highlights.coerceIn(0f, 1f)
        if (sh == 0f && hl == 0f) return
        // gain par niveau de luminance (LUT 256 entrées)
        val gain = FloatArray(256) { v ->
            val y = v / 255f
            val inv = 1f - y
            val y2 = (y + 0.20f * sh * inv * inv * inv - 0.20f * hl * y * y * y).coerceIn(0f, 1f)
            if (v == 0) 1f else min(4f, max(0.25f, y2 / y))
        }
        for (i in px.indices) {
            val p = px[i]
            val gn = gain[lumaInt(p)]
            px[i] = argb(
                (p ushr 24) and 0xFF,
                (r(p) * gn + 0.5f).toInt().coerceIn(0, 255),
                (g(p) * gn + 0.5f).toInt().coerceIn(0, 255),
                (b(p) * gn + 0.5f).toInt().coerceIn(0, 255)
            )
        }
    }

    /** Contraste local : masque flou à grand rayon (≈ 2 % de la taille de l'image). En place. */
    fun clarity(px: IntArray, w: Int, h: Int, amount: Float) {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0f) return
        val radius = max(6, min(w, h) / 50)
        val blurred = DeblurProcessor.boxBlur3(px, w, h, radius)
        DeblurProcessor.unsharpInPlace(px, blurred, 0.6f * a)
    }

    /**
     * Part (0..1) de pixels blancs purs (luminance ≥ 252) et noirs purs (≤ 3) : zones sans information,
     * qu'aucun traitement ne peut récupérer. Renvoie (blanc, noir).
     */
    fun clippedFractions(px: IntArray): Pair<Float, Float> {
        if (px.isEmpty()) return Pair(0f, 0f)
        var white = 0
        var black = 0
        for (p in px) {
            val l = lumaInt(p)
            if (l >= 252) white++ else if (l <= 3) black++
        }
        return Pair(white.toFloat() / px.size, black.toFloat() / px.size)
    }

    /** Enchaîne niveaux auto -> courbe -> clarté. Renvoie un nouveau tableau. */
    fun tone(px: IntArray, w: Int, h: Int, p: ToneParams): IntArray {
        val out = px.copyOf()
        if (p.autoLevels) autoLevels(out)
        toneCurve(out, p.shadows, p.highlights)
        clarity(out, w, h, p.clarity)
        return out
    }
}
