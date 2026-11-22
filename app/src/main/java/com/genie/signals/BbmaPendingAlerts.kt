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
 * Alerte "Re-entry en attente" : après un Extrême, le prix revient vers la MA5/10, la bande médiane ou la zone ZZL
 * sans les avoir touchées. Une alerte par Extrême et par type (mémorisé dans les préférences).
 * Quand le prix touche ensuite la zone, l'alerte de zone habituelle part (avec l'état du CSAK).
 */
object BbmaPendingAlerts {

    fun check(ctx: Context, m: Market, tf: Tf, raw: List<Candle>) {
        if (raw.size < 100) return
        val c = raw.dropLast(1)
        val last = c.last()
        // Marché fermé : bougies trop anciennes
        val now = System.currentTimeMillis() / 1000
        if (now - last.time > tf.seconds * 4L) return
        val b = BbmaCalc(c)
        val p = b.pending() ?: return
        val prefs = ctx.getSharedPreferences("genie", Context.MODE_PRIVATE)
        val key = "bp_" + m.code + "|" + tf.label
        val stamp = c[p.extremeIdx].time.toString() + ":" + p.kind + ":" + p.dir.name
        if (prefs.getString(key, null) == stamp) return
        prefs.edit().putString(key, stamp).apply()
        val mk = b.marks()
        val csak = if (mk.candle == "CSAK" && mk.candleDir == p.dir) "CSAK trouvé" else "CSAK pas encore trouvé"
        notify(ctx, m, tf, p, last.close, csak, key)
    }

    private fun notify(ctx: Context, m: Market, tf: Tf, p: BbmaPending, price: Double, csak: String, key: String) {
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
        val buy = p.dir == Direction.BUY
        val title = bbmaPendingName(p.kind) + " en attente · " + m.name + " (" + tf.label + ")"
        val text = (if (buy) "Achat" else "Vente") + " · niveau " + fmt(p.low) +
            (if (p.high - p.low > 0.0) " – " + fmt(p.high) else "") + " · prix " + fmt(price) + " · " + csak
        val big = text + "\nLe prix n'a pas encore touché le niveau. Une alerte de zone part quand il le touche."
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
