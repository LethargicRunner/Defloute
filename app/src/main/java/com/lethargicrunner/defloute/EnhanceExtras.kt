package com.lethargicrunner.defloute

/**
 * Passe finale « micro-contraste » : unsharp très doux à petit rayon, appliqué APRÈS
 * le défloutage. Redonne du croustillant aux micro-détails (texture, grain fin) sans
 * créer de halos visibles grâce au clamp doux déjà présent dans DeblurProcessor.unsharp.
 */
object EnhanceExtras {

    /** [amount] 0..1 (0.3 conseillé). */
    fun microContrast(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0f) return px.copyOf()
        val radius = 2
        val blurred = DeblurProcessor.boxBlur3(px, w, h, radius)
        return DeblurProcessor.unsharp(px, blurred, 0.45f * a)
    }
}
