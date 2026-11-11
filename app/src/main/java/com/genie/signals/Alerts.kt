package com.genie.signals

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

object Alerts {
    private const val PREFS = "genie"
    private const val WORK = "genie-scan"

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("alerts", false)

    fun enable(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("alerts", true).apply()
    }

    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("alerts", false).apply()
    }

    fun notify(ctx: Context, s: Signal) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val channelId = "genie_signals"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Signaux Genie", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val sens = if (s.dir == Direction.BUY) "ACHAT" else "VENTE"
        val combo = s.setup.startsWith("Combo ")
        val title = if (combo) {
            s.setup.removePrefix("Combo ").take(3) + " " + sens + " " + s.market.name
        } else {
            sens + " " + s.market.name + " (" + s.tf.label + ")"
        }
        val text = "Entrée " + s.fmt(s.entry) + " · SL " + s.fmt(s.sl) + " · TP " + s.fmt(s.tp1) +
            " · Score " + s.score
        val lot = LotCalc.describe(s, LotCalc.capital(ctx), LotCalc.riskPct(ctx))
        var big = s.setup + "\n" + text
        if (combo) big += "\n" + s.reasons.joinToString("\n")
        if (lot.isNotEmpty()) big += "\n" + lot
        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx)
            .notify((s.market.code + s.tf.label + s.setup + s.time).hashCode(), n)
    }
}

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /** Timeframes surveillés par les alertes (M5 et M15 retirés). */
    private val frames = listOf(Tf.H1, Tf.H4, Tf.D1)

    /** Délai minimum (secondes) entre deux analyses d'un timeframe, pour économiser la batterie. */
    private fun every(tf: Tf): Long = when (tf) {
        Tf.M5, Tf.M15 -> 0L
        Tf.H1 -> 1800L
        Tf.H4 -> 3600L
        Tf.D1 -> 14400L
    }

    /** H4 · H1 · M15 à chaque passage, D1 · H4 · H1 toutes les 30 minutes. */
    private fun comboEvery(triple: List<Tf>): Long = if (triple.last() == Tf.M15) 0L else 1800L

    private fun minScore(tf: Tf): Int = if (tf == Tf.M5) 80 else 70

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("genie", Context.MODE_PRIVATE)
        val seen = (prefs.getStringSet("seen", emptySet()) ?: emptySet()).toMutableSet()
        val now = System.currentTimeMillis() / 1000
        var tried = 0
        var ok = 0

        for (tf in frames) {
            val last = prefs.getLong("last_" + tf.label, 0L)
            if (now - last < every(tf)) continue
            tried++
            try {
                val res = Scanner.scan(ALL_MARKETS, tf)
                val fresh = Journal.add(ctx, res.signals)
                for (s in fresh) {
                    if (s.score < minScore(tf)) continue
                    val key = s.market.code + "|" + s.tf.label + "|" + s.dir + "|" + s.time
                    if (seen.add(key)) Alerts.notify(ctx, s)
                }
                prefs.edit().putLong("last_" + tf.label, now).apply()
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // on passe au timeframe suivant
            }
        }

        // Combos BBMA : REE, RME, REM, RRE, RMM sur 3 timeframes
        for (triple in Combos.TRIPLES) {
            val name = "combo_" + triple.joinToString("") { it.label }
            val last = prefs.getLong(name, 0L)
            if (now - last < comboEvery(triple)) continue
            try {
                val hits = Combos.scan(ALL_MARKETS, triple)
                val fresh = Journal.add(ctx, hits)
                for (s in fresh) {
                    val key = "C|" + s.market.code + "|" + s.setup + "|" + s.dir + "|" + s.time
                    if (seen.add(key)) Alerts.notify(ctx, s)
                }
                prefs.edit().putLong(name, now).apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // on réessaiera au prochain passage
            }
        }

        prefs.edit().putStringSet("seen", seen.toList().takeLast(300).toSet()).apply()
        try {
            Journal.refresh(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // le journal sera mis à jour au prochain passage
        }
        return if (tried > 0 && ok == 0) Result.retry() else Result.success()
    }
}
