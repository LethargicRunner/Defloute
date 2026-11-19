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

    /** Moteur utilisé par le dernier traitement : « CPU » ou « NNAPI » (accélération matérielle vérifiée). */
    @Volatile
    var backend: String = "CPU"
        private set

    private const val GUARD_FILE = "accel-trying.flag"

    private class TileInput(val ix0: Int, val iy0: Int, val iw: Int, val ih: Int, val data: FloatArray)

    private fun newOptions(threads: Int) = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    /**
     * Agrandit [px] (ARGB, w x h) x4. [onTile] reçoit (tuile faite, total).
     * [checkCancelled] doit lever une exception d'annulation si l'utilisateur a annulé.
     *
     * Moteur : processeur par défaut. Si l'accélération matérielle est autorisée, la première tuile est
     * calculée sur les deux moteurs : on ne garde l'accélération que si le résultat est quasi identique
     * ET plus rapide. Un fichier témoin détecte un plantage natif de l'essai et désactive alors l'option.
     */
    suspend fun upscale(
        context: Context,
        px: IntArray,
        w: Int,
        h: Int,
        onTile: (Int, Int) -> Unit,
        checkCancelled: suspend () -> Unit
    ): Bitmap {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val cpuOpts = newOptions(threads)
        var accOpts: OrtSession.SessionOptions? = null
        var cpu: OrtSession? = null
        var acc: OrtSession? = null
        val guard = java.io.File(context.filesDir, GUARD_FILE)
        val out = Bitmap.createBitmap(w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)
        try {
            cpu = env.createSession(modelBytes, cpuOpts)
            var active: OrtSession = cpu
            backend = "CPU"

            if (AppSettings.aiAccel(context)) {
                if (guard.exists()) {
                    // l'essai précédent n'est pas allé au bout (plantage natif ou arrêt forcé) : on désactive l'option
                    AppSettings.setAiAccel(context, false)
                    guard.delete()
                    CrashLog.log(context, "Accélération matérielle désactivée : l'essai précédent ne s'est pas terminé")
                } else {
                    try {
                        guard.writeText("1")
                        accOpts = newOptions(threads).apply { addNnapi() }
                        acc = env.createSession(modelBytes, accOpts)
                        val verdict = pickBackend(env, cpu, acc, px, w, h)
                        CrashLog.log(context, "Accélération matérielle : $verdict")
                        if (verdict.startsWith("retenue")) { active = acc; backend = "NNAPI" }
                    } catch (e: Throwable) {
                        CrashLog.log(context, "Accélération matérielle indisponible", e)
                    }
                    if (backend == "CPU") guard.delete()   // sinon le témoin reste jusqu'à la fin du traitement
                }
            }

            val total = tileCount(w, h)
            var done = 0
            var ty = 0
            while (ty < h) {
                val y1 = minOf(h, ty + TILE)
                var tx = 0
                while (tx < w) {
                    checkCancelled()
                    val x1 = minOf(w, tx + TILE)
                    val inp = prepareInput(px, w, h, tx, ty, x1, y1)
                    writeTile(infer(env, active, inp), inp, tx, ty, x1, y1, out)
                    done++
                    onTile(done, total)
                    tx += TILE
                }
                ty += TILE
            }
        } finally {
            guard.delete()
            acc?.close(); accOpts?.close(); cpu?.close(); cpuOpts.close()
        }
        return out
    }

    /** Compare les deux moteurs sur la première tuile. Renvoie un verdict commençant par « retenue » ou « refusée ». */
    private fun pickBackend(env: OrtEnvironment, cpu: OrtSession, acc: OrtSession, px: IntArray, w: Int, h: Int): String {
        val inp = prepareInput(px, w, h, 0, 0, minOf(w, TILE), minOf(h, TILE))
        val t0 = System.nanoTime()
        val a = infer(env, cpu, inp)
        val tCpu = (System.nanoTime() - t0) / 1e9
        infer(env, acc, inp)                       // premier passage : compilation du graphe, non chronométré
        val t1 = System.nanoTime()
        val b = infer(env, acc, inp)
        val tAcc = (System.nanoTime() - t1) / 1e9
        var sum = 0.0
        var mx = 0f
        for (i in a.indices) {
            val d = Math.abs(a[i] - b[i])
            sum += d
            if (d > mx) mx = d
        }
        val mean = sum / a.size
        val info = "CPU %.2f s, NNAPI %.2f s par tuile, écart moyen %.4f, max %.3f".format(tCpu, tAcc, mean, mx)
        return if (AccelPolicy.accept(mean, mx, tCpu, tAcc)) "retenue ($info)" else "refusée ($info)"
    }

    private fun prepareInput(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): TileInput {
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
        return TileInput(ix0, iy0, iw, ih, input)
    }

    private fun infer(env: OrtEnvironment, session: OrtSession, inp: TileInput): FloatArray {
        val ow = inp.iw * SCALE
        val oh = inp.ih * SCALE
        return OnnxTensor.createTensor(
            env, FloatBuffer.wrap(inp.data), longArrayOf(1, 3, inp.ih.toLong(), inp.iw.toLong())
        ).use { tensor ->
            session.run(mapOf("input" to tensor)).use { res ->
                val outTensor = res.get(0) as OnnxTensor
                val arr = FloatArray(3 * ow * oh)
                outTensor.floatBuffer.get(arr)
                arr
            }
        }
    }

    private fun writeTile(
        result: FloatArray, inp: TileInput,
        x0: Int, y0: Int, x1: Int, y1: Int,
        out: Bitmap
    ) {
        val ix0 = inp.ix0; val iy0 = inp.iy0
        val ow = inp.iw * SCALE
        val oh = inp.ih * SCALE
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
