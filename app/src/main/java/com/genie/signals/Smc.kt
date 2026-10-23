package com.genie.signals

import kotlin.math.max
import kotlin.math.min

data class Zone(val low: Double, val high: Double)

data class SmcHit(
    val dir: Direction,
    val kind: String,        // BOS ou CHoCH
    val zoneType: String,    // OB ou FVG
    val zone: Zone,
    val sweep: Boolean,
    val target: Double?,
    val reasons: List<String>
)

/**
 * SMC (Smart Money Concepts) simplifié : structure (BOS / CHoCH),
 * Order Block, Fair Value Gap et balayage de liquidité.
 */
class SmcCalc(private val c: List<Candle>) {

    private class Lvl(val idx: Int, val price: Double) {
        var broken = false
    }

    private class Brk(val idx: Int, val dir: Direction, val kind: String, val originIdx: Int)

    fun detect(): SmcHit? {
        val n = c.size
        val w = 3
        if (n < 40) return null

        var trend = 0
        var lastH: Lvl? = null
        var lastL: Lvl? = null
        val breaks = mutableListOf<Brk>()

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
                breaks.add(Brk(i, Direction.BUY, if (trend < 0) "CHoCH" else "BOS", l?.idx ?: max(0, h.idx - 5)))
                trend = 1
            } else if (l != null && !l.broken && c[i].close < l.price) {
                l.broken = true
                breaks.add(Brk(i, Direction.SELL, if (trend > 0) "CHoCH" else "BOS", h?.idx ?: max(0, l.idx - 5)))
                trend = -1
            }
        }

        val last = n - 1
        val b = breaks.lastOrNull { it.idx >= n - 40 && it.idx < last } ?: return null
        val start = b.originIdx.coerceIn(0, b.idx - 1)

        // Order Block : dernière bougie opposée avant l'impulsion
        var ob: Zone? = null
        for (k in b.idx - 1 downTo start) {
            val cd = c[k]
            val opposite = if (b.dir == Direction.BUY) cd.close < cd.open else cd.close > cd.open
            if (opposite) {
                ob = Zone(cd.low, cd.high)
                break
            }
        }

        // Fair Value Gap le plus récent dans l'impulsion
        var fvg: Zone? = null
        for (k in start + 1..b.idx) {
            if (k + 1 >= n) break
            if (b.dir == Direction.BUY && c[k - 1].high < c[k + 1].low) {
                fvg = Zone(c[k - 1].high, c[k + 1].low)
            }
            if (b.dir == Direction.SELL && c[k - 1].low > c[k + 1].high) {
                fvg = Zone(c[k + 1].high, c[k - 1].low)
            }
        }

        val cur = c[last]
        fun touches(z: Zone) = cur.low <= z.high && cur.high >= z.low
        fun holds(z: Zone) = if (b.dir == Direction.BUY) cur.close >= z.low else cur.close <= z.high

        var zone: Zone? = null
        var zoneType = ""
        if (ob != null && touches(ob) && holds(ob)) {
            zone = ob; zoneType = "OB"
        } else if (fvg != null && touches(fvg) && holds(fvg)) {
            zone = fvg; zoneType = "FVG"
        }
        if (zone == null) return null

        // Balayage de liquidité sur les 3 dernières bougies
        var sweep = false
        for (k in max(11, last - 2)..last) {
            var refLow = Double.MAX_VALUE
            var refHigh = -Double.MAX_VALUE
            for (j in k - 10 until k) {
                refLow = min(refLow, c[j].low)
                refHigh = max(refHigh, c[j].high)
            }
            if (b.dir == Direction.BUY && c[k].low < refLow && c[k].close > refLow) sweep = true
            if (b.dir == Direction.SELL && c[k].high > refHigh && c[k].close < refHigh) sweep = true
        }

        // Objectif : extrême opposé des 60 dernières bougies
        var target: Double? = null
        val from = max(0, n - 60)
        if (b.dir == Direction.BUY) {
            var hi = -Double.MAX_VALUE
            for (k in from until n) hi = max(hi, c[k].high)
            target = hi
        } else {
            var lo = Double.MAX_VALUE
            for (k in from until n) lo = min(lo, c[k].low)
            target = lo
        }

        val sens = if (b.dir == Direction.BUY) "haussier" else "baissier"
        val reasons = mutableListOf<String>()
        reasons.add(
            if (b.kind == "CHoCH") "CHoCH $sens : changement de structure"
            else "BOS $sens : continuation de la tendance"
        )
        reasons.add(
            if (zoneType == "OB") "Le prix retourne tester l'Order Block"
            else "Le prix retourne combler le Fair Value Gap"
        )
        if (sweep) reasons.add("Balayage de liquidité juste avant le retour")

        return SmcHit(b.dir, b.kind, zoneType, zone, sweep, target, reasons)
    }
}
