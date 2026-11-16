package com.lethargicrunner.defloute

import org.junit.Assert.assertTrue
import org.junit.Test

class BlurDetectorTest {

    private fun grayPixel(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun `image uniforme a un score nul`() {
        val px = IntArray(16 * 16) { grayPixel(128) }
        assertTrue(BlurDetector.laplacianVariance(px, 16, 16) < 1e-6)
    }

    @Test
    fun `image a bords nets a un score eleve`() {
        val w = 16; val h = 16
        val px = IntArray(w * h) { i -> grayPixel(if ((i % w) < w / 2) 30 else 220) }
        assertTrue(BlurDetector.laplacianVariance(px, w, h) > 100)
    }

    @Test
    fun `decision floue avec seuil par defaut`() {
        val w = 16; val h = 16
        val px = IntArray(w * h) { i -> grayPixel(if ((i % w) < w / 2) 30 else 220) }
        val blurry = DeblurProcessor.boxBlur3(px, w, h, 3)
        val r = BlurDetector.evaluate(px, w, h, blurry, w, h, BlurDetector.Settings())
        assertTrue(r.fineScore > 0)
    }
}
