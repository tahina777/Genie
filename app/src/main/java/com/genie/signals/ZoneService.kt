package com.genie.signals

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Active ou coupe la surveillance des zones en temps réel. */
object ZoneAlerts {
    private const val PREFS = "genie"

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("zones", false)

    fun enable(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("zones", true).apply()
        // L'alarme horaire sert aussi à relancer le service s'il a été tué par le système
        Schedule.scheduleNext(ctx)
        if (!Schedule.canExact(ctx)) Schedule.askExactPermission(ctx)
        start(ctx)
    }

    fun disable(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("zones", false).apply()
        ctx.stopService(Intent(ctx, ZoneService::class.java))
        if (!Alerts.isOn(ctx)) Schedule.cancel(ctx)
    }

    fun start(ctx: Context) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ZoneService::class.java))
        } catch (e: Exception) {
            // le système refuse de lancer le service en arrière-plan : il sera relancé par l'alarme
        }
    }

    fun notifyZone(ctx: Context, z: WatchZone, price: Double) {
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
        fun fmt(v: Double): String = String.format(Locale.US, "%." + z.market.decimals + "f", v)
        val buy = z.dir == Direction.BUY
        val title = z.kind + " · " + z.market.name + " (" + z.tf.label + ")"
        val text = "Prix " + fmt(price) + " dans la zone " + fmt(z.low) + " – " + fmt(z.high) +
            " · tendance " + (if (buy) "haussière" else "baissière") +
            " → cherche un " + (if (buy) "ACHAT" else "VENTE") +
            (if (z.note.isNotEmpty()) " · " + z.note else "")
        val big = text + "\nAttends une bougie de confirmation avant d'entrer."
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
        NotificationManagerCompat.from(ctx).notify(z.key.hashCode(), n)
    }
}

/**
 * Service permanent (notification fixe) : écoute les prix en direct de tous les marchés
 * et prévient dès que le prix touche une zone, dans le sens de la tendance.
 * Les zones sont recalculées à chaque clôture d'heure (H1, H4, D1).
 */
class ZoneService : Service() {

