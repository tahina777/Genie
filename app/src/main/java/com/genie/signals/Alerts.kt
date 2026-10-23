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
        val text = "Entrée " + s.fmt(s.entry) + " · SL " + s.fmt(s.sl) + " · TP " + s.fmt(s.tp1) +
            " · Score " + s.score
        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(sens + " " + s.market.name + " (" + s.tf.label + ")")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.setup + "\n" + text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((s.market.code + s.time).hashCode(), n)
    }
}

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val res = Scanner.scan(ALL_MARKETS, Tf.M15)
            val prefs = applicationContext.getSharedPreferences("genie", Context.MODE_PRIVATE)
            val seen = (prefs.getStringSet("seen", emptySet()) ?: emptySet()).toMutableSet()
            for (s in res.signals) {
                if (s.score < 70) continue
                val key = s.market.code + "|" + s.tf.label + "|" + s.dir + "|" + s.time
                if (seen.add(key)) Alerts.notify(applicationContext, s)
            }
            prefs.edit().putStringSet("seen", seen.toList().takeLast(200).toSet()).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
