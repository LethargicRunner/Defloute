package com.lethargicrunner.defloute

import com.lethargicrunner.defloute.PhotoDiagnosis.BlurType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Tests du moteur de diagnostic et de restauration automatique (sur des scènes synthétiques). */
class AutoDiagnosisTest {

    // ---------------------------------------------------------------- outils de test

    private class Rng(var s: Long = 987654321L) {
        fun next(): Double { s = (s * 6364136223846793005L + 1442695040888963407L); return ((s ushr 11) and 0xFFFFFFFFFFFFL).toDouble() / 0x1000000000000L }
        fun gauss(): Double = sqrt(-2.0 * ln(next().coerceAtLeast(1e-12))) * cos(2.0 * Math.PI * next())
        fun range(a: Double, b: Double) = a + (b - a) * next()
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v.coerceIn(0, 255) * 0x010101)

    /** Scène nette : rectangles, disques et bandes obliques de niveaux variés. */
    private fun scene(w: Int, h: Int, seed: Long = 5L): IntArray {
        val rng = Rng(seed)
        val lum = FloatArray(w * h) { 110f }
        repeat(70) {
            val v = rng.range(20.0, 235.0).toFloat()
            val cx = rng.range(0.0, w.toDouble()); val cy = rng.range(0.0, h.toDouble())
            val kind = (rng.next() * 3).toInt()
            val s = rng.range(10.0, 45.0)
            val ang = rng.range(0.0, Math.PI)
            for (y in 0 until h) for (x in 0 until w) {
                val dx = x - cx; val dy = y - cy
                val inside = when (kind) {
                    0 -> abs(dx) < s && abs(dy) < s * 0.7
                    1 -> dx * dx + dy * dy < s * s * 0.6
                    else -> { val u = dx * cos(ang) + dy * Math.sin(ang); val t = -dx * Math.sin(ang) + dy * cos(ang); abs(u) < s * 0.4 && abs(t) < s * 1.3 }
                }
                if (inside) lum[y * w + x] = v
            }
        }
        return IntArray(w * h) { gray(lum[it].toInt()) }
    }

