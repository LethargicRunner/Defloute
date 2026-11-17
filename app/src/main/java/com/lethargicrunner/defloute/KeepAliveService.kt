package com.lethargicrunner.defloute

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service « garde-éveillé » : il ne fait aucun calcul lui-même. Tant qu'il tourne (avec sa notification),
 * Android ne tue pas l'application en arrière-plan et le processeur reste éveillé pendant le traitement IA
 * lancé par [DeblurViewModel]. Il est démarré au lancement du traitement et arrêté à sa fin.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val n = buildNotification(this, "Démarrage…", 0, 0, 0L)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "defloute:processing").apply {
                acquire(60 * 60 * 1000L) // garde-fou : 1 heure maximum
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "defloute_processing"
        private const val NOTIF_ID = 4201

        fun start(ctx: Context) {
            createChannel(ctx)
            ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }

        /** Met à jour la notification (progression et heure de fin estimée). */
        fun update(ctx: Context, done: Int, total: Int, etaAt: Long) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(ctx, "Tuile $done / $total", done, total, etaAt))
        }

        private fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Traitement des images", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun buildNotification(ctx: Context, text: String, done: Int, total: Int, etaAt: Long): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val eta = if (etaAt > 0L) " · fin ≈ " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(etaAt)) else ""
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Défloute — amélioration IA")
                .setContentText(text + eta)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setProgress(total, done, total == 0)
                .build()
        }
    }
}
