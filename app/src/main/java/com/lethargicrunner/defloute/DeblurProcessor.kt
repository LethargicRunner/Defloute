package com.lethargicrunner.defloute

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Défloutage en pur Kotlin, sans dépendance externe.
 *
 * Pipeline :
 *  1. Upscale bilinéaire x2 (restaure un peu de définition, facilite le sharpening).
 *  2. Unsharp mask multi-échelle : 3 passes de masque flou (rayons fin / moyen / large),
 *     chaque échelle rattrape un type de flou différent (léger, moyen, fort).
 *  3. Ré-étalonnage doux pour éviter le halo et l'écrêtage.
 *
 * Tout travaille sur IntArray ARGB pour rester rapide et testable en JVM pur.
 */
object DeblurProcessor {

    data class Params(
        /** Force globale 0..1. 0.5 = réglage neutre conseillé. */
        val strength: Float = 0.5f,
        /** Upscale x2 avant traitement. */
        val upscale: Boolean = true
    )

    /** Traite un bitmap Android (wrapper pratique, logique testée sur IntArray). */
    fun process(pixels: IntArray, width: Int, height: Int, params: Params): Processed {
        val (upPx, upW, upH) = if (params.upscale) {
            Triple(upscaleBilinear(pixels, width, height, 2), width * 2, height * 2)
        } else {
            Triple(pixels.copyOf(), width, height)
        }

        val strength = params.strength.coerceIn(0f, 1f)
        // Chaque échelle contribue proportionnellement à la force.
        val amounts = floatArrayOf(
            0.55f * strength, // rayon fin : flou léger / détails
            0.35f * strength, // rayon moyen : flou de mise au point
            0.25f * strength  // rayon large : flou de mouvement doux
        )
        val radii = intArrayOf(1, 3, 6)

        var current = upPx
        for (i in radii.indices) {
            val blurred = boxBlur3(current, upW, upH, radii[i])
            current = unsharp(current, blurred, amounts[i])
        }

        return Processed(current, upW, upH)
    }

    data class Processed(val pixels: IntArray, val width: Int, val height: Int)

    /** Score de netteté après traitement (même métrique que BlurDetector : variance du Laplacien). */
    fun sharpness(pixels: IntArray, width: Int, height: Int): Double =
        BlurDetector.laplacianVariance(pixels, width, height)

    // ---------------------------------------------------------------- upscale

