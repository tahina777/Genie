package com.genie.signals

import kotlin.math.abs
import kotlin.math.max

/** Zone surveillée en temps réel : le prix doit venir la toucher depuis le bon côté. */
data class WatchZone(
    val market: Market,
    val tf: Tf,
    val kind: String,
    val dir: Direction,
    val low: Double,
    val high: Double
) {
    val key: String
        get() = market.code + "|" + tf.label + "|" + kind + "|" + dir + "|" + (low * 1_000_000.0).toLong()
}

/**
 * Construit les zones à surveiller pour un marché et un timeframe, uniquement dans le sens
 * de la tendance (EMA50) : zones de demande/offre fraîches, Re-entry BBMA (bande médiane
 * après un Extrême) et supports/résistances respectés au moins 2 fois.
 */
object ZoneBuilder {

    fun build(m: Market, tf: Tf, raw: List<Candle>): List<WatchZone> {
        if (raw.size < 100) return emptyList()
        // On ignore la dernière bougie (encore en formation)
        val c = raw.dropLast(1)
        val last = c.last()
        // Marché fermé (forex le week-end) : bougies trop anciennes
        val now = System.currentTimeMillis() / 1000
        if (now - last.time > tf.seconds * 4L) return emptyList()

        val atr = Ind.atr(c)
        if (atr <= 0.0) return emptyList()

        val trend = BbmaCalc(c).trend()
        if (trend == 0) return emptyList()
        val dir = if (trend > 0) Direction.BUY else Direction.SELL
        val price = last.close

        val out = ArrayList<WatchZone>()
        out.addAll(supplyDemand(m, tf, c, atr, dir, price))
        out.addAll(reentry(m, tf, c, atr, dir, price))
        for (l in levels(m, tf, c, atr, dir, price)) {
            // pas de doublon avec une zone déjà surveillée
            if (out.none { it.high >= l.low && it.low <= l.high }) out.add(l)
        }
        return out
    }

    /** Zones de demande (tendance haussière) ou d'offre (baissière) jamais retouchées. */
    private fun supplyDemand(
        m: Market, tf: Tf, c: List<Candle>, atr: Double, dir: Direction, price: Double
    ): List<WatchZone> {
        val n = c.size
        if (n < 60) return emptyList()
        val found = ArrayList<WatchZone>()
        var i = max(1, n - 120)
        while (i <= n - 6) {
            val b = c[i]
            val small = abs(b.close - b.open) <= 0.6 * atr && (b.high - b.low) <= 1.2 * atr
            if (small) {
                var hi = Double.NEGATIVE_INFINITY
                var lo = Double.POSITIVE_INFINITY
                for (k in (i + 1)..(i + 3)) {
                    hi = max(hi, c[k].high)
                    lo = kotlin.math.min(lo, c[k].low)
                }
                val up = (hi - b.high) / atr
                val down = (b.low - lo) / atr
                val zoneDir: Direction? = when {
                    up >= 1.5 && c[i + 1].close > b.high && up >= down -> Direction.BUY
                    down >= 1.5 && c[i + 1].close < b.low -> Direction.SELL
                    else -> null
                }
                if (zoneDir == dir) {
                    var fresh = true
                    for (k in (i + 4) until n) {
                        if (dir == Direction.BUY) {
                            if (c[k].low <= b.high) { fresh = false; break }
                        } else {
                            if (c[k].high >= b.low) { fresh = false; break }
                        }
                    }
                    val placed = if (dir == Direction.BUY) b.high < price else b.low > price
                    if (fresh && placed) {
                        val kind = if (dir == Direction.BUY) "Zone de demande" else "Zone d'offre"
                        found.add(WatchZone(m, tf, kind, dir, b.low, b.high))
                    }
                }
            }
            i++
        }
        return found.sortedBy { abs(price - (it.low + it.high) / 2) }.take(2)
    }

    /** Re-entry BBMA : retour du prix sur la bande médiane après un Extrême dans le sens de la tendance. */
    private fun reentry(
        m: Market, tf: Tf, c: List<Candle>, atr: Double, dir: Direction, price: Double
    ): List<WatchZone> {
        val n = c.size
        if (n < 60) return emptyList()
        val closes = DoubleArray(n) { c[it].close }
        val highs = DoubleArray(n) { c[it].high }
        val lows = DoubleArray(n) { c[it].low }
        val bb = Ind.bollinger(closes)
        val ma5h = Ind.wma(highs, 5)
        val ma5l = Ind.wma(lows, 5)
        val i = n - 1

        var e = -1
        var ext: Direction? = null
        for (k in i downTo max(i - 15, 0)) {
            if (ma5h[k] > bb.up[k]) { e = k; ext = Direction.SELL; break }
            if (ma5l[k] < bb.low[k]) { e = k; ext = Direction.BUY; break }
        }
        if (e == -1 || ext != dir) return emptyList()

        val mid = bb.mid[i]
        if (mid.isNaN()) return emptyList()

        // Le prix a déjà repris la bande médiane après l'Extrême
        var crossed = false
        for (k in (e + 1)..i) {
            if (dir == Direction.BUY && c[k].close > bb.mid[k]) crossed = true
            if (dir == Direction.SELL && c[k].close < bb.mid[k]) crossed = true
        }
        if (!crossed) return emptyList()

        val pad = 0.15 * atr
        val placed = if (dir == Direction.BUY) price > mid + pad else price < mid - pad
        if (!placed) return emptyList()
        return listOf(WatchZone(m, tf, "Re-entry BBMA", dir, mid - pad, mid + pad))
    }

    /** Supports (tendance haussière) ou résistances (baissière) respectés au moins 2 fois. */
    private fun levels(
        m: Market, tf: Tf, c: List<Candle>, atr: Double, dir: Direction, price: Double
    ): List<WatchZone> {
        val n = 5
        val pts = ArrayList<Double>()
        for (i in n until c.size - n) {
            var isHigh = true
            var isLow = true
            for (k in 1..n) {
                if (c[i].high <= c[i - k].high || c[i].high <= c[i + k].high) isHigh = false
                if (c[i].low >= c[i - k].low || c[i].low >= c[i + k].low) isLow = false
            }
            if (isHigh) pts.add(c[i].high)
            if (isLow) pts.add(c[i].low)
        }
        pts.sort()

        val prices = ArrayList<Double>()
        var group = ArrayList<Double>()
        for (p in pts) {
            if (group.isNotEmpty() && p - group.last() > 0.3 * atr) {
                if (group.size >= 2) prices.add(group.average())
                group = ArrayList()
            }
            group.add(p)
        }
        if (group.size >= 2) prices.add(group.average())

        val pad = 0.15 * atr
        val kind = if (dir == Direction.BUY) "Support" else "Résistance"
        return prices
            .filter { if (dir == Direction.BUY) it < price - pad else it > price + pad }
            .sortedBy { abs(it - price) }
            .take(2)
            .map { WatchZone(m, tf, kind, dir, it - pad, it + pad) }
    }
}
