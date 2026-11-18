package com.lethargicrunner.defloute

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Diagnostic automatique d'une photo : mesure le bruit, les artefacts JPEG, le type et l'ampleur du flou,
 * et l'exposition. Pur Kotlin, sans Android, validé sur un banc d'essai (photos nettes dégradées
 * artificiellement, résultats comparés à la vérité).
 *
 * Méthode du flou : on mesure la largeur des contours (contraste ÷ pente maximale) selon l'orientation
 * de leur normale. Un flou isotrope élargit tous les contours de la même façon ; un flou de bougé
 * n'élargit que ceux perpendiculaires au mouvement. L'ajustement du modèle
 * largeur(φ)² = w0² + (L·cos(φ−θ))² donne la direction θ et la longueur L du mouvement.
 */
object PhotoDiagnosis {

    enum class BlurType { NONE, MOTION, ISOTROPIC }

    data class Diagnosis(
        val width: Int,
        val height: Int,
        /** Écart-type du bruit (niveaux 0..255). */
        val noiseSigma: Float,
        /** ≈ 1 sans JPEG ; au-delà de 1,25 : compression marquée (blocs 8×8). */
        val blockiness: Float,
        val blurType: BlurType,
        /** Longueur du flou de bougé en pixels (pleine résolution), 0 sinon. */
        val motionLength: Float,
        /** Direction du mouvement en degrés (0..180), 0 sinon. */
        val motionAngleDeg: Float,
        /** Écart-type équivalent du flou isotrope en pixels (pleine résolution), 0 sinon. */
        val isoSigma: Float,
        /** Rapport largeur max / largeur min des contours (≥ 3 : flou directionnel). */
        val edgeRatio: Float,
        /** Largeur médiane des contours, corrigée du lissage d'analyse (px, résolution d'analyse). */
        val edgeWidth: Float,
        val medianLuma: Int,
        val p1: Int,
        val p99: Int,
        val clipWhite: Float,
        val clipBlack: Float,
        /** Raison pour laquelle l'agrandissement IA est conseillé, null sinon. */
        val aiReason: String? = null
    ) {
        val jpegMarked: Boolean get() = blockiness > JPEG_THRESHOLD
        val recommendAi: Boolean get() = aiReason != null

        /** Lignes lisibles pour l'écran de diagnostic. */
        fun summary(): List<String> {
            val lines = ArrayList<String>()
            lines += when (blurType) {
                BlurType.MOTION -> "Flou de bougé : ≈ ${motionLength.roundToInt()} px, direction ${motionAngleDeg.roundToInt()}°"
                BlurType.ISOTROPIC -> "Flou de mise au point / doux : ≈ ${"%.1f".format(isoSigma)} px"
                BlurType.NONE -> "Netteté : aucun flou notable détecté"
            }
            lines += when {
                noiseSigma < 2.5f -> "Bruit : faible (≈ ${"%.1f".format(noiseSigma)})"
                noiseSigma < 7f -> "Bruit : modéré (≈ ${"%.1f".format(noiseSigma)})"
                else -> "Bruit : fort (≈ ${"%.1f".format(noiseSigma)})"
            }
            if (jpegMarked) lines += "Compression JPEG marquée (blocs visibles)"
            lines += when {
                medianLuma < 85 -> "Exposition : photo sombre"
                medianLuma > 175 -> "Exposition : photo claire"
                else -> "Exposition : correcte"
            }
            if (p99 - p1 < 170) lines += "Contraste : faible (image voilée)"
            if (clipWhite > 0.08f) lines += "${(clipWhite * 100).roundToInt()} % de blanc pur (non récupérable)"
            if (clipBlack > 0.08f) lines += "${(clipBlack * 100).roundToInt()} % de noir pur (non récupérable)"
            aiReason?.let { lines += "IA conseillée : $it" }
            return lines
        }
    }

    const val JPEG_THRESHOLD = 1.25f
    private const val ANALYSIS_MAX = 768

    fun luma(px: IntArray): FloatArray = FloatArray(px.size) {
        val p = px[it]
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
    }

    // ------------------------------------------------------------------ bruit et JPEG

