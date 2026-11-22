package com.genie.signals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Combos SMC sur 3 timeframes, lus du plus grand au plus petit (séries H4 · H1 · M15 et H1 · M15 · M5).
 *  - grand timeframe : le prix teste une zone SMC (Order Block ou FVG) après un BOS ou un CHoCH : donne le sens ;
 *  - timeframe moyen : un balayage de liquidité (sweep) ou un CHoCH dans ce sens, sur les 6 dernières bougies ;
 *  - petit timeframe : un BOS dans ce sens, sur les 3 dernières bougies (confirmation).
 * Les trois timeframes vont dans le même sens. La structure D1 + H4 filtre ensuite l'alerte (Alerts.notify).
 */
object SmcCombos {
    val TRIPLES: List<List<Tf>> = listOf(
        listOf(Tf.H4, Tf.H1, Tf.M15),
        listOf(Tf.H1, Tf.M15, Tf.M5)
    )

    private const val SL_LOOKBACK = 10

    private class Brk(val idx: Int, val dir: Direction, val kind: String)

    private class Lvl(val idx: Int, val price: Double) {
        var broken = false
    }

    class SmcMatch(val dir: Direction, val reasons: List<String>)

    /** Cassures de structure sur des fractales de 3 bougies : BOS (continuation) ou CHoCH (changement). */
    private fun breaks(c: List<Candle>): List<Brk> {
        val n = c.size
        val w = 3
        val res = ArrayList<Brk>()
        var trend = 0
        var lastH: Lvl? = null
        var lastL: Lvl? = null
        for (i in 0 until n) {
            val p = i - w
            if (p >= w) {
                var isH = true
                var isL = true
                for (k in p - w..p + w) {
                    if (k == p) continue
                    if (c[k].high >= c[p].high) isH = false
                    if (c[k].low <= c[p].low) isL = false
                }
                if (isH) lastH = Lvl(p, c[p].high)
                if (isL) lastL = Lvl(p, c[p].low)
            }
            val h = lastH
            val l = lastL
            if (h != null && !h.broken && c[i].close > h.price) {
                h.broken = true
                res.add(Brk(i, Direction.BUY, if (trend < 0) "CHoCH" else "BOS"))
                trend = 1
            } else if (l != null && !l.broken && c[i].close < l.price) {
                l.broken = true
                res.add(Brk(i, Direction.SELL, if (trend > 0) "CHoCH" else "BOS"))
                trend = -1
            }
        }
        return res
    }

    /** Balayage de liquidité dans un sens sur les `within` dernières bougies : mèche au-delà des 10 précédentes, clôture en retrait. */
    private fun swept(c: List<Candle>, dir: Direction, within: Int): Boolean {
        val n = c.size
        for (k in max(11, n - within) until n) {
            var refLow = Double.MAX_VALUE
            var refHigh = -Double.MAX_VALUE
            for (j in k - 10 until k) {
                refLow = min(refLow, c[j].low)
                refHigh = max(refHigh, c[j].high)
            }
            if (dir == Direction.BUY && c[k].low < refLow && c[k].close > refLow) return true
            if (dir == Direction.SELL && c[k].high > refHigh && c[k].close < refHigh) return true
        }
        return false
    }

    /** `closed` : bougies terminées de chaque timeframe de la série, du plus grand au plus petit. */
    fun match(closed: List<List<Candle>>, triple: List<Tf>): SmcMatch? {
        if (closed.size != 3 || closed.any { it.size < 60 }) return null
        val top = SmcCalc(closed[0]).detect() ?: return null
        val dir = top.dir
        val sens = if (dir == Direction.BUY) "haussier" else "baissier"

        val mid = closed[1]
        val midBreaks = breaks(mid)
        val midSweep = swept(mid, dir, 6)
        val midChoch = midBreaks.any { it.kind == "CHoCH" && it.dir == dir && it.idx >= mid.size - 6 }
        if (!midSweep && !midChoch) return null

        val low = closed[2]
        val lowBos = breaks(low).any { it.kind == "BOS" && it.dir == dir && it.idx >= low.size - 3 }
        if (!lowBos) return null

        val reasons = ArrayList<String>()
        reasons.add(triple[0].label + " : " + top.kind + " " + sens + " puis le prix teste " + (if (top.zoneType == "OB") "un Order Block" else "un FVG"))
        reasons.add(
            triple[1].label + " : " +
                (if (midSweep && midChoch) "balayage de liquidité + CHoCH" else if (midSweep) "balayage de liquidité" else "CHoCH") + " " + sens
        )
        reasons.add(triple[2].label + " : BOS " + sens + " (confirmation)")
        return SmcMatch(dir, reasons)
    }

    suspend fun scan(markets: List<Market>, triple: List<Tf>): List<Signal> = coroutineScope {
        val gate = Semaphore(4)
        markets.map { m ->
            async {
                gate.withPermit {
                    try {
                        check(m, triple)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun check(m: Market, triple: List<Tf>): Signal? {
        val closed = ArrayList<List<Candle>>()
        var forming: Candle? = null
        for (tf in triple) {
            val raw = Deriv.candles(m.code, tf.seconds, 300)
            if (raw.size < 100) return null
            closed.add(raw.dropLast(1))
            forming = raw.last()
        }
        val small = triple.last()
        val c = closed.last()
        val last = c.last()
        // Marché fermé (forex le week-end) : bougies trop anciennes
        val now = System.currentTimeMillis() / 1000
        if (now - last.time > small.seconds * 4L) return null

        val mt = match(closed, triple) ?: return null
        val sig = build(m, small, c, triple, mt) ?: return null
        val f = forming
        return if (f != null && Fresh.stale(sig, f)) null else sig
    }

    private fun build(m: Market, small: Tf, c: List<Candle>, triple: List<Tf>, mt: SmcMatch): Signal? {
        val dir = mt.dir
        val atr = Ind.atr(c)
        if (atr <= 0.0) return null
        val last = c.last()
        val entry = last.close
        val from = max(0, c.size - SL_LOOKBACK)
        var sl: Double
        if (dir == Direction.BUY) {
            var lo = Double.MAX_VALUE
            for (k in from until c.size) lo = min(lo, c[k].low)
            sl = lo - 0.3 * atr
        } else {
            var hi = -Double.MAX_VALUE
            for (k in from until c.size) hi = max(hi, c[k].high)
            sl = hi + 0.3 * atr
        }
        var risk = abs(entry - sl)
        if (risk > 4 * atr) return null
        if (risk < 0.5 * atr) {
            sl = if (dir == Direction.BUY) entry - 0.5 * atr else entry + 0.5 * atr
            risk = 0.5 * atr
        }
        val sign = if (dir == Direction.BUY) 1.0 else -1.0
        val names = triple.joinToString(" ") { it.label }
        val reasons = ArrayList<String>()
        reasons.add("Combo SMC sur " + names + " : zone, sweep ou CHoCH, puis BOS dans le même sens")
        reasons.addAll(mt.reasons)
        return Signal(
            m, small, dir, "Combo SMC " + names,
            entry, sl, entry + sign * 1.5 * risk, entry + sign * 2.5 * risk,
            80, reasons, last.time
        )
    }
}
