package com.lethargicrunner.defloute

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Scan récursif d'un dossier via SAF (comme en V1), décodage échantillonné max 512 px.
 * Retourne pour chaque image : nom, scores fin/grossier, verdict.
 */
object ImageScanner {

    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    data class ScanItem(
        val name: String,
        val uri: Uri,
        val fineScore: Double,
        val coarseScore: Double,
        val ratio: Double
    ) {
        fun isBlurry(s: BlurDetector.Settings): Boolean =
            fineScore < s.threshold || ratio > s.ratioThreshold
    }

    fun scanFolder(context: Context, treeUri: Uri, onProgress: (Int, Int) -> Unit): List<ScanItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val files = mutableListOf<DocumentFile>()
        collect(root, files)
        val items = mutableListOf<ScanItem>()
        files.forEachIndexed { index, f ->
            try {
                val bytes = context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val fine = ImageLoader.decodeFile(bytes, maxDim = 512)
                    val coarse = ImageLoader.decodeFile(bytes, maxDim = 128)
                    if (fine != null && coarse != null) {
                        val (fpx, fw) = ImageLoader.toPixels(fine)
                        val (cpx, cw) = ImageLoader.toPixels(coarse)
                        val fineV = BlurDetector.laplacianVariance(fpx, fw, fine.height)
                        val coarseV = BlurDetector.laplacianVariance(cpx, cw, coarse.height)
                        items.add(
                            ScanItem(
                                name = f.name ?: f.uri.toString(),
                                uri = f.uri,
                                fineScore = fineV,
                                coarseScore = coarseV,
                                ratio = if (fineV > 0) coarseV / fineV else 0.0
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // image illisible : on l'ignore
            }
            onProgress(index + 1, files.size)
        }
        return items
    }

    private fun collect(dir: DocumentFile, out: MutableList<DocumentFile>) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) collect(f, out)
            else {
                val ext = f.name?.substringAfterLast('.', "")?.lowercase() ?: ""
                if (ext in EXTENSIONS) out.add(f)
            }
        }
    }
}
