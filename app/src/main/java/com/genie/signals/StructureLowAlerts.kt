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
 * Alerte d'alignement de la structure sur les petites unités : H1, M15 et M5 deviennent tous haussiers
 * ou tous baissiers. Une alerte par changement d'état ; au tout premier passage l'état est seulement mémorisé.
 */
object StructureLowAlerts {

    /** Analyse H1, M15 et M5 de chaque marché puis alerte si l'alignement vient de changer. */
    suspend fun scan(ctx: Context, markets: List<Market>) {
        for (m in markets) {
            try {
                val h1 = MarketStructure.analyze("H1", Deriv.candles(m.code, Tf.H1.seconds, 300))
                val m15 = MarketStructure.analyze("M15", Deriv.candles(m.code, Tf.M15.seconds, 300))
                val m5 = MarketStructure.analyze("M5", Deriv.candles(m.code, Tf.M5.seconds, 300))
                check(ctx, m, h1, m15, m5)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // marché suivant
            }
        }
    }

    fun check(ctx: Context, m: Market, h1: StructureState, m15: StructureState, m5: StructureState) {
        val al = MarketStructure.alignment(listOf(h1, m15, m5))
        val prefs = ctx.getSharedPreferences("genie", Context.MODE_PRIVATE)
        val key = "stl_" + m.code
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
        val text = "H1, M15 et M5 sont tous " + (if (up) "haussiers (HH + HL)" else "baissiers (LH + LL)")
        val big = text + "\nCherche une entrée dans le sens " + (if (up) "de l'achat" else "de la vente") +
            " sur M15 / M5 (confirmation de l'entrée)."
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
