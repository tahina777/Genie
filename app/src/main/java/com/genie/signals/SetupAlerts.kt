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

/**
 * Alertes des setups de la fiche : QMR, QMC, QMM, QM2P (QmSetups.kt), SNRC1/2/3 (SnrSetups.kt) et Hybrid 1/2 (HybridSetups.kt) RBR/DBD/RBD/DBR (BaseSetups.kt) et Blindspot 1/2, CK1, CK2 (PatSetups.kt).
 * Appelé par ZoneService à chaque clôture : prévient quand un setup récent apparaît (en attente d'entrée)
 * et quand son entrée est touchée. Une alerte par setup et par changement d'état.
 */
object SetupAlerts {

    private class Item(
        val kind: String,
        val dir: QmDirection,
        val breakTime: Long,
        val status: QmStatus,
        val zoneLow: Double,
        val zoneHigh: Double,
        val entry: Double,
        val sl: Double,
        val tp1: Double,
        val tp2: Double,
        val rr: Double
    )

    fun scan(ctx: Context, m: Market, tf: Tf, raw: List<Candle>) {
        if (raw.size < 80) return
        val c = raw.dropLast(1) // la dernière bougie est encore en formation
        val n = c.size
        val nowSec = System.currentTimeMillis() / 1000L
        if (nowSec - c[n - 1].time > tf.seconds * 4L) return // marché fermé
        val atr = Ind.atr(c)
        if (atr <= 0.0) return
        val last = c[n - 1].close
        val qcs = c.map { QmCandle(it.time, it.open, it.high, it.low, it.close) }

        val items = ArrayList<Item>()
        for (s in QmSetups.detect(qcs)) {
            if (s.breakIdx < n - 4) continue // seulement les setups récents
            if (abs(s.entry - last) > 5.0 * atr) continue
            items.add(
                Item(
                    s.types.joinToString("/") { it.name }, s.direction, c[s.breakIdx].time, s.status,
                    s.zoneLow, s.zoneHigh, s.entry, s.stopLoss, s.tp1, s.tp2, s.rr
                )
            )
        }
        for (s in SnrSetups.detect(qcs)) {
            val ref = s.retestIdx ?: s.breakIdx
            if (ref < n - 4) continue
            if (abs(s.entry - last) > 5.0 * atr) continue
            items.add(
                Item(
                    s.type.name, s.direction, c[s.breakIdx].time, s.status,
                    s.zoneLow, s.zoneHigh, s.entry, s.stopLoss, s.tp1, s.tp2, s.rr
                )
            )
        }

        for (s in HybridSetups.detect(qcs)) {
            if (s.refIdx < n - 35) continue // seulement les setups récents
            if (abs(s.entry - last) > 5.0 * atr) continue
            val nm = (if (s.type == HybridType.HYBRID1) "Hybrid 1" else "Hybrid 2") +
                (if (s.via.isEmpty()) "" else " (" + s.via + ")")
            items.add(
                Item(
                    nm, s.direction, c[s.refIdx].time, s.status,
                    s.zoneLow, s.zoneHigh, s.entry, s.stopLoss, s.tp1, s.tp2, s.rr
                )
            )
        }

        for (s in BaseSetups.detect(qcs)) {
            val ref = s.retestIdx ?: s.legOutIdx
            if (ref < n - 4) continue // seulement les zones récentes ou en premier retest
            if (abs(s.entry - last) > 5.0 * atr) continue
            items.add(
                Item(
                    s.type.name, s.direction, c[s.legOutIdx].time, s.status,
                    s.zoneLow, s.zoneHigh, s.entry, s.stopLoss, s.tp1, s.tp2, s.rr
                )
            )
        }

        for (s in PatSetups.detect(qcs)) {
            if (s.keyIdx < n - 4 && (s.retestIdx ?: -1) < n - 2) continue // seulement les setups récents
            if (abs(s.entry - last) > 5.0 * atr) continue
            items.add(
                Item(
                    patName(s.type), s.direction, c[s.keyIdx].time, s.status,
                    s.zoneLow, s.zoneHigh, s.entry, s.stopLoss, s.tp1, s.tp2, s.rr
                )
            )
        }

        val prefs = ctx.getSharedPreferences("genie", Context.MODE_PRIVATE)
        var sent = 0
        for (s in items) {
            if (sent >= 3) break
            val sd = if (s.dir == QmDirection.BUY) Direction.BUY else Direction.SELL
            if (!StructureFilter.allows(ctx, m, sd)) continue
            val key = "sa_" + m.code + "|" + tf.label + "|" + s.kind + "|" + s.dir.name
            val value = s.breakTime.toString() + ":" + s.status.name
            if (prefs.getString(key, null) == value) continue
            prefs.edit().putString(key, value).apply()
            notify(ctx, m, tf, s, key)
            sent++
        }
    }

    private fun notify(ctx: Context, m: Market, tf: Tf, s: Item, key: String) {
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
        val title = "Setup " + s.kind + " · " + (if (buy) "ACHAT" else "VENTE") +
            " · " + m.name + " (" + tf.label + ")"
        val state = if (s.status == QmStatus.TRIGGERED) "entrée touchée" else "en attente de l'entrée"
        val text = state + " · zone " + fmt(s.zoneLow) + " – " + fmt(s.zoneHigh) + " · entrée " + fmt(s.entry)
        val big = text +
            "\nSL " + fmt(s.sl) + " · TP1 " + fmt(s.tp1) + " · TP2 " + fmt(s.tp2) +
            " · RR " + String.format(Locale.US, "%.1f", s.rr) +
            "\nRègles déduites de ta fiche : vérifie sur le graphique avant d'entrer."
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
