package com.genie.signals

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Alerte d'alignement de la structure de marché : prévient quand D1, H4 et H1 deviennent tous haussiers
 * ou tous baissiers (MarketStructure.kt). Une alerte par changement d'état ; au tout premier passage
 * l'état est seulement mémorisé (pas de rafale de notifications au démarrage).
 */
object StructureAlerts {

    fun check(ctx: Context, m: Market, d1: StructureState, h4: StructureState, h1: StructureState) {
        val al = MarketStructure.alignment(listOf(d1, h4, h1))
        val prefs = ctx.getSharedPreferences("genie", Context.MODE_PRIVATE)
        val key = "st_" + m.code
        val old = prefs.getString(key, null)
        val now = al.toString()
        if (old == now) return
        prefs.edit().putString(key, now).apply()
        if (old == null || al == 0) return
        notify(ctx, m, al, key)
    }

    private fun notify(ctx: Context, m: Market, al: Int, key: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val channelId = "genie_zones"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(channelId, "Zones Genie", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
        val up = al > 0
        val title = "Structure alignée · " + m.name
        val text = "D1, H4 et H1 sont tous " + (if (up) "haussiers (HH + HL)" else "baissiers (LH + LL)")
        val big = text + "\nCherche une entrée dans le sens " + (if (up) "de l'achat" else "de la vente") +
            " sur H1 (zone, re-entry ou setup)."
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(key.hashCode(), n)
    }
}
