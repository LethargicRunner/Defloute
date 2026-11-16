package com.lethargicrunner.defloute

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * Super-résolution x4 avec Real-ESRGAN (modèle realesr-general-x4v3) via ONNX Runtime, 100 % sur le téléphone.
 *
 * L'image est découpée en tuiles (avec marge de recouvrement pour éviter les coutures) afin de
 * borner la mémoire, puis chaque tuile agrandie est recopiée directement dans le bitmap final
 * (pas de gros tableau intermédiaire).
 *
 * Le fichier modèle est généré par le workflow GitHub (tools/make_esrgan_onnx.py) dans les assets.
 */
object EsrganUpscaler {

    const val MODEL_ASSET = "realesr-general-x4v3.onnx"
    const val SCALE = 4

    /** Plus grand côté conseillé en entrée : 1024 px -> sortie 4096 px max. */
    const val MAX_INPUT_DIM = 1024

    private const val TILE = 128   // zone utile d'une tuile (entrée)
    private const val PAD = 10     // marge de contexte (rognée en sortie)

    fun isAvailable(context: Context): Boolean = try {
        context.assets.open(MODEL_ASSET).close()
        true
    } catch (_: Exception) {
        false
    }

    /** Nombre de tuiles pour une image w x h (pour afficher la progression). */
    fun tileCount(w: Int, h: Int): Int = ((w + TILE - 1) / TILE) * ((h + TILE - 1) / TILE)

    /**
     * Agrandit [px] (ARGB, w x h) x4. [onTile] reçoit (tuile faite, total).
     * [checkCancelled] doit lever une exception d'annulation si l'utilisateur a annulé.
     */
    fun upscale(
        context: Context,
        px: IntArray,
        w: Int,
        h: Int,
        onTile: (Int, Int) -> Unit,
        checkCancelled: () -> Unit
    ): Bitmap {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        val out = Bitmap.createBitmap(w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)
        env.createSession(modelBytes, opts).use { session ->
            val total = tileCount(w, h)
            var done = 0
            var ty = 0
            while (ty < h) {
                val y1 = minOf(h, ty + TILE)
                var tx = 0
                while (tx < w) {
                    checkCancelled()
                    val x1 = minOf(w, tx + TILE)
                    processTile(env, session, px, w, h, tx, ty, x1, y1, out)
                    done++
                    onTile(done, total)
                    tx += TILE
                }
                ty += TILE
            }
        }
        opts.close()
        return out
    }

    private fun processTile(
        env: OrtEnvironment, session: OrtSession,
        px: IntArray, w: Int, h: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
        out: Bitmap
    ) {
        // zone d'entrée = zone utile + marge (bornée à l'image)
        val ix0 = maxOf(0, x0 - PAD); val iy0 = maxOf(0, y0 - PAD)
        val ix1 = minOf(w, x1 + PAD); val iy1 = minOf(h, y1 + PAD)
        val iw = ix1 - ix0
        val ih = iy1 - iy0

        // ARGB -> tenseur CHW float 0..1
        val plane = iw * ih
        val input = FloatArray(3 * plane)
        for (y in 0 until ih) {
            val row = (iy0 + y) * w + ix0
            for (x in 0 until iw) {
                val p = px[row + x]
                val i = y * iw + x
                input[i] = ((p shr 16) and 0xFF) / 255f
                input[plane + i] = ((p shr 8) and 0xFF) / 255f
                input[2 * plane + i] = (p and 0xFF) / 255f
            }
        }

        val ow = iw * SCALE
        val oh = ih * SCALE
        val result = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, 3, ih.toLong(), iw.toLong())
        ).use { tensor ->
            session.run(mapOf("input" to tensor)).use { res ->
                val outTensor = res.get(0) as OnnxTensor
                val arr = FloatArray(3 * ow * oh)
                outTensor.floatBuffer.get(arr)
                arr
            }
        }

        // rogne la marge, repasse en ARGB et copie dans le bitmap final
        val cx0 = (x0 - ix0) * SCALE
        val cy0 = (y0 - iy0) * SCALE
        val cw = (x1 - x0) * SCALE
        val ch = (y1 - y0) * SCALE
        val oplane = ow * oh
        val line = IntArray(cw * ch)
        for (y in 0 until ch) {
            val srow = (cy0 + y) * ow + cx0
            for (x in 0 until cw) {
                val s = srow + x
                val r = (result[s] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (result[oplane + s] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (result[2 * oplane + s] * 255f + 0.5f).toInt().coerceIn(0, 255)
                line[y * cw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        out.setPixels(line, 0, cw, x0 * SCALE, y0 * SCALE, cw, ch)
    }
}
