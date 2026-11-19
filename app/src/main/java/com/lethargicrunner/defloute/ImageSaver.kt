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

    /** Informations de la photo d'origine à conserver : date de prise de vue, lieu GPS, appareil. */
    class SourceMeta(val dateTakenMs: Long?, val exif: Map<String, String>)

    private val COPIED_TAGS = listOf(
        "DateTimeOriginal", "DateTimeDigitized", "DateTime", "OffsetTimeOriginal", "OffsetTime",
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp", "Make", "Model"
    )

    /** Lit les métadonnées de la photo d'origine (à faire au chargement : l'accès au fichier est alors valide). */
    fun readMeta(context: Context, uri: Uri): SourceMeta? = try {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val ei = android.media.ExifInterface(ins)
            val map = LinkedHashMap<String, String>()
            for (t in COPIED_TAGS) ei.getAttribute(t)?.let { map[t] = it }
            val taken = ExifUtil.parseExifDate(map["DateTimeOriginal"] ?: map["DateTimeDigitized"] ?: map["DateTime"])
            if (map.isEmpty() && taken == null) null else SourceMeta(taken, map)
        }
    } catch (_: Throwable) {
        null
    }

    /** Recopie les métadonnées dans le fichier JPEG enregistré (l'orientation n'est pas copiée : les pixels sont déjà redressés). */
    private fun writeExif(context: Context, uri: Uri, meta: SourceMeta?) {
        if (meta == null || meta.exif.isEmpty()) return
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val ei = android.media.ExifInterface(pfd.fileDescriptor)
                for ((k, v) in meta.exif) ei.setAttribute(k, v)
                ei.saveAttributes()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Enregistre le résultat dans le dossier choisi. [png] = PNG sans perte (plus lourd),
     * sinon JPEG qualité 95. Ne remplace jamais un fichier existant.
     */
    fun saveToTree(
        context: Context, treeUri: Uri, bitmap: Bitmap, baseName: String,
        png: Boolean = false, stamp: String = nowStamp(), meta: SourceMeta? = null
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
        if (!png) writeExif(context, target.uri, meta)
        return target.uri
    }

    /** Enregistrement direct dans la galerie (Pictures/Defloute), sans choisir de dossier. Android 10+. */
    val galleryAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun saveToGallery(
        context: Context, bitmap: Bitmap, baseName: String,
        png: Boolean = false, stamp: String = nowStamp(), meta: SourceMeta? = null
    ): Uri? {
        if (!galleryAvailable) return null
        val ext = if (png) "png" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName-defloute-$stamp.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Defloute")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            // la galerie trie par date de prise de vue : on garde celle de l'original
            meta?.dateTakenMs?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val ok = resolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, os)
            } ?: false
            if (!ok) { resolver.delete(uri, null, null); return null }
            if (!png) writeExif(context, uri, meta)
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