    private class Res(val m: Market, val tf: Tf, val ok: Boolean, val list: List<WatchZone>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val frames = listOf(Tf.H1, Tf.H4, Tf.D1)
    private val lastPrice = ConcurrentHashMap<String, Double>()
    private val cooldown = ConcurrentHashMap<String, Long>()
    private var started = false

    @Volatile
    private var zones: List<WatchZone> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground(0)
        if (!ZoneAlerts.isOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            scope.launch { refreshLoop() }
            scope.launch { streamLoop() }
            scope.launch { lowStructureLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- notification fixe ----------

    private fun notification(count: Int): android.app.Notification {
        val channelId = "genie_service"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Surveillance Genie", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (count == 0) "Analyse des zones en cours…"
        else "$count zones surveillées en temps réel (H1, H4, D1)"
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Genie surveille les zones")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun showForeground(count: Int) {
        val n = notification(count)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(4001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(4001, n)
        }
    }

    private fun updateNotification(count: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(4001, notification(count))
    }

    // ---------- zones ----------

    /** Structure H1 · M15 · M5 : vérifiée toutes les 5 minutes (juste après la clôture de chaque bougie M5). */
    private suspend fun lowStructureLoop() {
        delay(20_000L)
        while (true) {
            try {
                StructureLowAlerts.scan(this@ZoneService, ALL_MARKETS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // nouvel essai au prochain passage
            }
            val now = System.currentTimeMillis()
            val next = (now / 300_000L + 1L) * 300_000L + 15_000L
            delay(max(1_000L, next - now))
        }
    }

    private suspend fun refreshLoop() {
        while (true) {
            val failed = try {
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
            val now = System.currentTimeMillis()
            // Prochaine clôture d'heure + 30 s, ou nouvel essai dans 2 minutes en cas d'échec
            val next = if (failed) now + 120_000L else Schedule.nextTrigger(now) + 10_000L
            delay(max(1_000L, next - now))
        }
    }

    /** Recalcule toutes les zones. Renvoie true si au moins un marché n'a pas pu être chargé. */
    private suspend fun rebuild(): Boolean = coroutineScope {
        val old = zones
        val gate = Semaphore(4)
        val structs = java.util.concurrent.ConcurrentHashMap<String, StructureState>()
        val jobs = ALL_MARKETS.flatMap { m ->
            frames.map { tf ->
                async {
                    gate.withPermit {
                        try {
                            val raw = Deriv.candles(m.code, tf.seconds, 300)
                            try {
                                structs[m.code + "|" + tf.label] = MarketStructure.analyze(tf.label, raw)
                            } catch (e: Exception) {
                                // structure indisponible pour ce timeframe
                            }
                            // Confirmation : la bougie qui vient de se fermer a-t-elle réagi dans une zone surveillée ?
                            try {
                                val mine = old.filter { it.market.code == m.code && it.tf == tf }
                                val hit = ZoneConfirm.check(m, tf, raw, mine)
                                if (hit != null && StructureFilter.allows(this@ZoneService, m, hit.zone.dir) &&
                                    !ZoneConfirm.alreadySent(this@ZoneService, m, tf, hit.candle.time)) {
                                    ZoneConfirm.notify(this@ZoneService, m, tf, hit)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // une erreur de confirmation ne doit pas bloquer le calcul des zones
                            }
                            // Setups de la fiche (QMR, QMC, QMM, QM2P, SNRC1/2/3) : alerte quand un setup récent apparaît
                            try {
                                SetupAlerts.scan(this@ZoneService, m, tf, raw)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // une erreur d'alerte ne doit pas bloquer le calcul des zones
                            }
                            // Confirmation validée : englobante du timeframe supérieur sur la zone d'un setup
                            try {
                                SetupConfirmAlerts.check(this@ZoneService, m, tf)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // une erreur d'alerte ne doit pas bloquer le calcul des zones
                            }
                            // Re-entry BBMA en attente (MA5/10, Mid BB, ZZL) : alerte avant que le prix touche le niveau
                            try {
                                BbmaPendingAlerts.check(this@ZoneService, m, tf, raw)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // une erreur d'alerte ne doit pas bloquer le calcul des zones
                            }
                            // FVG, BPR, POI, Inducement, CRT : alerte quand un concept vient d'être confirmé
                            try {
                                SmcConceptAlerts.check(this@ZoneService, m, tf, raw)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // une erreur d'alerte ne doit pas bloquer le calcul des zones
                            }
                            Res(m, tf, true, ZoneBuilder.build(m, tf, raw))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // on garde les anciennes zones de ce marché
                            Res(m, tf, false, old.filter { it.market.code == m.code && it.tf == tf })
                        }
                    }
                }
            }
        }
        val results = jobs.awaitAll()
        // Alerte d'alignement de la structure D1 / H4 / H1
        for (m in ALL_MARKETS) {
            val a = structs[m.code + "|D1"]
            val b = structs[m.code + "|H4"]
            val c = structs[m.code + "|H1"]
            if (a == null || b == null || c == null) continue
            try {
                StructureFilter.store(this@ZoneService, m, a, b)
                StructureAlerts.check(this@ZoneService, m, a, b, c)
            } catch (e: Exception) {
                // une erreur d'alerte ne doit pas bloquer le calcul des zones
            }
        }
        val all = results.flatMap { it.list }
        zones = all
        updateNotification(all.size)
        results.any { !it.ok }
    }

    // ---------- prix en direct ----------

    private suspend fun streamLoop() {
        var useNew = true
        while (true) {
            val url = if (useNew) Deriv.URL_NEW else Deriv.URL_OLD
            val received = try {
                runSocket(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            // Si la connexion n'a rien donné, on essaie l'autre adresse
            if (!received) useNew = !useNew
            delay(5_000L)
        }
    }

    /** Une connexion WebSocket avec un abonnement aux ticks de tous les marchés. */
    private suspend fun runSocket(url: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        val seen = AtomicLong(System.currentTimeMillis())
        val got = AtomicBoolean(false)
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                for (m in ALL_MARKETS) {
                    val q = JSONObject()
                    q.put("ticks", m.code)
                    q.put("subscribe", 1)
                    webSocket.send(q.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                seen.set(System.currentTimeMillis())
                try {
                    val tick = JSONObject(text).optJSONObject("tick") ?: return
                    got.set(true)
                    onTick(tick.getString("symbol"), tick.getDouble("quote"))
                } catch (e: Exception) {
                    // message ignoré
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(got.get())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(got.get())
            }
        })
        // Chien de garde : ping régulier et reconnexion si plus aucun message depuis 60 s
        val watchdog = scope.launch {
            while (true) {
                delay(15_000L)
                ws.send("{\"ping\":1}")
                if (System.currentTimeMillis() - seen.get() > 60_000L) {
                    ws.cancel()
                    done.complete(got.get())
                    break
                }
            }
        }
        try {
            return done.await()
        } finally {
            watchdog.cancel()
            ws.cancel()
        }
    }

    private fun onTick(symbol: String, price: Double) {
        val prev = lastPrice.put(symbol, price) ?: return
        if (prev == price) return
        val lo = min(prev, price)
        val hi = max(prev, price)
        val now = System.currentTimeMillis()
        for (z in zones) {
            if (z.market.code != symbol) continue
            // le trajet du prix doit croiser la zone
            if (hi < z.low || lo > z.high) continue
            // et venir du bon côté : d'en haut pour une demande/un support, d'en bas pour une offre/une résistance
            val fromOk = if (z.dir == Direction.BUY) prev > z.high else prev < z.low
            if (!fromOk) continue

            val key = z.key
            val prefs = getSharedPreferences("genie", Context.MODE_PRIVATE)
            val last = cooldown[key] ?: prefs.getLong("zc_$key", 0L)
            // pas de nouvelle alerte pour la même zone avant 2 bougies de son timeframe
            if (now - last < z.tf.seconds * 2000L) continue
            cooldown[key] = now
            prefs.edit().putLong("zc_$key", now).apply()
            if (StructureFilter.allows(this, z.market, z.dir)) ZoneAlerts.notifyZone(this, z, price)
        }
    }
}
