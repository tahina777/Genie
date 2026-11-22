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
import java.util.Locale

/**
 * Alertes FVG, BPR, POI, Inducement (pris) et CRT : une alerte quand un concept vient d'être confirmé
 * (sur les 2 dernières bougies terminées), une seule fois par concept. Dans le sens de la structure D1 + H4
 * quand « Suivre la structure » est actif. Maximum 3 alertes par marché et timeframe.
 */
object SmcConceptAlerts {

    fun check(ctx: Context, m: Market, tf: Tf, raw: List<Candle>) {
        if (raw.size < 100) return
        val c = raw.dropLast(1)
        val n = c.size
        val last = c.last()
        // Marché fermé : bougies trop anciennes
        val now = System.currentTimeMillis() / 1000
        if (now - last.time > tf.seconds * 4L) return
        val prefs = ctx.getSharedPreferences("genie", Context.MODE_PRIVATE)
        var sent = 0
        for (sc in SmcConcepts.detect(raw)) {
            if (sent >= 3) break
            if (sc.atIdx < n - 2 || sc.atIdx >= n) continue
            val dir = if (sc.buy) Direction.BUY else Direction.SELL
            if (!StructureFilter.allows(ctx, m, dir)) continue
            val key = "sc_" + m.code + "|" + tf.label + "|" + sc.kind + "|" + sc.buy
            val stamp = c[sc.atIdx].time.toString() + ":" + sc.label
            if (prefs.getString(key, null) == stamp) continue
            prefs.edit().putString(key, stamp).apply()
            notify(ctx, m, tf, sc, last.close, key)
            sent++
        }
    }

    private fun notify(ctx: Context, m: Market, tf: Tf, sc: SmcConcept, price: Double, key: String) {
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
        fun fmt(v: Double): String = String.format(Locale.US, "%." + m.decimals + "f", v)
        val title = sc.label + " \u00b7 " + m.name + " (" + tf.label + ")"
        val level = if (sc.high - sc.low > 0.0) fmt(sc.low) + " \u2013 " + fmt(sc.high) else fmt(sc.low)
        val text = "Niveau " + level + " \u00b7 prix " + fmt(price) + " \u00b7 " + (if (sc.buy) "haussier" else "baissier")
        val extra = when (sc.kind) {
            "CRT" -> "\nLa cible est l'autre extr\u00eame de la bougie range."
            "IDM" -> "\nLa liquidit\u00e9 vient d'\u00eatre prise : attends une confirmation dans le sens indiqu\u00e9."
            else -> "\nAttends le retour du prix sur la zone et une bougie de confirmation."
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + extra))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(key.hashCode(), n)
    }
}
