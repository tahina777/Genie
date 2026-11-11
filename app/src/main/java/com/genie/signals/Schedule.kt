package com.genie.signals

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Alarmes à la clôture des bougies : chaque heure pile + 20 secondes.
 * Toutes les clôtures H1, H4 et D1 tombent sur une heure pile (UTC) :
 * H1 chaque heure, H4 à 0h/4h/8h/12h/16h/20h, D1 à 0h.
 */
object Schedule {
    private const val ACTION = "com.genie.signals.CLOSE_ALARM"
    private const val REQUEST_CODE = 7001
    private const val HOUR_MS = 3_600_000L
    private const val DELAY_MS = 20_000L

    /** Prochain déclenchement : l'heure pile + 20 s, la plus proche dans le futur. */
    fun nextTrigger(nowMs: Long): Long {
        val top = nowMs / HOUR_MS * HOUR_MS
        val t = top + DELAY_MS
        return if (t > nowMs + 1000L) t else top + HOUR_MS + DELAY_MS
    }

    /** Timeframes dont la bougie vient de se terminer à l'heure donnée. */
    fun closedFrames(nowMs: Long): List<Tf> {
        val hourUtc = ((nowMs / HOUR_MS) % 24L).toInt()
        val out = ArrayList<Tf>()
        out.add(Tf.H1)
        if (hourUtc % 4 == 0) out.add(Tf.H4)
        if (hourUtc == 0) out.add(Tf.D1)
        return out
    }

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** Sur Android 12 et 12L, ouvre le réglage « Alarmes et rappels » de Genie. */
    fun askExactPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            // le réglage n'existe pas sur ce téléphone
        }
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        i.action = ACTION
        return PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = nextTrigger(System.currentTimeMillis())
        val pi = pending(ctx)
        try {
            if (canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx))
    }
}

/** Reçoit l'alarme de clôture, programme la suivante et lance l'analyse. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if (!Alerts.isOn(ctx)) return
        Schedule.scheduleNext(ctx)
        val frames = Schedule.closedFrames(System.currentTimeMillis())
        val data = Data.Builder()
            .putString("tfs", frames.joinToString(",") { it.label })
            .build()
        val req = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}

/** Reprogramme l'alarme après un redémarrage du téléphone ou une mise à jour de Genie. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if (Alerts.isOn(ctx)) Schedule.scheduleNext(ctx)
    }
}
