package fr.lethargicrunner.defloute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurDetectorTest {

    @Test
    fun image_uniforme_a_score_nul() {
        val gray = IntArray(16) { 128 }
        val score = BlurDetector.laplacianVariance(gray, 4, 4)
        assertEquals(0.0, score, 1e-9)
        assertTrue(BlurDetector.isBlurryMultiScale(score, score))
    }

    @Test
    fun damier_net_a_un_score_superieur_au_flou() {
        val checker = IntArray(64) { i -> if ((i / 8 + i % 8) % 2 == 0) 255 else 0 }
        val sharp = BlurDetector.laplacianVariance(checker, 8, 8)

        val smooth = IntArray(64) { i -> 128 + ((i / 8) - 3) * 4 }
        val smoothScore = BlurDetector.laplacianVariance(smooth, 8, 8)

        assertTrue(sharp > smoothScore)
        assertTrue(sharp > BlurDetector.DEFAULT_BLUR_THRESHOLD)
    }

    @Test
    fun decision_multiechelle_image_nette() {
        // Image nette : score fin eleve, score grossier effondre (ratio 0.03)
        assertFalse(BlurDetector.isBlurryMultiScale(2000.0, 60.0))
    }

    @Test
    fun decision_multiechelle_flou_bruite() {
        // Flou bruite : score fin qui passe le seuil absolu MAIS ratio eleve
        assertTrue(BlurDetector.isBlurryMultiScale(300.0, 150.0))
    }

    @Test
    fun decision_multiechelle_seuils_reglables() {
        // Ratio 0.25 (ancienne valeur, trop stricte) : cette photo normale serait faussement floue
        val fine = 200.0
        val coarse = 80.0  // ratio 0.4
        assertFalse(BlurDetector.isBlurryMultiScale(fine, coarse, 100.0, 0.5))
        assertTrue(BlurDetector.isBlurryMultiScale(fine, coarse, 100.0, 0.25))
    }

    @Test
    fun decision_score_fin_faible() {
        assertTrue(BlurDetector.isBlurryMultiScale(42.0, 0.0))
    }

    @Test
    fun tailles_incoherentes_rejetees() {
        var threw = false
        try {
            BlurDetector.laplacianVariance(IntArray(10), 5, 5)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun image_trop_petite_renvoie_zero() {
        assertEquals(0.0, BlurDetector.laplacianVariance(IntArray(4), 2, 2), 1e-9)
    }
}
