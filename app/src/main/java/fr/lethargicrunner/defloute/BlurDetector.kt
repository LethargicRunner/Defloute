package fr.lethargicrunner.defloute

import android.graphics.Bitmap

/**
 * Detection du flou par variance du Laplacien, multi-echelle (pur Kotlin, zero dependance).
 *
 * Deux criteres combinables (seuils reglables dans l'app) :
 * 1. Absolu : score fin (echelle ~512 px) sous [DEFAULT_BLUR_THRESHOLD] -> flou.
 * 2. Ratio : score grossier (~128 px) / score fin > [DEFAULT_RATIO_THRESHOLD] -> flou.
 *    Utile pour les images floues mais BRUITEES qui gardent une variance elevee.
 *    NB : sur de vraies photos, le ratio ne s'effondre pas aussi bas que la theorie :
 *    0.25 marquait ~100% des images floues (retour terrain V0.2.0). 0.5 est plus realiste.
 *
 * Les fonctions de decision sont pures, donc testables sans Android.
 */
object BlurDetector {

    /** Seuil absolu (score fin) en dessous duquel une image est floue. */
    const val DEFAULT_BLUR_THRESHOLD: Double = 100.0

    /** Ratio (score grossier / score fin) au-dessus duquel une image est floue. */
    const val DEFAULT_RATIO_THRESHOLD: Double = 0.5

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

    fun score(bitmap: Bitmap): Double =
        laplacianVariance(luminances(bitmap), bitmap.width, bitmap.height)

    /**
     * Decision multi-echelle, seuils reglables.
     */
    fun isBlurryMultiScale(
        fine: Double,
        coarse: Double,
        fineThreshold: Double = DEFAULT_BLUR_THRESHOLD,
        ratioThreshold: Double = DEFAULT_RATIO_THRESHOLD
    ): Boolean {
        if (fine < fineThreshold) return true
        if (coarse <= 0.0 || fine <= 0.0) return false
        return coarse / fine > ratioThreshold
    }
}
