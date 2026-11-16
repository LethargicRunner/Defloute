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

    fun saveToTree(context: Context, treeUri: Uri, bitmap: Bitmap, baseName: String): Uri? {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "${baseName.removeSuffix(".jpg").removeSuffix(".jpeg").removeSuffix(".png")}-defloute-$stamp.jpg"
        val mime = "image/jpeg"
        val existing = tree.findFile(name)
        if (existing != null) return existing.uri
        val target = tree.createFile(mime, name) ?: return null
        context.contentResolver.openOutputStream(target.uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
        } ?: return null
        return target.uri
    }
}
