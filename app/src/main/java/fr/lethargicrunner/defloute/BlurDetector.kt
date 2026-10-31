package fr.lethargicrunner.defloute

import android.graphics.Bitmap

/**
 * Detection du flou par variance du Laplacien, multi-echelle (pur Kotlin, zero dependance).
 *
 * Principe : le Laplacien mesure les variations locales d'intensite.
 * Une image nette a des contours marques -> forte variance.
 * Une image floue est "plate" -> faible variance.
 *
 * Piege connu : une image floue mais BRUITEE garde une variance elevee et passe
 * le seuil absolu. D'ou la decision multi-echelle : une image nette perd presque
 * toute sa variance quand on la reduit (les details fins disparaissent), alors
 * qu'une image floue/bruitee garde un ratio eleve entre score grossier et score fin.
 *
 * Les fonctions de decision sont pures, donc testables sans Android.
 */
object BlurDetector {

    /** Seuil absolu (score fin) en dessous duquel une image est floue. */
    const val DEFAULT_BLUR_THRESHOLD: Double = 100.0

    /**
     * Ratio (score grossier / score fin) au-dessus duquel une image est floue.
     * Image nette reduite : ratio faible (souvent < 0.1). Image floue/bruitee : ratio eleve.
     */
    const val DEFAULT_RATIO_THRESHOLD: Double = 0.25

    /**
     * Calcule la variance du Laplacien sur un plan de luminance.
     *
     * @param gray   luminances 0..255, taille width * height, ligne par ligne.
     * @param width  largeur en pixels.
     * @param height hauteur en pixels.
     * @return variance du Laplacien (score de nettete ; plus haut = plus net).
     */
    fun laplacianVariance(gray: IntArray, width: Int, height: Int): Double {
        require(gray.size == width * height) { "gray doit contenir width * height elements" }
        if (width < 3 || height < 3) return 0.0

        val responses = DoubleArray((width - 2) * (height - 2))
        var k = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val center = gray[row + x]
                val lap = (gray[row + x - 1] + gray[row + x + 1] +
                        gray[row - width + x] + gray[row + width + x] - 4 * center).toDouble()
                responses[k++] = lap
            }
        }

        var sum = 0.0
        for (r in responses) sum += r
        val mean = sum / responses.size
        var variance = 0.0
        for (r in responses) {
            val d = r - mean
            variance += d * d
        }
        return variance / responses.size
    }

    /**
     * Extrait les luminances (Rec. 601) d'un Bitmap ARGB.
     */
    fun luminances(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return gray
    }

    /**
     * Score de nettete d'un Bitmap deja decode.
     */
    fun score(bitmap: Bitmap): Double =
        laplacianVariance(luminances(bitmap), bitmap.width, bitmap.height)

    /**
     * Decision multi-echelle.
     *
     * @param fine   score a l'echelle fine (max ~512 px).
     * @param coarse score a l'echelle grossiere (max ~128 px).
     */
    fun isBlurryMultiScale(
        fine: Double,
        coarse: Double,
        fineThreshold: Double = DEFAULT_BLUR_THRESHOLD,
        ratioThreshold: Double = DEFAULT_RATIO_THRESHOLD
    ): Boolean {
        if (fine < fineThreshold) return true
        if (coarse <= 0.0 || fine <= 0.0) return false
        // Une image nette s'effondre a l'echelle grossiere ; une floue/bruitee non.
        return coarse / fine > ratioThreshold
    }

    /** Indique si un score unique (ancienne methode) correspond a une image floue. */
    fun isBlurry(score: Double, threshold: Double = DEFAULT_BLUR_THRESHOLD): Boolean = score < threshold
}
