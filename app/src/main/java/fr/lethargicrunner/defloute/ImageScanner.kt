package fr.lethargicrunner.defloute

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Resultat d'analyse d'une image. Seuils NON figes ici : la decision se prend
 * dans l'UI avec les seuils courants (reglables en direct, sans re-scan).
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
    /** Ratio grossier/fin, affiche et sert au 2e critere. */
    val ratio: Double get() = if (fineScore > 0) coarseScore / fineScore else Double.POSITIVE_INFINITY

    fun isBlurry(fineThreshold: Double, ratioThreshold: Double): Boolean =
        BlurDetector.isBlurryMultiScale(fineScore, coarseScore, fineThreshold, ratioThreshold)
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

    suspend fun scan(context: Context, root: DocumentFile, onProgress: (analysed: Int, total: Int) -> Unit = { _, _ -> }): List<ImageResult> {
        val images = mutableListOf<DocumentFile>()
        collectImages(root, images)

        val results = mutableListOf<ImageResult>()
        images.forEachIndexed { index, file ->
            analyse(context, file)?.let { results.add(it) }
            onProgress(index + 1, images.size)
        }
        return results.sortedBy { it.fineScore }
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Exception) {
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

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
