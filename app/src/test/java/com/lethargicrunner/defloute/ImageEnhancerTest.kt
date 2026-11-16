package com.lethargicrunner.defloute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEnhancerTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun gray(v: Int) = rgb(v, v, v)
    private fun lum(p: Int) = (p shr 8) and 0xFF   // pour un pixel gris, G = niveau

    /** Bruit pseudo-aléatoire déterministe (pas de dépendance à java.util.Random). */
    private fun noisy(w: Int, h: Int, base: Int, amp: Int): IntArray {
        var seed = 12345L
        return IntArray(w * h) {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            gray((base + (seed % (2 * amp + 1)).toInt() - amp).coerceIn(0, 255))
        }
    }

    private fun variance(px: IntArray): Double {
        val m = px.map { lum(it).toDouble() }.average()
        return px.map { (lum(it) - m) * (lum(it) - m) }.average()
    }

    @Test
    fun `denoise reduit le bruit d un aplat`() {
        val src = noisy(32, 32, 128, 20)
        val out = ImageEnhancer.denoise(src, 32, 32, 0.8f)
        assertEquals(src.size, out.size)
        assertTrue("variance ${variance(out)} vs ${variance(src)}", variance(out) < variance(src) * 0.6)
    }

    @Test
    fun `denoise conserve un contour net`() {
        val w = 16; val h = 16
        val src = IntArray(w * h) { i -> gray(if (i % w < w / 2) 30 else 220) }
        val out = ImageEnhancer.denoise(src, w, h, 1f)
        assertTrue(lum(out[8 * w + 3]) < 40)
        assertTrue(lum(out[8 * w + 12]) > 210)
    }

    @Test
    fun `denoise force zero ne change rien`() {
        val src = noisy(8, 8, 100, 30)
        val out = ImageEnhancer.denoise(src, 8, 8, 0f)
        assertTrue(src.indices.all { src[it] == out[it] })
    }

    @Test
    fun `niveaux auto etirent une image voilee`() {
        val src = noisy(32, 32, 125, 25)      // niveaux 100..150
        ImageEnhancer.autoLevels(src)
        val mn = src.minOf { lum(it) }
        val mx = src.maxOf { lum(it) }
        assertTrue("min $mn", mn < 15)
        assertTrue("max $mx", mx > 240)
    }

    @Test
    fun `niveaux auto ne touchent pas une image uniforme`() {
        val src = IntArray(64) { gray(128) }
        ImageEnhancer.autoLevels(src)
        assertTrue(src.all { lum(it) == 128 })
    }

    @Test
    fun `courbe de tons remonte les ombres et tasse les hautes lumieres`() {
        val dark = intArrayOf(gray(20))
        ImageEnhancer.toneCurve(dark, 1f, 0f)
        assertTrue(lum(dark[0]) > 20)
        val bright = intArrayOf(gray(240))
        ImageEnhancer.toneCurve(bright, 0f, 1f)
        assertTrue(lum(bright[0]) < 240)
    }

    @Test
    fun `courbe de tons reste monotone`() {
        val ramp = IntArray(256) { gray(it) }
        ImageEnhancer.toneCurve(ramp, 1f, 1f)
        for (i in 1 until 256) assertTrue("i=$i", lum(ramp[i]) >= lum(ramp[i - 1]))
    }

    @Test
    fun `tone garde les dimensions et une image uniforme`() {
        val src = IntArray(40 * 30) { gray(100) }
        val out = ImageEnhancer.tone(src, 40, 30, ImageEnhancer.ToneParams(autoLevels = false, shadows = 0f, highlights = 0f))
        assertEquals(src.size, out.size)
        assertTrue(out.all { lum(it) == 100 })
    }
}