    /** Convolution naïve (bords réfléchis) de chaque canal. */
    private fun blur(px: IntArray, w: Int, h: Int, k: Kernel): IntArray {
        val out = IntArray(px.size); val c = k.size / 2
        fun refl(i: Int, n: Int): Int { var j = i; if (j < 0) j = -j; if (j >= n) j = 2 * (n - 1) - j; return j.coerceIn(0, n - 1) }
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f
            for (ky in 0 until k.size) for (kx in 0 until k.size) {
                val wgt = k.data[ky * k.size + kx]
                if (wgt == 0f) continue
                val p = px[refl(y + ky - c, h) * w + refl(x + kx - c, w)]
                r += wgt * ((p shr 16) and 0xFF); g += wgt * ((p shr 8) and 0xFF); b += wgt * (p and 0xFF)
            }
            out[y * w + x] = (0xFF shl 24) or ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b + 0.5f).toInt().coerceIn(0, 255)
        }
        return out
    }

    private fun addNoise(px: IntArray, sigma: Double, seed: Long = 11L): IntArray {
        val rng = Rng(seed)
        return IntArray(px.size) {
            val p = px[it]
            fun ch(v: Int) = (v + rng.gauss() * sigma + 0.5).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch((p shr 16) and 0xFF) shl 16) or (ch((p shr 8) and 0xFF) shl 8) or ch(p and 0xFF)
        }
    }

    private fun mse(a: IntArray, b: IntArray): Double {
        var s = 0.0
        for (i in a.indices) for (sh in intArrayOf(16, 8, 0)) { val d = ((a[i] shr sh) and 0xFF) - ((b[i] shr sh) and 0xFF); s += d * d }
        return s / (a.size * 3)
    }

    private fun median(px: IntArray): Int = px.map { PhotoDiagnosis.luma(intArrayOf(it))[0].toInt() }.sorted()[px.size / 2]

    // ---------------------------------------------------------------- FFT, noyaux, déconvolution

    @Test
    fun `fft aller-retour restitue le signal`() {
        val n = 64; val plan = FftPlan(n); val rng = Rng(3)
        val re = FloatArray(n * n) { rng.range(0.0, 255.0).toFloat() }; val im = FloatArray(n * n)
        val ref = re.copyOf()
        plan.fft2d(re, im, false); plan.fft2d(re, im, true)
        var maxErr = 0f
        for (i in re.indices) maxErr = maxOf(maxErr, abs(re[i] - ref[i]))
        assertTrue("erreur max $maxErr", maxErr < 0.05f)
    }

    @Test
    fun `noyaux normalises et orientes`() {
        for (k in listOf(BlurKernels.motion(12f, 0.5), BlurKernels.gaussian(2f), BlurKernels.disk(3f), BlurKernels.isotropicMix(2f))) {
            assertEquals(1.0, k.data.sum().toDouble(), 1e-4)
        }
        val h = BlurKernels.motion(11f, 0.0)          // horizontal : toute l'énergie sur la ligne centrale
        val c = h.size / 2
        var line = 0f; for (x in 0 until h.size) line += h.data[c * h.size + x]
        assertTrue("énergie ligne centrale $line", line > 0.95f)
    }

    @Test
    fun `wiener reduit l erreur d un flou connu`() {
        val w = 96; val h = 96
        val sharp = scene(w, h, 8L)
        val k = BlurKernels.gaussian(2f)
        val blurred = blur(sharp, w, h, k)
        val restored = AutoRestore.deconvolveLuma(blurred, w, h, k, 0.003f)
        val e0 = mse(sharp, blurred); val e1 = mse(sharp, restored)
        assertTrue("MSE flou $e0 -> restauré $e1", e1 < e0 * 0.7)
    }

    // ---------------------------------------------------------------- diagnostic

    @Test
    fun `bruit gaussien par canal est estime`() {
        val flat = IntArray(160 * 160) { gray(128) }
        val d = PhotoDiagnosis.analyse(addNoise(flat, 8.0), 160, 160)
        assertTrue("σ estimé ${d.noiseSigma}", d.noiseSigma in 6f..10.5f)
        assertEquals(BlurType.NONE, d.blurType)
    }

    @Test
    fun `image uniforme sans defaut`() {
        val d = PhotoDiagnosis.analyse(IntArray(128 * 128) { gray(128) }, 128, 128)
        assertTrue(d.noiseSigma < 0.5f)
        assertEquals(BlurType.NONE, d.blurType)
        assertTrue(!d.jpegMarked)
    }

    @Test
    fun `grille jpeg 8x8 detectee`() {
        val rng = Rng(21)
        val w = 128; val h = 128
        val blocks = IntArray((w / 8) * (h / 8)) { rng.range(80.0, 170.0).toInt() }
        val px = IntArray(w * h) { gray(blocks[(it / w / 8) * (w / 8) + (it % w) / 8]) }
        val b = PhotoDiagnosis.blockiness(PhotoDiagnosis.luma(px), w, h)
        assertTrue("blockiness $b", b > 2f)
        val smooth = IntArray(w * h) { gray(40 + (it % w)) }
        val b2 = PhotoDiagnosis.blockiness(PhotoDiagnosis.luma(smooth), w, h)
        assertTrue("lisse $b2", b2 < 1.1f)
    }

    @Test
    fun `scene nette sans flou detecte`() {
        val d = PhotoDiagnosis.analyse(scene(256, 256), 256, 256)
        assertEquals(BlurType.NONE, d.blurType)
    }

    @Test
    fun `flou de bouge detecte avec direction et longueur`() {
        val w = 256; val h = 256
        val k = BlurKernels.motion(14f, Math.toRadians(30.0))
        val d = PhotoDiagnosis.analyse(blur(scene(w, h), w, h, k), w, h)
        assertTrue("type ${d.blurType}", d.blurType == BlurType.MOTION)
        val dAng = abs(((d.motionAngleDeg - 30f) + 90f) % 180f - 90f)
        assertTrue("angle estimé ${d.motionAngleDeg}", dAng < 12f)
        assertTrue("longueur estimée ${d.motionLength}", d.motionLength in 8f..20f)
    }

    @Test
    fun `flou isotrope detecte`() {
        val w = 256; val h = 256
        val d = PhotoDiagnosis.analyse(blur(scene(w, h), w, h, BlurKernels.gaussian(2.5f)), w, h)
        assertEquals(BlurType.ISOTROPIC, d.blurType)
        assertTrue("σ estimé ${d.isoSigma}", d.isoSigma in 1.2f..4.5f)
    }

    // ---------------------------------------------------------------- restauration automatique

    @Test
    fun `scene nette non modifiee`() {
        val px = scene(160, 160)
        val r = AutoRestore.run(px, 160, 160)
        assertEquals(0.0, mse(px, r.pixels), 1e-9)
        assertTrue(r.steps.isEmpty())
    }

    @Test
    fun `flou de bouge corrige`() {
        val w = 192; val h = 192
        val sharp = scene(w, h)
        val blurred = blur(sharp, w, h, BlurKernels.motion(12f, Math.toRadians(60.0)))
        val r = AutoRestore.run(blurred, w, h, tuning = AutoRestore.Tuning(toneTarget = 0f))
        val e0 = mse(sharp, blurred); val e1 = mse(sharp, r.pixels)
        assertTrue("MSE ${e0} -> ${e1}", e1 < e0)
    }

    @Test
    fun `bruit reduit`() {
        val w = 128; val h = 128
        val sharp = scene(w, h)
        val noisy = addNoise(sharp, 14.0)
        val r = AutoRestore.run(noisy, w, h, tuning = AutoRestore.Tuning(toneTarget = 0f))
        assertTrue("MSE ${mse(sharp, noisy)} -> ${mse(sharp, r.pixels)}", mse(sharp, r.pixels) < mse(sharp, noisy) * 0.8)
    }

    @Test
    fun `photo sombre eclaircie`() {
        val dark = scene(128, 128).map { gray(((it and 0xFF) * 0.25).toInt()) }.toIntArray()
        val r = AutoRestore.run(dark, 128, 128)
        assertTrue("médiane ${median(dark)} -> ${median(r.pixels)}", median(r.pixels) > median(dark) + 20)
    }
}