    fun upscaleBilinear(src: IntArray, w: Int, h: Int, factor: Int): IntArray {
        val nw = w * factor
        val nh = h * factor
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val gy = y * (h - 1) / max(nh - 1, 1).toFloat()
            val y0 = gy.toInt().coerceIn(0, h - 1)
            val y1 = (y0 + 1).coerceIn(0, h - 1)
            val fy = gy - y0
            for (x in 0 until nw) {
                val gx = x * (w - 1) / max(nw - 1, 1).toFloat()
                val x0 = gx.toInt().coerceIn(0, w - 1)
                val x1 = (x0 + 1).coerceIn(0, w - 1)
                val fx = gx - x0
                val c00 = src[y0 * w + x0]
                val c10 = src[y0 * w + x1]
                val c01 = src[y1 * w + x0]
                val c11 = src[y1 * w + x1]
                out[y * nw + x] = bilinearPixel(c00, c10, c01, c11, fx, fy)
            }
        }
        return out
    }

    private fun bilinearPixel(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
        val a = lerpChannel(c00, c10, fx)
        val b = lerpChannel(c01, c11, fx)
        return lerpChannel(a, b, fy)
    }

    private fun lerpChannel(c0: Int, c1: Int, t: Float): Int {
        val a0 = (c0 shr 0) and 0xFF; val a1 = (c1 shr 0) and 0xFF
        val r0 = (c0 shr 8) and 0xFF; val r1 = (c1 shr 8) and 0xFF
        val g0 = (c0 shr 16) and 0xFF; val g1 = (c1 shr 16) and 0xFF
        val b0 = (c0 shr 24) and 0xFF; val b1 = (c1 shr 24) and 0xFF
        val mix = { v0: Int, v1: Int -> (v0 + (v1 - v0) * t).roundToInt().coerceIn(0, 255) }
        return (mix(b0, b1) shl 24) or (mix(g0, g1) shl 16) or (mix(r0, r1) shl 8) or mix(a0, a1)
    }

    // ---------------------------------------------------------------- flou (masque)

    /**
     * Box blur appliqué 3 fois (approximation gaussienne), rayon r.
     * Séparable : horizontal puis vertical, avec sommes glissantes -> O(n) par passe.
     */
    fun boxBlur3(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceAtLeast(1)
        var tmp = IntArray(src.size)
        var cur = src.copyOf()
        repeat(3) {
            tmp = boxBlurPass(cur, tmp, w, h, r, horizontal = true)
            cur = boxBlurPass(tmp, cur.copyOf(), w, h, r, horizontal = false)
        }
        return cur
    }

    private fun boxBlurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        val stepIn = if (horizontal) 1 else w
        val stepOut = if (horizontal) w else 1
        val win = 2 * r + 1
        for (o in 0 until outer) {
            val base = o * stepOut
            var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
            for (k in -r..r) {
                val idx = base + k.coerceIn(0, inner - 1) * stepIn
                sumA += (src[idx] shr 0) and 0xFF
                sumR += (src[idx] shr 8) and 0xFF
                sumG += (src[idx] shr 16) and 0xFF
                sumB += (src[idx] shr 24) and 0xFF
            }
            for (i in 0 until inner) {
                val idx = base + i * stepIn
                dst[idx] = pack(sumA / win, sumR / win, sumG / win, sumB / win)
                val addIdx = base + (i + r + 1).coerceIn(0, inner - 1) * stepIn
                val remIdx = base + (i - r).coerceIn(0, inner - 1) * stepIn
                sumA += ((src[addIdx] shr 0) and 0xFF) - ((src[remIdx] shr 0) and 0xFF)
                sumR += ((src[addIdx] shr 8) and 0xFF) - ((src[remIdx] shr 8) and 0xFF)
                sumG += ((src[addIdx] shr 16) and 0xFF) - ((src[remIdx] shr 16) and 0xFF)
                sumB += ((src[addIdx] shr 24) and 0xFF) - ((src[remIdx] shr 24) and 0xFF)
            }
        }
        return dst
    }

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (b.coerceIn(0, 255) shl 24) or (g.coerceIn(0, 255) shl 16) or (r.coerceIn(0, 255) shl 8) or a.coerceIn(0, 255)

    // ---------------------------------------------------------------- unsharp

    /** out = src + amount * (src - blurred), avec clamp doux. */
    fun unsharp(src: IntArray, blurred: IntArray, amount: Float): IntArray {
        val out = IntArray(src.size)
        for (i in src.indices) {
            val s = src[i]; val b = blurred[i]
            val a = (s shr 0) and 0xFF
            val r = (s shr 8) and 0xFF
            val g = (s shr 16) and 0xFF
            val bl = (s shr 24) and 0xFF
            out[i] = pack(
                sharpen(a, (b shr 0) and 0xFF, amount),
                sharpen(r, (b shr 8) and 0xFF, amount),
                sharpen(g, (b shr 16) and 0xFF, amount),
                sharpen(bl, (b shr 24) and 0xFF, amount)
            )
        }
        return out
    }

    private fun sharpen(v: Int, blur: Int, amount: Float): Int {
        val detail = v - blur
        // Clamp doux : au-delà de ±40 de détail, rendu sous-linéaire pour éviter les halos.
        val boosted = if (kotlin.math.abs(detail) <= 40) detail * amount
        else kotlin.math.sign(detail.toFloat()) * (40 * amount + (kotlin.math.abs(detail) - 40) * amount * 0.4f)
        return (v + boosted).roundToInt().coerceIn(0, 255)
    }
}
