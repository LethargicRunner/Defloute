package com.lethargicrunner.defloute

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Défloutage directionnel : corrige le flou de MOUVEMENT (photo prise en bougeant),
 * que l'unsharp multi-échelle ne rattrape pas bien.
 *
 * 1. Estimation de la direction du mouvement : histogramme des orientations de gradients
 *    (pondérées par leur magnitude). Le mouvement est perpendiculaire au gradient dominant.
 * 2. Déconvolution de Richardson-Lucy avec un noyau linéaire 1D orienté (longueur réglable).
 *
 * Pur Kotlin, testable en JVM.
 */
object MotionDeblur {

    /**
     * Estime la direction du flou de mouvement en radians.
     * Renvoie null si l'image ne présente pas de direction dominante (flou isotrope).
     */
    fun estimateDirection(px: IntArray, w: Int, h: Int): Float? {
        if (w < 3 || h < 3) return null
        val bins = 18
        val hist = DoubleArray(bins)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = lum(px[i + 1]) - lum(px[i - 1])
                val gy = lum(px[i + w]) - lum(px[i - w])
                val mag = abs(gx) + abs(gy)
                if (mag < 24f) continue // ignore les zones plates (bruit seul)
                var ang = atan2(gy.toDouble(), gx.toDouble()) // orientation du gradient
                if (ang < 0) ang += Math.PI // gradients opposés = même direction de flou
                val bin = ((ang / Math.PI) * bins).toInt().coerceIn(0, bins - 1)
                hist[bin] += mag
            }
        }
        // lissage circulaire
        val smooth = DoubleArray(bins)
        for (b in 0 until bins) {
            smooth[b] = hist[(b + bins - 1) % bins] + 2 * hist[b] + hist[(b + 1) % bins]
        }
        var best = 0
        for (b in 1 until bins) if (smooth[b] > smooth[best]) best = b
        val total = smooth.sum()
        if (total <= 0) return null
        // direction dominante assez marquée ? (sinon le défloutage directionnel n'aide pas)
        val dominance = smooth[best] / total
        if (dominance < 0.18) return null
        // le gradient est perpendiculaire au mouvement
        val gradAngle = (best + 0.5) / bins * Math.PI
        return (gradAngle - Math.PI / 2).toFloat()
    }

    /**
     * Déconvolution de Richardson-Lucy avec un noyau linéaire de [length] pixels
     * orienté selon [angle] (radians). [iterations] 4..12, [strength] 0..1 mélange avec l'original.
     */
    fun deblur(px: IntArray, w: Int, h: Int, angle: Float, length: Int = 7, iterations: Int = 8, strength: Float = 1f): IntArray {
        val len = (length.coerceAtLeast(3) or 1) // impair
        val r = len / 2
        val dx = cos(angle.toDouble())
        val dy = sin(angle.toDouble())

        // noyau 1D uniforme (hypothèse : vitesse constante pendant la pose)
        val kernel = FloatArray(len) { 1f / len }

        // canaux float 0..1
        val n = w * h
        val ch = arrayOf(FloatArray(n), FloatArray(n), FloatArray(n))
        for (i in 0 until n) {
            val p = px[i]
            ch[0][i] = ((p shr 16) and 0xFF) / 255f
            ch[1][i] = ((p shr 8) and 0xFF) / 255f
            ch[2][i] = (p and 0xFF) / 255f
        }

        for (c in 0..2) {
            var u = ch[c]
            for (it2 in 0 until iterations) {
                // c1 = k * u  (convolution)
                val c1 = convolve1D(u, w, h, dx, dy, r, kernel, normalize = false)
                // ratio = image / c1 (borné)
                val ratio = FloatArray(n)
                for (i in 0 until n) {
                    val d = c1[i]
                    ratio[i] = if (d > 1e-4f) (ch[c][i] / d).coerceIn(0f, 3f) else 1f
                }
                // c2 = k_flip * ratio
                val c2 = convolve1D(ratio, w, h, dx, dy, r, kernel, normalize = false, flip = true)
                for (i in 0 until n) u[i] = (u[i] * c2[i]).coerceIn(0f, 1f)
            }
        }

        val out = IntArray(n)
        val mix = strength.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val p = px[i]
            val rOut = blend(((p shr 16) and 0xFF) / 255f, ch[0][i], mix)
            val gOut = blend(((p shr 8) and 0xFF) / 255f, ch[1][i], mix)
            val bOut = blend((p and 0xFF) / 255f, ch[2][i], mix)
            out[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
        }
        return out
    }

    private fun blend(orig: Float, deblurred: Float, mix: Float): Int {
        val v = orig + (deblurred - orig) * mix
        return (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Convolution 1D le long de la direction (dx, dy), avec échantillonnage bilinéaire. */
    private fun convolve1D(
        src: FloatArray, w: Int, h: Int,
        dx: Double, dy: Double, r: Int, kernel: FloatArray,
        normalize: Boolean, flip: Boolean = false
    ): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (t in -r..r) {
                    val tt = if (flip) -t else t
                    val sx = x + tt * dx
                    val sy = y + tt * dy
                    acc += kernel[if (flip) r - (t + r) else t + r] * sampleBilinear(src, w, h, sx.toFloat(), sy.toFloat())
                }
                out[y * w + x] = if (normalize) acc else acc // noyau déjà normalisé (somme = 1)
            }
        }
        return out
    }

    private fun sampleBilinear(src: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val xa = x0.coerceIn(0, w - 1)
        val xb = (x0 + 1).coerceIn(0, w - 1)
        val ya = y0.coerceIn(0, h - 1)
        val yb = (y0 + 1).coerceIn(0, h - 1)
        val v00 = src[ya * w + xa]
        val v10 = src[ya * w + xb]
        val v01 = src[yb * w + xa]
        val v11 = src[yb * w + xb]
        return (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy
    }

    private fun lum(p: Int): Float =
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)

    /**
     * Enchaîne estimation + déconvolution, avec garde-fous :
     * renvoie l'original si aucune direction dominante n'est trouvée.
     */
    fun autoDeblur(px: IntArray, w: Int, h: Int, strength: Float = 0.8f): IntArray {
        val angle = estimateDirection(px, w, h) ?: return px
        return deblur(px, w, h, angle, length = 7, iterations = 8, strength = strength)
    }
}
