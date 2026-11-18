package com.lethargicrunner.defloute

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** Noyau de flou carré (taille impaire), somme = 1. */
class Kernel(val size: Int, val data: FloatArray)

/** Constructeurs des noyaux de flou utilisés pour la déconvolution. */
object BlurKernels {

    private fun normalized(size: Int, d: FloatArray): Kernel {
        var s = 0f
        for (v in d) s += v
        if (s > 0f) for (i in d.indices) d[i] /= s
        return Kernel(size, d)
    }

    /** Flou de bougé : segment de longueur [length] pixels, orienté de [angleRad]. */
    fun motion(length: Float, angleRad: Double): Kernel {
        val l = max(length, 1.5f)
        var n = l.toInt() + 4
        if (n % 2 == 0) n++
        val k = FloatArray(n * n)
        val c = n / 2
        val steps = (l * 8).toInt()
        val ca = cos(angleRad); val sa = sin(angleRad)
        for (i in 0..steps) {
            val t = -l / 2.0 + l.toDouble() * i / steps
            val x = c + t * ca; val y = c + t * sa
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
            val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
            fun add(dx: Int, dy: Int, w: Float) {
                val xx = x0 + dx; val yy = y0 + dy
                if (xx in 0 until n && yy in 0 until n) k[yy * n + xx] += w
            }
            add(0, 0, (1 - fx) * (1 - fy)); add(1, 0, fx * (1 - fy))
            add(0, 1, (1 - fx) * fy); add(1, 1, fx * fy)
        }
        return normalized(n, k)
    }

    fun gaussian(sigma: Float): Kernel {
        val s = max(sigma, 0.4f)
        var n = (6 * s).toInt() or 1
        if (n < 3) n = 3
        val c = n / 2
        val k = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x - c).toFloat(); val dy = (y - c).toFloat()
            k[y * n + x] = exp(-(dx * dx + dy * dy) / (2 * s * s))
        }
        return normalized(n, k)
    }

    /** Disque de rayon [r] (flou de mise au point), sur-échantillonné pour des bords doux. */
    fun disk(r: Float): Kernel {
        val rr = max(r, 0.8f)
        var n = (2 * ceil(rr.toDouble()) + 3).toInt()
        if (n % 2 == 0) n++
        val ss = 8
        val k = FloatArray(n * n)
        val half = n / 2.0
        for (y in 0 until n) for (x in 0 until n) {
            var cnt = 0
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val px = x + (sx + 0.5) / ss - half
                val py = y + (sy + 0.5) / ss - half
                if (px * px + py * py <= rr * rr) cnt++
            }
            k[y * n + x] = cnt.toFloat() / (ss * ss)
        }
        return normalized(n, k)
    }

    /**
     * Flou isotrope « mixte » : moyenne d'un noyau gaussien et d'un disque d'étalement équivalent.
     * Mesuré sur le jeu de test : meilleur compromis entre flou de mise au point (disque) et
     * flou doux d'objectif (gaussien).
     */
    fun isotropicMix(sigma: Float): Kernel {
        val g = gaussian(sigma)
        val d = disk(max(sigma * 2f, 0.8f))
        val n = max(g.size, d.size)
        val out = FloatArray(n * n)
        fun put(k: Kernel) {
            val o = (n - k.size) / 2
            for (y in 0 until k.size) for (x in 0 until k.size) out[(y + o) * n + x + o] += 0.5f * k.data[y * k.size + x]
        }
        put(g); put(d)
        return normalized(n, out)
    }
}
