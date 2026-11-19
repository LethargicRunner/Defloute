package com.lethargicrunner.defloute

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Date EXIF « 2024:05:17 14:03:09 » (heure locale de la prise de vue) <-> millisecondes. */
object ExifUtil {
    private fun fmt() = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { isLenient = false }

    fun parseExifDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try { fmt().parse(s.trim())?.time } catch (_: Exception) { null }
    }

    fun formatExifDate(ms: Long): String = fmt().format(Date(ms))
}

/** Mise en forme du journal d'erreurs (sans dépendance Android). */
object LogFormat {
    fun entry(title: String, e: Throwable?, nowMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs))
        val sb = StringBuilder()
        sb.append("=== ").append(stamp).append(" — ").append(title).append(" ===\n")
        if (e != null) {
            sb.append(e.javaClass.name).append(": ").append(e.message ?: "").append('\n')
            for (el in e.stackTrace.take(25)) sb.append("    at ").append(el).append('\n')
            var c = e.cause
            var depth = 0
            while (c != null && depth < 3) {
                sb.append("Caused by: ").append(c.javaClass.name).append(": ").append(c.message ?: "").append('\n')
                for (el in c.stackTrace.take(8)) sb.append("    at ").append(el).append('\n')
                c = c.cause; depth++
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /** Garde les [max] derniers caractères (le plus récent), en commençant à une ligne entière. */
    fun tail(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.substring(text.length - max)
        val nl = cut.indexOf('\n')
        return if (nl in 0 until cut.length - 1) cut.substring(nl + 1) else cut
    }
}

/** Règle de choix du moteur d'inférence : l'accélération n'est gardée que si elle est fidèle ET nettement plus rapide. */
object AccelPolicy {
    /**
     * @param meanDiff écart moyen absolu entre les sorties (échelle 0..1)
     * @param maxDiff écart maximal
     * @param tCpu durée d'une tuile sur le processeur (s)
     * @param tAcc durée d'une tuile accélérée, hors compilation (s)
     */
    fun accept(meanDiff: Double, maxDiff: Float, tCpu: Double, tAcc: Double): Boolean =
        meanDiff < 0.01 && maxDiff < 0.25f && tAcc < tCpu * 0.8
}

