package fr.lethargicrunner.defloute

import android.content.Context
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
    /** Score de nettete (variance du Laplacien). Plus bas = plus flou. */
    val score: Double,
) {
    val blurry: Boolean get() = BlurDetector.isBlurry(score)
}

/**
 * Parcours recursif d'un dossier (SAF, lecture seule stricte) et detection du flou.
 */
object ImageScanner {

    /** Cote max de decodage : suffisant pour le score, evite d'exploser la memoire. */
    private const val MAX_DIM = 512

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
        return results.sortedBy { it.score } // les plus floues en premier
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

    private fun analyse(context: Context, file: DocumentFile): ImageResult? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Exception) {
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }

        val score = try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)?.let { bmp ->
                    val s = BlurDetector.score(bmp)
                    bmp.recycle()
                    s
                }
            } ?: return null
        } catch (_: Exception) {
            return null
        }

        return ImageResult(uri = file.uri, name = file.name ?: "?", sizeBytes = file.length(), score = score)
    }
}
