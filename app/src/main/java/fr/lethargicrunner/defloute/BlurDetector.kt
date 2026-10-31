package fr.lethargicrunner.defloute

import android.graphics.Bitmap

/**
 * Detection du flou par variance du Laplacien, en pur Kotlin (zero dependance).
 *
 * Principe : le Laplacien mesure les variations locales d'intensite.
 * Une image nette a des contours marques -> forte variance.
 * Une image floue est "plate" -> faible variance.
 *
 * La methode [laplacianVariance] est pure (tableau de luminances en entree),
 * donc testable unitairement sans Android.
 */
object BlurDetector {

    /** Seuil par defaut en dessous duquel une image est consideree floue. */
    const val DEFAULT_BLUR_THRESHOLD: Double = 100.0

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
     * Score de nettete complet d'un Bitmap deja decode (l'appelant gere le sampling).
     */
    fun score(bitmap: Bitmap): Double =
        laplacianVariance(luminances(bitmap), bitmap.width, bitmap.height)

    /** Indique si un score correspond a une image floue. */
    fun isBlurry(score: Double, threshold: Double = DEFAULT_BLUR_THRESHOLD): Boolean = score < threshold
}
