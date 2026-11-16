package com.lethargicrunner.defloute

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
    fun saveToTree(context: Context, treeUri: Uri, bitmap: Bitmap, baseName: String, png: Boolean = false): Uri? {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
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
}
