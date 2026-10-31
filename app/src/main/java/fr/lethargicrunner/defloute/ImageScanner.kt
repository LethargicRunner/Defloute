package fr.lethargicrunner.defloute

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Resultat d'analyse d'une image.
 */
data class ImageResult(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    /** Score de nettete a l'echelle fine (max ~512 px). Plus bas = plus flou. */
    val fineScore: Double,
    /** Score a l'echelle grossiere (max ~128 px). */
    val coarseScore: Double,
) {
    /** Decision multi-echelle : floue si score fin faible OU ratio grossier/fin eleve (flou + bruit). */
    val blurry: Boolean get() = BlurDetector.isBlurryMultiScale(fineScore, coarseScore)

    /** Score affiche : le score fin, conserve pour le tri et le debug. */
    val score: Double get() = fineScore
}

/**
 * Parcours recursif d'un dossier (SAF, lecture seule stricte) et detection du flou
 * multi-echelle (512 px / 128 px).
 */
object ImageScanner {

    private const val FINE_DIM = 512
    private const val COARSE_DIM = 128

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")

    fun isImage(file: DocumentFile): Boolean {
        val name = file.name?.lowercase() ?: return false
        val ext = name.substringAfterLast('.', "")
        return ext in IMAGE_EXTENSIONS
    }

    /** Parcours recursif du dossier et analyse de toutes les images. [onProgress] est appele apres chaque image. */
    suspend fun scan(context: Context, root: DocumentFile, onProgress: (analysed: Int, total: Int) -> Unit = { _, _ -> }): List<ImageResult> {
        val images = mutableListOf<DocumentFile>()
        collectImages(root, images)

        val results = mutableListOf<ImageResult>()
        images.forEachIndexed { index, file ->
            analyse(context, file)?.let { results.add(it) }
            onProgress(index + 1, images.size)
        }
        return results.sortedBy { it.fineScore } // les plus floues en premier
    }

    private fun collectImages(folder: DocumentFile, out: MutableList<DocumentFile>) {
        val files = folder.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                collectImages(f, out)
            } else if (f.isFile && isImage(f)) {
                out.add(f)
            }
        }
    }

    private fun sampleFor(width: Int, height: Int, targetDim: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > targetDim) sample *= 2
        return sample
    }

    private fun analyse(context: Context, file: DocumentFile): ImageResult? {
        // 1) Bornes sans decodage complet
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Exception) {
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2) Decodage a l'echelle fine (une seule lecture du flux)
        val fineBitmap = try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, FINE_DIM)
            }
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return null
        } catch (_: Exception) {
            return null
        } ?: return null

        // 3) Echelle grossiere derivee du bitmap fin (pas de relecture disque)
        val coarseBitmap = try {
            val scale = maxOf(fineBitmap.width, fineBitmap.height) / COARSE_DIM
            if (scale >= 2) {
                Bitmap.createScaledBitmap(
                    fineBitmap,
                    maxOf(fineBitmap.width / scale, 3),
                    maxOf(fineBitmap.height / scale, 3),
                    true
                )
            } else {
                fineBitmap
            }
        } catch (_: Exception) {
            null
        }

        // 4) Scores + decision multi-echelle
        val fineScore = BlurDetector.score(fineBitmap)
        val coarseScore = coarseBitmap?.let { BlurDetector.score(it) } ?: 0.0

        if (coarseBitmap != null && coarseBitmap !== fineBitmap) coarseBitmap.recycle()
        fineBitmap.recycle()

        return ImageResult(
            uri = file.uri,
            name = file.name ?: "?",
            sizeBytes = file.length(),
            fineScore = fineScore,
            coarseScore = coarseScore
        )
    }
}
