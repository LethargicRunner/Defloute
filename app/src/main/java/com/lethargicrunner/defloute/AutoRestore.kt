package com.lethargicrunner.defloute

import com.lethargicrunner.defloute.PhotoDiagnosis.BlurType
import com.lethargicrunner.defloute.PhotoDiagnosis.Diagnosis
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Restauration automatique : diagnostic -> plan -> traitement, sans réglage manuel.
 *
 * Ordre : (1) déconvolution du flou mesuré (luminance), (2) débruitage adapté au bruit estimé,
 * (3) rééquilibrage d'exposition et de contraste seulement si le diagnostic le justifie.
 * Garde-fous mesurés sur le banc d'essai :
 * - rien n'est fait si aucun défaut n'est détecté ;
 * - la déconvolution est désactivée si la compression JPEG est marquée (elle amplifierait les blocs).
 */
object AutoRestore {

    /** Paramètres réglés sur le banc d'essai (exposés pour pouvoir les recalibrer). */
    data class Tuning(
        val nsrBase: Float = 0.012f,
        val nsrNoise: Float = 0.0015f,
        val kernelShrink: Float = 0.9f,
        val nlmSigmaK: Float = 1.0f,
        val nlmStrength: Float = 0.9f,
        val nlmMinSigma: Float = 2.5f,
        val jpegSigma: Float = 8f,
        val toneTarget: Float = 118f,
        /** Médiane de luminance « confortable » : en dehors, on corrige l'exposition. */
        val toneLow: Float = 55f,
        val toneHigh: Float = 175f,
        /** Part du chemin vers la cible (0..1) : on corrige sans dénaturer une scène sombre voulue. */
        val toneStrength: Float = 0.9f,
        /** Étirement du contraste seulement si l'étendue p1..p99 est inférieure à ce seuil. */
        val stretchBelow: Float = 115f
    )

    class Result(val pixels: IntArray, val diagnosis: Diagnosis, val steps: List<String>)

    private const val NLM_MAX_PIXELS = 1_100_000

    fun run(
        px: IntArray, w: Int, h: Int,
        diag: Diagnosis = PhotoDiagnosis.analyse(px, w, h),
        tuning: Tuning = Tuning(),
        onStage: (String) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): Result {
        val steps = ArrayList<String>()
        var cur = px

        // 1) déconvolution
        val deconvOk = diag.blurType != BlurType.NONE && !diag.jpegMarked
        if (deconvOk) {
            onStage("Correction du flou…")
            val kernel = when (diag.blurType) {
                BlurType.MOTION -> BlurKernels.motion(max(diag.motionLength * tuning.kernelShrink, 1.5f), Math.toRadians(diag.motionAngleDeg.toDouble()))
                else -> BlurKernels.isotropicMix(max(diag.isoSigma * tuning.kernelShrink, 0.5f))
            }
            val nsr = tuning.nsrBase + tuning.nsrNoise * diag.noiseSigma * diag.noiseSigma
            cur = deconvolveLuma(cur, w, h, kernel, nsr)
            steps += if (diag.blurType == BlurType.MOTION) "Flou de bougé corrigé" else "Flou de mise au point corrigé"
        } else if (diag.blurType != BlurType.NONE && diag.jpegMarked) {
            steps += "Défloutage évité : compression JPEG marquée (il amplifierait les blocs)"
        }
        checkCancelled()

        // 2) débruitage / déblocage
        val sEff = max(diag.noiseSigma, tuning.jpegSigma * max(diag.blockiness - 1.1f, 0f))
        if (sEff >= tuning.nlmMinSigma) {
            onStage("Réduction du bruit…")
            cur = if (w.toLong() * h <= NLM_MAX_PIXELS)
                NlmDenoise.denoise(cur, w, h, strength = tuning.nlmStrength, searchR = 5, patchR = 1, sigma = tuning.nlmSigmaK * sEff)
            else
                ImageEnhancer.denoise(cur, w, h, (sEff / 14f).coerceIn(0.2f, 1f))
            steps += "Bruit réduit (≈ ${"%.1f".format(sEff)})"
        }
        checkCancelled()

        // 3) exposition / contraste
        val toned = toneCorrect(cur, diag, tuning)
        if (toned != null) { cur = toned.first; steps += toned.second }

        return Result(cur, diag, steps)
    }

    /** Déconvolue la luminance et rajoute l'écart à chaque canal (la couleur est conservée). */
    fun deconvolveLuma(px: IntArray, w: Int, h: Int, kernel: Kernel, nsr: Float): IntArray {
        val y = PhotoDiagnosis.luma(px)
        val sharp = Deconvolution.wiener(y, w, h, kernel, nsr)
        val out = IntArray(px.size)
        for (i in px.indices) {
            val d = sharp[i] - y[i]
            val p = px[i]
            val r = (((p shr 16) and 0xFF) + d + 0.5f).toInt().coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + d + 0.5f).toInt().coerceIn(0, 255)
            val b = ((p and 0xFF) + d + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /**
     * Exposition et contraste : gamma si la médiane est hors de [85, 170], étirement si le contraste
     * est faible. Appliqué comme un gain de luminance (la teinte est conservée).
     */
    fun toneCorrect(px: IntArray, d: Diagnosis, t: Tuning): Pair<IntArray, String>? {
        if (t.toneTarget <= 0f) return null
        val m = d.medianLuma.coerceIn(1, 254)
        val needGamma = m < t.toneLow || m > t.toneHigh
        // étendue des tons après correction de gamma, pour décider de l'étirement
        val gammaFor = { med: Float ->
            val goal = m + t.toneStrength * (t.toneTarget - m)
            (ln(goal / 255f) / ln(med.coerceIn(0.02f, 0.98f))).coerceIn(0.55f, 1.8f)
        }
        val gamma0 = if (needGamma) gammaFor(m / 255f) else 1f
        val mapped = { v: Int -> 255f * (v / 255f).pow(gamma0) }
        val rangeAfter = mapped(d.p99) - mapped(d.p1)
        val needStretch = rangeAfter < t.stretchBelow
        if (!needGamma && !needStretch) return null
        val lut = FloatArray(256)
        val lo = if (needStretch) mapped(d.p1) else 0f
        val hi = if (needStretch) max(mapped(d.p99), lo + 40f) else 255f
        for (v in 0..255) {
            val g = mapped(v)
            lut[v] = 255f * ((g - lo) / (hi - lo)).coerceIn(0f, 1f)
        }
        val gamma = gamma0
        val out = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val y = (0.299f * r + 0.587f * g + 0.114f * b + 0.5f).toInt().coerceIn(0, 255)
            val ny = lut[y]
            val gain = if (y == 0) 1f else min(4f, ny / y)
            val nr = (r * gain + 0.5f).toInt().coerceIn(0, 255)
            val ng = (g * gain + 0.5f).toInt().coerceIn(0, 255)
            val nb = (b * gain + 0.5f).toInt().coerceIn(0, 255)
            // pixels noirs : on ajoute directement la valeur cible
            out[i] = if (y == 0) (0xFF shl 24) or (ny.toInt().coerceIn(0, 255) * 0x010101) else (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        val what = buildString {
            if (needGamma) append(if (gamma < 1f) "Photo éclaircie" else "Photo assombrie")
            if (needStretch) { if (isNotEmpty()) append(" + "); append("contraste rétabli") }
        }
        return Pair(out, what)
    }
}
