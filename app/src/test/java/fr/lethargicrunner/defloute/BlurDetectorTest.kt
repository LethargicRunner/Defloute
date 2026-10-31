package fr.lethargicrunner.defloute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurDetectorTest {

    @Test
    fun image_uniforme_a_score_nul() {
        val gray = IntArray(16) { 128 } // 4x4 uniforme
        val score = BlurDetector.laplacianVariance(gray, 4, 4)
        assertEquals(0.0, score, 1e-9)
        assertTrue(BlurDetector.isBlurry(score))
    }

    @Test
    fun damier_net_a_un_score_superieur_au_flou() {
        // Damier haute frequence = tres net
        val checker = IntArray(64) { i -> if ((i / 8 + i % 8) % 2 == 0) 255 else 0 }
        val sharp = BlurDetector.laplacianVariance(checker, 8, 8)

        // Degradation douce autour de la moyenne = tres flou
        val smooth = IntArray(64) { i -> 128 + ((i / 8) - 3) * 4 }
        val smoothScore = BlurDetector.laplacianVariance(smooth, 8, 8)

        assertTrue(sharp > smoothScore)
        assertTrue(sharp > BlurDetector.DEFAULT_BLUR_THRESHOLD)
        assertTrue(BlurDetector.isBlurry(smoothScore))
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
