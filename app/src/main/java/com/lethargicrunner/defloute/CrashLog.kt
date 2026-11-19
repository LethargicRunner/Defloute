package com.lethargicrunner.defloute

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/**
 * Journal d'erreurs local : enregistre les plantages et les erreurs de traitement dans un fichier de
 * l'application, pour pouvoir les partager (au lieu de faire une capture d'écran du message).
 */
object CrashLog {
    private const val FILE = "defloute-log.txt"
    private const val PENDING = "crash-pending.flag"
    private const val MAX_FILE = 120_000
    private const val MAX_SHARE = 40_000
    @Volatile private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                append(app, LogFormat.entry("PLANTAGE (thread ${thread.name})", e, System.currentTimeMillis()))
                File(app.filesDir, PENDING).writeText("1")
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Enregistre une erreur gérée (sans plantage). */
    fun log(context: Context, title: String, e: Throwable? = null) {
        try { append(context, LogFormat.entry(title, e, System.currentTimeMillis())) } catch (_: Throwable) { }
    }

    private fun append(context: Context, text: String) {
        val f = File(context.filesDir, FILE)
        if (f.exists() && f.length() > MAX_FILE) f.writeText(LogFormat.tail(f.readText(), MAX_FILE / 2))
        f.appendText(text)
    }

    fun hasPendingCrash(context: Context): Boolean = File(context.filesDir, PENDING).exists()

    fun clearPending(context: Context) { File(context.filesDir, PENDING).delete() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        clearPending(context)
    }

    private fun header(context: Context): String {
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "?" }
        val rt = Runtime.getRuntime()
        return "Défloute v$version | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | " +
            "mémoire max ${rt.maxMemory() / (1024 * 1024)} Mo\n\n"
    }

    fun readLog(context: Context): String {
        val f = File(context.filesDir, FILE)
        val body = if (f.exists()) LogFormat.tail(f.readText(), MAX_SHARE) else ""
        return header(context) + body.ifBlank { "Aucune erreur enregistrée.\n" }
    }

    fun shareIntent(context: Context): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Journal d'erreurs Défloute")
            putExtra(Intent.EXTRA_TEXT, readLog(context))
        }
        return Intent.createChooser(send, "Partager le journal").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
