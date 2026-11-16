package com.lethargicrunner.defloute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class EnhanceTest {

    private fun grayPixel(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun lumPx(px: IntArray): DoubleArray =
        DoubleArray(px.size) { i -> 0.299 * ((px[i] shr 16) and 0xFF) + 0.587 * ((px[i] shr 8) and 0xFF) + 0.114 * (px[i] and 0xFF) }

    private fun variance(px: IntArray, w: Int, h: Int): Double =
        BlurDetector.laplacianVariance(px, w, h)

    /** Flou de mouvement horizontal synthétique : moyenne glissante de 7 px. */
    private fun motionBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var n = 0
                for (t in -radius..radius) {
                    val xx = (x + t).coerceIn(0, w - 1)
                    sum += (src[y * w + xx] shr 16) and 0xFF
                    n++
                }
                out[y * w + x] = grayPixel(sum / n)
            }
        }
        return out
    }

    @Test
    fun `direction du mouvement detectee pour un flou horizontal`() {
        val w = 48; val h = 48
        val sharp = IntArray(w * h) { i -> grayPixel(if ((i % w) < w / 2) 40 else 210) }
        val blurry = motionBlur(sharp, w, h, 3)
        val angle = MotionDeblur.estimateDirection(blurry, w, h)
        // mouvement horizontal -> angle proche de 0 ou PI (mod PI)
        assertTrue("angle=$angle", angle != null && (abs(angle) < 0.35f || abs(abs(angle) - Math.PI.toFloat()) < 0.35f))
    }

    @Test
    fun `deconvolution directionnelle augmente la nettete`() {
        val w = 48; val h = 48
        val sharp = IntArray(w * h) { i -> grayPixel(if ((i % w) < w / 2) 40 else 210) }
        val blurry = motionBlur(sharp, w, h, 3)
        val angle = MotionDeblur.estimateDirection(blurry, w, h) ?: 0f
        val restored = MotionDeblur.deblur(blurry, w, h, angle, length = 7, iterations = 8)
        val before = variance(blurry, w, h)
        val after = variance(restored, w, h)
        assertTrue("attendu after > before : $after vs $before", after > before)
    }

    @Test
    fun `autoDeblur garde l original sans direction dominante`() {
        val w = 24; val h = 24
        val flat = IntArray(w * h) { grayPixel(120 + (it % 3)) } // quasi uniforme
        val out = MotionDeblur.autoDeblur(flat, w, h)
        assertTrue(out === flat || out.contentEquals(flat))
    }

    @Test
    fun `nlm supprime le bruit d une zone plate sans la changer autrement`() {
        val w = 32; val h = 32
        val clean = IntArray(w * h) { grayPixel(128) }
        // bruit +-30 sur la luminance
        val noisy = IntArray(w * h) { i -> grayPixel((128 + if (i % 2 == 0) 30 else -30).coerceIn(0, 255)) }
        val out = NlmDenoise.denoise(noisy, w, h, strength = 0.9f)
        // l'écart à 128 doit diminuer nettement
        fun dev(p: IntArray): Double = lumPx(p).map { abs(it - 128) }.average()
        assertTrue("dev(out)=${dev(out)} dev(noisy)=${dev(noisy)}", dev(out) < dev(noisy) / 2)
        // une image déjà propre reste quasi identique
        val cleanOut = NlmDenoise.denoise(clean, w, h, strength = 0.9f)
        val maxDiff = cleanOut.indices.maxOf { abs(((cleanOut[it] shr 16) and 0xFF) - 128) }
        assertTrue("maxDiff=$maxDiff", maxDiff <= 8)
    }

    @Test
    fun `micro contraste augmente le score de nettete`() {
        val w = 32; val h = 32
        val soft = IntArray(w * h) { i -> grayPixel(128 + ((i % w) * 2).coerceAtMost(60)) }
        val out = EnhanceExtras.microContrast(soft, w, h, 0.5f)
        assertTrue(variance(out, w, h) > variance(soft, w, h))
    }

    @Test
    fun `micro contraste a amount zero ne change rien`() {
        val w = 16; val h = 16
        val src = IntArray(w * h) { grayPixel((it * 13) % 256) }
        val out = EnhanceExtras.microContrast(src, w, h, 0f)
        assertTrue(out.contentEquals(src))
    }
}