    /** Écart-type du bruit : médiane (MAD) de la réponse d'un filtre laplacien ; ×1,45 calibré sur le banc d'essai. */
    fun noiseSigma(y: FloatArray, w: Int, h: Int): Float {
        if (w < 5 || h < 5) return 0f
        val bins = 40960
        val hist = IntArray(bins)
        var n = 0
        for (yy in 1 until h - 1) {
            val o = yy * w
            for (x in 1 until w - 1) {
                val i = o + x
                val c = y[i - w - 1] - 2 * y[i - w] + y[i - w + 1] -
                    2 * y[i - 1] + 4 * y[i] - 2 * y[i + 1] +
                    y[i + w - 1] - 2 * y[i + w] + y[i + w + 1]
                hist[min(bins - 1, (abs(c) * 10f).toInt())]++
                n++
            }
        }
        var acc = 0
        var med = 0
        while (med < bins && acc + hist[med] < n / 2) { acc += hist[med]; med++ }
        val medAbs = (med + 0.5f) / 10f
        return medAbs / 0.6745f / 6f * 1.45f
    }

    /** Rapport des sauts d'intensité aux frontières des blocs 8×8 sur les autres (≈ 1 sans JPEG). */
    fun blockiness(y: FloatArray, w: Int, h: Int): Float {
        if (w < 24 || h < 24) return 1f
        val colSum = DoubleArray(w - 1)
        val rowSum = DoubleArray(h - 1)
        for (yy in 0 until h) for (x in 0 until w - 1) colSum[x] += abs(y[yy * w + x + 1] - y[yy * w + x]).toDouble()
        for (yy in 0 until h - 1) for (x in 0 until w) rowSum[yy] += abs(y[(yy + 1) * w + x] - y[yy * w + x]).toDouble()
        fun ratio(sum: DoubleArray, other: Int): Double {
            var on = 0.0; var onN = 0; var off = 0.0; var offN = 0
            for (i in sum.indices) if (i % 8 == 7) { on += sum[i]; onN++ } else { off += sum[i]; offN++ }
            val a = on / onN / other; val b = off / offN / other
            return a / max(b, 1e-6)
        }
        return (0.5 * (ratio(colSum, h) + ratio(rowSum, w))).toFloat()
    }

    // ------------------------------------------------------------------ flou : largeur des contours

    private class EdgeStats(val ang: DoubleArray, val med: FloatArray, val cnt: IntArray, val smooth: Float)

    private fun boxDownscale(y: FloatArray, w: Int, h: Int, f: Int): Triple<FloatArray, Int, Int> {
        if (f <= 1) return Triple(y, w, h)
        val dw = w / f; val dh = h / f
        val out = FloatArray(dw * dh)
        val inv = 1f / (f * f)
        for (yy in 0 until dh) for (x in 0 until dw) {
            var s = 0f
            for (j in 0 until f) for (i in 0 until f) s += y[(yy * f + j) * w + x * f + i]
            out[yy * dw + x] = s * inv
        }
        return Triple(out, dw, dh)
    }

