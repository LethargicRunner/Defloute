package com.lethargicrunner.defloute

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import java.io.InputStream

/** Décodage d'image avec EXIF + échantillonnage mémoire maîtrisé. */
object ImageLoader {

    data class LoadedImage(val bitmap: Bitmap, val pathHint: String?)

    /** Décode une image (Uri SAF) en respectant l'orientation EXIF. */
    fun decode(context: Context, uri: Uri, maxDim: Int = 2048): LoadedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null

        var sample = 1
        var dim = maxOf(bounds.outWidth, bounds.outHeight)
        while (dim / 2 >= maxDim) { sample *= 2; dim /= 2 }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val rotated = applyExifRotation(context, uri, raw)
        return LoadedImage(rotated, pathHint = uri.lastPathSegment)
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ExifInterface(ins).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /** Variante fichier directe (tests / dossier scanné). */
    fun decodeFile(bytes: ByteArray, maxDim: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        var dim = maxOf(bounds.outWidth, bounds.outHeight)
        while (dim / 2 >= maxDim) { sample *= 2; dim /= 2 }
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    fun toPixels(bmp: Bitmap): Pair<IntArray, Int> {
        val w = bmp.width
        val px = IntArray(w * bmp.height)
        bmp.getPixels(px, 0, w, 0, 0, w, bmp.height)
        return px to w
    }

    fun fromPixels(px: IntArray, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(px, 0, w, w, h, Bitmap.Config.ARGB_8888, false)

    fun closeStreamQuietly(s: InputStream?) { try { s?.close() } catch (_: Exception) {} }
}
