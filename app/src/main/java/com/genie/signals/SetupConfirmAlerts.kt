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
 * Confirmation validée : pour un setup de la fiche (QM, SNRC, Hybrid, RBR/DBD/RBD/DBR, Blindspot, CK) encore en attente
 * ou déclenché, Genie regarde le timeframe juste au-dessus (M5 > M15, M15 > H1, H1 > H4, H4 > D1) : si la dernière bougie
 * terminée touche la zone du setup et forme une confirmation (englobante dans le sens du setup, marteau / pin bar avec
 * la mèche du bon côté, ou doji), la confirmation est validée et une alerte part.
 * Une alerte par setup et par bougie de confirmation. Respecte « Suivre la structure ».
 */
object SetupConfirmAlerts {

    private fun higher(tf: Tf): Tf? = when (tf) {
        Tf.M5 -> Tf.M15
        Tf.M15 -> Tf.H1
        Tf.H1 -> Tf.H4
        Tf.H4 -> Tf.D1
        Tf.D1 -> null
    }

    private fun engulfs(prev: Candle, cur: Candle, buy: Boolean): Boolean =
        if (buy) {
            prev.close < prev.open && cur.close > cur.open && cur.close > prev.open && cur.open <= prev.close
        } else {
            prev.close > prev.open && cur.close < cur.open && cur.close < prev.open && cur.open >= prev.close
        }

    /** Nom de la confirmation formée par `cur` (englobante, marteau / étoile filante, doji) ou null. */
    internal fun confirmation(prev: Candle, cur: Candle, buy: Boolean, atr: Double): String? {
        val body = kotlin.math.abs(cur.close - cur.open)
        val range = cur.high - cur.low
        if (range < 0.5 * atr) return null
        if (engulfs(prev, cur, buy)) return if (buy) "Englobante haussi\u00e8re" else "Englobante baissi\u00e8re"
        val upper = cur.high - kotlin.math.max(cur.open, cur.close)
        val lower = kotlin.math.min(cur.open, cur.close) - cur.low
        if (buy && lower >= 2 * body && lower >= 0.6 * range) return "Marteau (pin bar haussi\u00e8re)"
        if (!buy && upper >= 2 * body && upper >= 0.6 * range) return "\u00c9toile filante (pin bar baissi\u00e8re)"
        if (body <= 0.1 * range) return "Doji"
        return null
    }

    suspend fun check(ctx: Context, m: Market, tf: Tf) {
        val htf = higher(tf) ?: return
        val items = SetupAlerts.recent[m.code + "|" + tf.label] ?: return
        val active = items.filter { it.status == QmStatus.PENDING || it.status == QmStatus.TRIGGERED }
        if (active.isEmpty()) return
        val raw = Deriv.candles(m.code, htf.seconds, 100)
        if (raw.size < 30) return
        val c = raw.dropLast(1)
        val n = c.size
        val cur = c[n - 1]
        val prev = c[n - 2]
        val nowSec = System.currentTimeMillis() / 1000L
        if (nowSec - cur.time > htf.seconds * 3L) return // marché fermé
        val atr = Ind.atr(c)
        if (atr <= 0.0) return
        val tol = 0.5 * atr
        val prefs = ctx.getSharedPreferences("genie", Context.MODE_PRIVATE)
        var sent = 0
        for (s in active) {
            if (sent >= 3) break
            val buy = s.dir == QmDirection.BUY
            if (cur.low > s.zoneHigh + tol || cur.high < s.zoneLow - tol) continue
            val pat = confirmation(prev, cur, buy, atr) ?: continue
            val d = if (buy) Direction.BUY else Direction.SELL
            if (!StructureFilter.allows(ctx, m, d)) continue
            val key = "scf_" + m.code + "|" + tf.label + "|" + s.kind + "|" + s.dir.name
            val stamp = cur.time.toString()
            if (prefs.getString(key, null) == stamp) continue
            prefs.edit().putString(key, stamp).apply()
            notify(ctx, m, tf, htf, s, pat, key)
            sent++
        }
    }

    private fun notify(ctx: Context, m: Market, tf: Tf, htf: Tf, s: SetupAlerts.Item, pat: String, key: String) {
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
        val buy = s.dir == QmDirection.BUY
        val title = "Confirmation valid\u00e9e \u00b7 " + s.kind + (if (buy) " ACHAT" else " VENTE") + " \u00b7 " + m.name + " (" + tf.label + ")"
        val text = pat + " " + htf.label + " sur la zone " + fmt(s.zoneLow) + " \u2013 " + fmt(s.zoneHigh) +
            " \u00b7 entr\u00e9e " + fmt(s.entry) + " \u00b7 SL " + fmt(s.sl) + " \u00b7 TP1 " + fmt(s.tp1)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(key.hashCode(), n)
    }
}