    private fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val r = ceil(4.0 * sigma).toInt()
        val k = FloatArray(2 * r + 1)
        var s = 0f
        for (i in -r..r) { k[i + r] = exp(-(i * i) / (2.0 * sigma * sigma)).toFloat(); s += k[i + r] }
        for (i in k.indices) k[i] /= s
        val tmp = FloatArray(src.size)
        for (yy in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (i in -r..r) a += k[i + r] * src[yy * w + (x + i).coerceIn(0, w - 1)]
            tmp[yy * w + x] = a
        }
        val out = FloatArray(src.size)
        for (yy in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (i in -r..r) a += k[i + r] * tmp[(yy + i).coerceIn(0, h - 1) * w + x]
            out[yy * w + x] = a
        }
        return out
    }

    private fun bilinear(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, w - 1); val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = (x - x0).coerceIn(0f, 1f); val fy = (y - y0).coerceIn(0f, 1f)
        return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) +
            (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
    }

    /** Largeur médiane des contours par orientation (18 classes de 10°). */
    private fun edgeWidths(y: FloatArray, w: Int, h: Int, sig: Float): EdgeStats? {
        val nb = 18
        val r = min(30, (min(w, h) - 10) / 2)
        if (r < 8) return null
        val ss = (0.12f * sig).coerceIn(0f, 2f)
        val smooth = if (ss > 0.35f) ss else 0f
        val ys = if (smooth > 0f) gaussianBlur(y, w, h, smooth) else y
        val seff = if (smooth > 0f) sig / (1f + 3f * smooth) else sig

        val g = FloatArray(w * h)
        val gx = FloatArray(w * h); val gy = FloatArray(w * h)
        for (yy in 1 until h - 1) for (x in 1 until w - 1) {
            val i = yy * w + x
            val ax = (ys[i + 1] - ys[i - 1]) / 2f; val ay = (ys[i + w] - ys[i - w]) / 2f
            gx[i] = ax; gy[i] = ay; g[i] = hypot(ax, ay)
        }
        val sorted = FloatArray((w - 2) * (h - 2))
        var si = 0
        for (yy in 1 until h - 1) for (x in 1 until w - 1) sorted[si++] = g[yy * w + x]
        sorted.sort()
        val q = sorted[((sorted.size - 1) * 0.94).toInt()]
        val thr = max(q, 3f * max(seff, 0.5f))

        // points de contour : maxima locaux 3×3 du gradient, assez loin des bords
        val pts = ArrayList<Int>()
        for (yy in r + 3 until h - r - 3) for (x in r + 3 until w - r - 3) {
            val i = yy * w + x
            val v = g[i]
            if (v < thr) continue
            var isMax = true
            loop@ for (dy in -1..1) for (dx in -1..1) if (g[i + dy * w + dx] > v) { isMax = false; break@loop }
            if (isMax) pts.add(i)
        }
        if (pts.size < 40) return null
        val stride = max(1, pts.size / 25000)

        val offsN = 4 * r + 1
        val c = offsN / 2
        val prof = FloatArray(offsN)
        val binW = Array(nb) { ArrayList<Float>() }
        var total = 0
        var pi = 0
        while (pi < pts.size) {
            val i = pts[pi]; pi += stride
            val xx = i % w; val yy = i / w
            val gm = g[i]
            val nx = gx[i] / gm; val ny = gy[i] / gm
            for (j in 0 until offsN) {
                val off = (j - c) * 0.5f
                prof[j] = bilinear(ys, w, h, xx + nx * off, yy + ny * off)
            }
            val s = if (prof[c + 1] - prof[c - 1] >= 0f) 1f else -1f
            var hi = c
            while (hi < offsN - 2 && (prof[hi + 1] - prof[hi]) * s > -0.5f) hi++
            var lo = c - 1
            while (lo > 0 && (prof[lo + 1] - prof[lo]) * s > -0.5f) lo--
            val top = prof[hi]; val bot = prof[lo]
            val vmax = if (s > 0) top else bot
            val vmin = if (s > 0) bot else top
            val rng = vmax - vmin
            if (rng <= max(18f, 4f * seff)) continue
            var slope = 0f
            for (j in 0 until offsN - 1) slope = max(slope, abs(prof[j + 1] - prof[j]) / 0.5f)
            if (slope <= 1e-6f) continue
            val width = rng / slope
            if (!(width > 0f)) continue
            var phi = atan2(ny.toDouble(), nx.toDouble()) % PI
            if (phi < 0) phi += PI
            val b = min((phi / PI * nb).toInt(), nb - 1)
            binW[b].add(width); total++
        }
        if (total < 30) return null
        val ang = ArrayList<Double>(); val med = ArrayList<Float>(); val cnt = ArrayList<Int>()
        for (b in 0 until nb) {
            val l = binW[b]
            if (l.size < 5) continue
            val a = l.toFloatArray(); a.sort()
            ang.add((b + 0.5) * 180.0 / nb); med.add(a[a.size / 2]); cnt.add(a.size)
        }
        if (ang.size < 8) return null
        return EdgeStats(ang.toDoubleArray(), med.toFloatArray(), cnt.toIntArray(), smooth)
    }

    private class MotionFit(val w0: Double, val length: Double, val thetaDeg: Double)

    /** Ajuste largeur(φ)² = w0² + (L·cos(φ−θ))² par recherche sur grille puis affinage local. */
    private fun fitMotion(ang: DoubleArray, med: FloatArray, cnt: IntArray): MotionFit {
        val phi = DoubleArray(ang.size) { Math.toRadians(ang[it]) }
        fun cost(w0: Double, l: Double, th: Double): Double {
            var e = 0.0
            for (i in phi.indices) {
                val c = cos(phi[i] - th)
                val m = sqrt(w0 * w0 + l * l * c * c)
                val d = m - med[i]
                e += cnt[i] * d * d
            }
            return e
        }
        var bw = 1.0; var bl = 1.0; var bt = 0.0; var be = Double.MAX_VALUE
        var thD = 0.0
        while (thD < 180.0) {
            val th = Math.toRadians(thD)
            var l = 0.0
            while (l <= 70.0) {
                var w0 = 0.5
                while (w0 <= 6.0) {
                    val e = cost(w0, l, th)
                    if (e < be) { be = e; bw = w0; bl = l; bt = th }
                    w0 += 0.25
                }
                l += 1.0
            }
            thD += 3.0
        }
        // affinage
        var stepT = Math.toRadians(1.5); var stepL = 0.5; var stepW = 0.125
        repeat(6) {
            var improved = true
            while (improved) {
                improved = false
                for (dt in intArrayOf(-1, 0, 1)) for (dl in intArrayOf(-1, 0, 1)) for (dw in intArrayOf(-1, 0, 1)) {
                    if (dt == 0 && dl == 0 && dw == 0) continue
                    val t2 = bt + dt * stepT; val l2 = max(0.0, bl + dl * stepL); val w2 = max(0.3, bw + dw * stepW)
                    val e = cost(w2, l2, t2)
                    if (e < be - 1e-9) { be = e; bt = t2; bl = l2; bw = w2; improved = true }
                }
            }
            stepT /= 2; stepL /= 2; stepW /= 2
        }
        var deg = Math.toDegrees(bt) % 180.0
        if (deg < 0) deg += 180.0
        return MotionFit(bw, bl, deg)
    }

    // ------------------------------------------------------------------ analyse complète

    fun analyse(px: IntArray, w: Int, h: Int): Diagnosis {
        val y = luma(px)
        val sig = noiseSigma(y, w, h)
        val block = blockiness(y, w, h)

        // exposition
        val hist = IntArray(256)
        for (v in y) hist[(v + 0.5f).toInt().coerceIn(0, 255)]++
        fun pct(p: Double): Int {
            val target = (y.size * p).toInt()
            var a = 0
            for (i in 0..255) { a += hist[i]; if (a > target) return i }
            return 255
        }
        var white = 0; var black = 0
        for (i in 0..255) { if (i >= 252) white += hist[i] else if (i <= 3) black += hist[i] }

        // flou, sur une copie réduite (≤ 768 px) : les longueurs sont ramenées à la pleine résolution
        val f = ceil(max(w, h) / ANALYSIS_MAX.toDouble()).toInt().coerceAtLeast(1)
        val (yd, dw, dh) = boxDownscale(y, w, h, f)
        val edge = edgeWidths(yd, dw, dh, sig / f)

        var type = BlurType.NONE
        var mLen = 0f; var mAng = 0f; var iso = 0f
        var ratio = 1f; var wmedC = 0f
        if (edge != null) {
            val fit = fitMotion(edge.ang, edge.med, edge.cnt)
            val sortedMed = edge.med.sortedArray()
            val wmed = sortedMed[sortedMed.size / 2]
            ratio = edge.med.max() / max(edge.med.min(), 1e-3f)
            val corr = 2.5f * edge.smooth
            wmedC = sqrt(max(wmed * wmed - corr * corr, 0f))
            if (ratio >= 3.0f && fit.length >= 3.0) {
                type = BlurType.MOTION
                mLen = (max(0.0, 1.5 * fit.length - 0.8) * f).toFloat()   // calibration mesurée : L ≈ 1,5·L̂ − 0.8
                mAng = fit.thetaDeg.toFloat()
            } else if (ratio < 3.0f && wmedC >= 2.2f) {
                type = BlurType.ISOTROPIC
                iso = (sqrt(max(wmedC * wmedC - 1.44f, 0.1f)) / 2.6f) * f
            }
        }
        val ai = when {
            block > JPEG_THRESHOLD -> "compression JPEG marquée, l'IA peut reconstruire les détails abîmés"
            type == BlurType.ISOTROPIC && iso >= 2.0f -> "flou important, l'IA peut redessiner les détails"
            type == BlurType.MOTION && mLen >= 12f -> "flou de bougé important, l'IA peut redessiner les détails"
            sig > 12f -> "bruit fort"
            else -> null
        }
        return Diagnosis(
            width = w, height = h, noiseSigma = sig, blockiness = block,
            blurType = type, motionLength = mLen, motionAngleDeg = mAng, isoSigma = iso,
            edgeRatio = ratio, edgeWidth = wmedC,
            medianLuma = pct(0.5), p1 = pct(0.01), p99 = pct(0.99),
            clipWhite = white.toFloat() / y.size, clipBlack = black.toFloat() / y.size,
            aiReason = ai
        )
    }
}
