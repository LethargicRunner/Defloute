package com.lethargicrunner.defloute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeblurProcessorTest {

    private fun flat(w: Int, h: Int, gray: Int): IntArray =
        IntArray(w * h) { (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray }

    private fun grayPixel(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun `upscale double les dimensions`() {
        val src = IntArray(3 * 3) { grayPixel(128) }
        val out = DeblurProcessor.upscaleBilinear(src, 3, 3, 2)
        assertEquals(6 * 6, out.size)
    }

    @Test
    fun `upscale preserve une image uniforme`() {
        val src = flat(4, 4, 90)
        val out = DeblurProcessor.upscaleBilinear(src, 4, 4, 2)
        assertTrue(out.all { ((it shr 16) and 0xFF) == 90 })
    }

    @Test
    fun `box blur d une image uniforme reste uniforme`() {
        val src = flat(8, 8, 120)
        val out = DeblurProcessor.boxBlur3(src, 8, 8, 2)
        assertTrue(out.all { ((it shr 16) and 0xFF) == 120 })
    }

    @Test
    fun `unsharp augmente le contraste local`() {
        // Bord net au centre : colonne 0 sombre, colonne 1 claire
        val src = IntArray(4 * 4) { i -> grayPixel(if (i % 4 < 2) 60 else 180) }
        val blurred = DeblurProcessor.boxBlur3(src, 4, 4, 1)
        val out = DeblurProcessor.unsharp(src, blurred, 0.8f)
        val darkBefore = (src[0] shr 16) and 0xFF
        val darkAfter = (out[0] shr 16) and 0xFF
        assertTrue(darkAfter < darkBefore) // le côté sombre devient plus sombre
    }

    @Test
    fun `process augmente le score de nettete d une image floue`() {
        val w = 24; val h = 24
        val sharp = IntArray(w * h) { i -> grayPixel(if ((i % w) < w / 2) 60 else 180) }
        val blurry = DeblurProcessor.boxBlur3(sharp, w, h, 2) // flou synthétique
        val before = DeblurProcessor.sharpness(blurry, w, h)
        val out = DeblurProcessor.process(blurry, w, h, DeblurProcessor.Params(0.7f, upscale = false))
        val after = DeblurProcessor.sharpness(out.pixels, out.width, out.height)
        assertTrue("attendu after > before : $after vs $before", after > before)
    }

    @Test
    fun `strength zero laisse l image quasi inchangee`() {
        val w = 8; val h = 8
        val src = IntArray(w * h) { grayPixel((it * 7) % 256) }
        val out = DeblurProcessor.process(src, w, h, DeblurProcessor.Params(0f, upscale = false))
        val diff = out.pixels.indices.count { i ->
            ((out.pixels[i] shr 16) and 0xFF) != ((src[i] shr 16) and 0xFF)
        }
        assertEquals(0, diff)
    }
}
