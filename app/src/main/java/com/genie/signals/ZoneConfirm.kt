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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Confirmation d'une zone : à la clôture d'une bougie, on regarde si elle a touché une zone surveillée
 * et si elle forme un Engulfing ou une Pin bar dans le sens de la tendance (mêmes règles que sur le graphique).
 * Une seule confirmation par marché, timeframe et bougie.
 */
object ZoneConfirm {

    class Hit(val zone: WatchZone, val pattern: String, val candle: Candle)

    /** Renvoie la confirmation de la dernière bougie terminée, ou null. `zones` = zones de ce marché et de ce timeframe. */
    fun check(m: Market, tf: Tf, raw: List<Candle>, zones: List<WatchZone>): Hit? {
        if (zones.isEmpty() || raw.size < 30) return null
        val c = raw.dropLast(1) // la dernière bougie est encore en formation
        val n = c.size
        val cur = c[n - 1]
        val prev = c[n - 2]
        val six = c[n - 7]

        // La bougie doit venir de se fermer (pas d'alerte ancienne après un redémarrage)
        val nowSec = System.currentTimeMillis() / 1000L
        val closeSec = cur.time + tf.seconds
        if (nowSec - closeSec > 30 * 60L || nowSec < closeSec - 60L) return null

        val atr = Ind.atr(c)
        if (atr <= 0.0) return null

        val body = abs(cur.close - cur.open)
        val range = cur.high - cur.low
        val pBody = abs(prev.close - prev.open)
        val upper = cur.high - max(cur.open, cur.close)
        val lower = min(cur.open, cur.close) - cur.low
        val down = prev.close < six.close
        val up = prev.close > six.close

        val bullEng = body >= 0.3 * atr && body > pBody && down && prev.close < prev.open &&
            cur.close > cur.open && cur.open <= prev.close && cur.close >= prev.open
        val bearEng = body >= 0.3 * atr && body > pBody && up && prev.close > prev.open &&
            cur.close < cur.open && cur.open >= prev.close && cur.close <= prev.open
        val hammer = range >= 0.5 * atr && down && lower >= 2 * body && lower >= 0.6 * range
        val shooting = range >= 0.5 * atr && up && upper >= 2 * body && upper >= 0.6 * range

        for (z in zones) {
            // la bougie doit avoir touché la zone, et la zone ne doit pas avoir été traversée en clôture
            if (cur.low > z.high || cur.high < z.low) continue
            if (z.dir == Direction.BUY) {
                if (cur.close < z.low) continue
                if (bullEng) return Hit(z, "Engulfing haussier", cur)
                if (hammer) return Hit(z, "Pin bar haussière (marteau)", cur)
            } else {
                if (cur.close > z.high) continue
                if (bearEng) return Hit(z, "Engulfing baissier", cur)
                if (shooting) return Hit(z, "Pin bar baissière (étoile filante)", cur)
            }
        }
        return null
    }

    /** Une seule confirmation par marché, timeframe et bougie (mémorisé, même après un redémarrage). */
    fun alreadySent(ctx: Context, m: Market, tf: Tf, candleTime: Long): Boolean {
        val prefs = ctx.getSharedPreferences("genie", Context.MODE_PRIVATE)
        val key = "cf_" + m.code + "|" + tf.label
        if (prefs.getLong(key, 0L) >= candleTime) return true
        prefs.edit().putLong(key, candleTime).apply()
        return false
    }

    fun notify(ctx: Context, m: Market, tf: Tf, hit: Hit) {
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
        val z = hit.zone
        val buy = z.dir == Direction.BUY
        val title = "Confirmation " + (if (buy) "ACHAT" else "VENTE") + " · " + m.name + " (" + tf.label + ")"
        val text = hit.pattern + " sur " + z.kind + " " + fmt(z.low) + " – " + fmt(z.high) +
            " · clôture " + fmt(hit.candle.close)
        val big = text +
            "\nTendance " + (if (buy) "haussière" else "baissière") + ". " +
            "Stop possible " + (if (buy) "sous " + fmt(min(z.low, hit.candle.low)) else "au-dessus de " + fmt(max(z.high, hit.candle.high))) +
            ".\nRegarde le graphique avant d'entrer."
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
        NotificationManagerCompat.from(ctx).notify(("cf|" + m.code + "|" + tf.label).hashCode(), n)
    }
}
