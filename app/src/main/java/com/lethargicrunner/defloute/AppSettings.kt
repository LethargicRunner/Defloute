package com.lethargicrunner.defloute

import android.content.Context

/** Réglages de l'application (mémorisés sur le téléphone). */
object AppSettings {

    /** Que faire d'un traitement en cours quand l'app passe en arrière-plan ou que l'écran se met en veille. */
    enum class BgPolicy {
        /** Le traitement continue grâce à un service avec notification (batterie sollicitée). */
        CONTINUE,
        /** Mise en pause automatique, reprise au retour dans l'app. */
        PAUSE,
        /** Annulation automatique du traitement. */
        CANCEL,
        /** Rien : Android peut arrêter l'app à tout moment. */
        NOTHING
    }

    private const val FILE = "defloute_settings"
    private const val KEY_LOCK = "lock_rotation"
    private const val KEY_BG = "bg_policy"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lockRotation(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOCK, false)

    fun setLockRotation(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOCK, value).apply()
    }

    fun bgPolicy(ctx: Context): BgPolicy =
        try {
            BgPolicy.valueOf(prefs(ctx).getString(KEY_BG, null) ?: BgPolicy.PAUSE.name)
        } catch (_: IllegalArgumentException) {
            BgPolicy.PAUSE
        }

    fun setBgPolicy(ctx: Context, value: BgPolicy) {
        prefs(ctx).edit().putString(KEY_BG, value.name).apply()
    }
}
