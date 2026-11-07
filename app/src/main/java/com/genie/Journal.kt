package com.genie.signals

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

data class JEntry(
    val id: String,
    val market: String,
    val code: String,
    val tf: String,
    val tfSeconds: Int,
    val buy: Boolean,
    val setup: String,
    val score: Int,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val time: Long,
    val decimals: Int,
    val status: String, // open, tp, sl, expired, unknown
    val r: Double
) {
    val closed: Boolean get() = status != "open"
    fun fmt(v: Double): String = String.format(Locale.US, "%." + decimals + "f", v)
}

data class JStats(val closed: Int, val open: Int, val wins: Int, val totalR: Double) {
    val winRate: Double get() = if (closed == 0) 0.0 else wins * 100.0 / closed
    val avgR: Double get() = if (closed == 0) 0.0 else totalR / closed
}

/** Journal des signaux : enregistre chaque signal puis vérifie s'il a touché TP1 ou SL. */
object Journal {
    private const val PREFS = "genie_journal"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 300
    private const val MAX_HOLD = 60
    const val MIN_SCORE = 65
    private val lock = Any()

    fun all(ctx: Context): List<JEntry> = synchronized(lock) { load(ctx) }

    fun add(ctx: Context, signals: List<Signal>) {
        synchronized(lock) {
            val list = load(ctx).toMutableList()
            val ids = list.map { it.id }.toHashSet()
            var changed = false
            for (s in signals) {
                if (s.score < MIN_SCORE) continue
                val id = s.market.code + "|" + s.tf.label + "|" + s.dir + "|" + s.time
                if (!ids.add(id)) continue
                list.add(
                    JEntry(
                        id, s.market.name, s.market.code, s.tf.label, s.tf.seconds,
                        s.dir == Direction.BUY, s.setup, s.score, s.entry, s.sl, s.tp1,
                        s.time, s.market.decimals, "open", 0.0
                    )
                )
                changed = true
            }
            if (changed) save(ctx, list)
        }
    }

    suspend fun refresh(ctx: Context) {
        val open = all(ctx).filter { !it.closed }
        if (open.isEmpty()) return
        val updated = HashMap<String, JEntry>()
        val groups = open.groupBy { it.code + "|" + it.tfSeconds }
        for ((_, g) in groups) {
            val first = g.first()
            val candles = try {
                Deriv.candles(first.code, first.tfSeconds, 1000)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                continue
            }
            for (e in g) updated[e.id] = evaluate(e, candles)
        }
        if (updated.isEmpty()) return
        synchronized(lock) {
            save(ctx, load(ctx).map { updated[it.id] ?: it })
        }
    }

    private fun evaluate(e: JEntry, c: List<Candle>): JEntry {
        if (c.isEmpty()) return e
        // Historique trop court : on ne peut pas vérifier ce vieux signal
        if (e.time < c.first().time) return e.copy(status = "unknown", r = 0.0)
        val after = c.filter { it.time > e.time }
        if (after.isEmpty()) return e
        val risk = abs(e.entry - e.sl)
        if (risk <= 0.0) return e
        val n = minOf(after.size, MAX_HOLD)
        for (i in 0 until n) {
            val k = after[i]
            val hitSl = if (e.buy) k.low <= e.sl else k.high >= e.sl
            val hitTp = if (e.buy) k.high >= e.tp1 else k.low <= e.tp1
            // Si SL et TP sont touchés dans la même bougie, on compte la perte
            if (hitSl) return e.copy(status = "sl", r = -1.0)
            if (hitTp) return e.copy(status = "tp", r = 1.5)
        }
        if (after.size >= MAX_HOLD) {
            val sign = if (e.buy) 1.0 else -1.0
            val r = (after[MAX_HOLD - 1].close - e.entry) * sign / risk
            return e.copy(status = "expired", r = r)
        }
        return e
    }

    fun stats(list: List<JEntry>): JStats {
        val done = list.filter { it.status == "tp" || it.status == "sl" || it.status == "expired" }
        return JStats(
            done.size,
            list.count { it.status == "open" },
            done.count { it.r > 0 },
            done.sumOf { it.r }
        )
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        }
    }

    private fun save(ctx: Context, list: List<JEntry>) {
        val arr = JSONArray()
        for (e in list.sortedByDescending { it.time }.take(MAX_ENTRIES)) {
            val o = JSONObject()
            o.put("id", e.id)
            o.put("market", e.market)
            o.put("code", e.code)
            o.put("tf", e.tf)
            o.put("tfs", e.tfSeconds)
            o.put("buy", e.buy)
            o.put("setup", e.setup)
            o.put("score", e.score)
            o.put("entry", e.entry)
            o.put("sl", e.sl)
            o.put("tp1", e.tp1)
            o.put("time", e.time)
            o.put("dec", e.decimals)
            o.put("status", e.status)
            o.put("r", e.r)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(ctx: Context): List<JEntry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<JEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    JEntry(
                        o.getString("id"), o.getString("market"), o.getString("code"),
                        o.getString("tf"), o.getInt("tfs"), o.getBoolean("buy"),
                        o.getString("setup"), o.getInt("score"), o.getDouble("entry"),
                        o.getDouble("sl"), o.getDouble("tp1"), o.getLong("time"),
                        o.getInt("dec"), o.getString("status"), o.getDouble("r")
                    )
                )
            }
            out
        } catch (ex: Exception) {
            emptyList()
        }
    }
}
