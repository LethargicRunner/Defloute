package com.lethargicrunner.defloute

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sauvegarde de l'image défloutée via SAF (jamais d'écriture sans choix de l'utilisateur). */
object ImageSaver {

    /**
     * Enregistre le résultat dans le dossier choisi. [png] = PNG sans perte (plus lourd),
     * sinon JPEG qualité 95. Ne remplace jamais un fichier existant.
     */
    fun saveToTree(
        context: Context, treeUri: Uri, bitmap: Bitmap, baseName: String,
        png: Boolean = false, stamp: String = nowStamp()
    ): Uri? {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val ext = if (png) "png" else "jpg"
        val mime = if (png) "image/png" else "image/jpeg"
        val cleanName = baseName
            .removeSuffix(".jpg").removeSuffix(".jpeg").removeSuffix(".png").removeSuffix(".webp")
        val name = "${cleanName}-defloute-$stamp.$ext"
        val existing = tree.findFile(name)
        if (existing != null) return existing.uri
        val target = tree.createFile(mime, name) ?: return null
        context.contentResolver.openOutputStream(target.uri)?.use { os ->
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, os)
        } ?: return null
        return target.uri
    }

    /** Enregistrement direct dans la galerie (Pictures/Defloute), sans choisir de dossier. Android 10+. */
    val galleryAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun saveToGallery(
        context: Context, bitmap: Bitmap, baseName: String,
        png: Boolean = false, stamp: String = nowStamp()
    ): Uri? {
        if (!galleryAvailable) return null
        val ext = if (png) "png" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName-defloute-$stamp.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Defloute")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val ok = resolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, os)
            } ?: false
            if (!ok) { resolver.delete(uri, null, null); return null }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Horodatage de nom de fichier (heure de lancement du traitement si fournie). */
    fun stampOf(millis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(if (millis > 0) millis else System.currentTimeMillis()))

    private fun nowStamp(): String = stampOf(0L)
}
