package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Concept SMC dessiné sur le graphique. kind : FVG, BPR, IDM, POI ou CRT. buy : haussier. low = high : simple ligne. */
class SmcConcept(
    val kind: String,
    val buy: Boolean,
    val fromIdx: Int,
    val low: Double,
    val high: Double,
    val label: String
)

/**
 * FVG, BPR, Inducement, POI et Candle Range Theory, sur les bougies terminées.
 * FVG : écart entre la bougie i-1 et i+1 (au moins 0,25 ATR), jamais comblé.
 * BPR : zone où un FVG haussier et un FVG baissier proches (15 bougies) se chevauchent, pas encore traversée.
 * POI : dernière bougie opposée avant une impulsion qui casse son extrême et laisse un FVG, jamais retouchée.
 * Inducement (IDM) : petit haut/bas (fractale 2) pris en mèche puis rejeté, ou encore en attente près du prix.
 * CRT : bougie 1 = range, bougie 2 balaie un extrême de la bougie 1 et clôture dedans : cible = l'autre extrême.
 */
object SmcConcepts {
    private const val LOOKBACK = 150

    private class Gap(val buy: Boolean, val idx: Int, val low: Double, val high: Double)

    fun detect(raw: List<Candle>): List<SmcConcept> {
        val c = raw.dropLast(1)
        val n = c.size
        if (n < 40) return emptyList()
        val atr = Ind.atr(c)
        if (atr <= 0.0) return emptyList()
        val price = c.last().close
        val gaps = gaps(c, atr)
        val out = ArrayList<SmcConcept>()
        out.addAll(fvg(c, gaps, price))
        out.addAll(bpr(c, gaps, price))
        out.addAll(poi(c, gaps, atr, price))
        out.addAll(idm(c, atr, price))
        out.addAll(crt(c, atr))
        return out
    }

    private fun gaps(c: List<Candle>, atr: Double): List<Gap> {
        val n = c.size
        val res = ArrayList<Gap>()
        for (i in max(1, n - LOOKBACK)..n - 2) {
            val a = c[i - 1]
            val z = c[i + 1]
            if (z.low - a.high >= 0.25 * atr) res.add(Gap(true, i, a.high, z.low))
            if (a.low - z.high >= 0.25 * atr) res.add(Gap(false, i, z.high, a.low))
        }
        return res
    }

    private fun filled(c: List<Candle>, g: Gap): Boolean {
        for (k in g.idx + 2 until c.size) {
            if (g.buy && c[k].low <= g.low) return true
            if (!g.buy && c[k].high >= g.high) return true
        }
        return false
    }

    private fun dist(low: Double, high: Double, price: Double): Double = abs((low + high) / 2.0 - price)

    private fun fvg(c: List<Candle>, gaps: List<Gap>, price: Double): List<SmcConcept> =
        gaps.filter { !filled(c, it) }
            .sortedBy { dist(it.low, it.high, price) }
            .take(4)
            .map { SmcConcept("FVG", it.buy, it.idx - 1, it.low, it.high, if (it.buy) "FVG achat" else "FVG vente") }

    private fun bpr(c: List<Candle>, gaps: List<Gap>, price: Double): List<SmcConcept> {
        val found = ArrayList<SmcConcept>()
        for (a in gaps) {
            for (b in gaps) {
                if (!a.buy || b.buy) continue
                if (abs(a.idx - b.idx) > 15 || a.idx == b.idx) continue
                val lo = max(a.low, b.low)
                val hi = min(a.high, b.high)
                if (hi <= lo) continue
                val later = max(a.idx, b.idx)
                var crossed = false
                for (k in later + 2 until c.size) {
                    if (c[k].low <= lo && c[k].high >= hi) crossed = true
                }
                if (crossed) continue
                val buyDir = if (a.idx > b.idx) a.buy else b.buy
                found.add(
                    SmcConcept("BPR", buyDir, min(a.idx, b.idx) - 1, lo, hi, if (buyDir) "BPR achat" else "BPR vente")
                )
            }
        }
        return found.sortedBy { dist(it.low, it.high, price) }.take(2)
    }

    private fun poi(c: List<Candle>, gaps: List<Gap>, atr: Double, price: Double): List<SmcConcept> {
        val n = c.size
        val found = ArrayList<SmcConcept>()
        for (i in max(2, n - LOOKBACK)..n - 5) {
            val b = c[i]
            var hiMax = Double.NEGATIVE_INFINITY
            var loMin = Double.POSITIVE_INFINITY
            for (j in i + 1..i + 3) {
                hiMax = max(hiMax, c[j].high)
                loMin = min(loMin, c[j].low)
            }
            val gapNear = gaps.filter { it.idx in i + 1..i + 2 }
            if (b.close < b.open && hiMax - b.low >= 1.5 * atr && c[i + 1].close > b.high &&
                gapNear.any { it.buy }
            ) {
                var mitig = false
                for (k in i + 4 until n) if (c[k].low <= b.high) mitig = true
                if (!mitig) found.add(SmcConcept("POI", true, i, b.low, b.high, "POI achat"))
            }
            if (b.close > b.open && b.high - loMin >= 1.5 * atr && c[i + 1].close < b.low &&
                gapNear.any { !it.buy }
            ) {
                var mitig = false
                for (k in i + 4 until n) if (c[k].high >= b.low) mitig = true
                if (!mitig) found.add(SmcConcept("POI", false, i, b.low, b.high, "POI vente"))
            }
        }
        return found.sortedBy { dist(it.low, it.high, price) }.take(3)
    }

    private fun idm(c: List<Candle>, atr: Double, price: Double): List<SmcConcept> {
        val n = c.size
        val found = ArrayList<SmcConcept>()
        for (i in max(2, n - 60)..n - 3) {
            val isHigh = c[i].high > c[i - 1].high && c[i].high > c[i - 2].high &&
                c[i].high > c[i + 1].high && c[i].high > c[i + 2].high
            val isLow = c[i].low < c[i - 1].low && c[i].low < c[i - 2].low &&
                c[i].low < c[i + 1].low && c[i].low < c[i + 2].low
            if (isLow) {
                var sweep = -1
                for (j in i + 3 until n) if (c[j].low < c[i].low) { sweep = j; break }
                if (sweep == -1) {
                    if (abs(c[i].low - price) <= 3 * atr)
                        found.add(SmcConcept("IDM", true, i, c[i].low, c[i].low, "IDM bas"))
                } else if (sweep >= n - 25 && c[sweep].close > c[i].low) {
                    found.add(SmcConcept("IDM", true, i, c[i].low, c[i].low, "IDM bas pris"))
                }
            }
            if (isHigh) {
                var sweep = -1
                for (j in i + 3 until n) if (c[j].high > c[i].high) { sweep = j; break }
                if (sweep == -1) {
                    if (abs(c[i].high - price) <= 3 * atr)
                        found.add(SmcConcept("IDM", false, i, c[i].high, c[i].high, "IDM haut"))
                } else if (sweep >= n - 25 && c[sweep].close < c[i].high) {
                    found.add(SmcConcept("IDM", false, i, c[i].high, c[i].high, "IDM haut pris"))
                }
            }
        }
        return found.sortedBy { dist(it.low, it.high, price) }.take(4)
    }

    private fun crt(c: List<Candle>, atr: Double): List<SmcConcept> {
        val n = c.size
        val found = ArrayList<SmcConcept>()
        for (i in max(1, n - 40) until n) {
            val r = c[i - 1]
            val s = c[i]
            if (r.high - r.low < 0.8 * atr) continue
            if (s.high > r.high && s.close < r.high && s.close > r.low) {
                var done = false
                for (k in i + 1 until n) if (c[k].low <= r.low || c[k].high > s.high) done = true
                if (!done) found.add(SmcConcept("CRT", false, i - 1, r.low, r.high, "CRT vente"))
            }
            if (s.low < r.low && s.close > r.low && s.close < r.high) {
                var done = false
                for (k in i + 1 until n) if (c[k].high >= r.high || c[k].low < s.low) done = true
                if (!done) found.add(SmcConcept("CRT", true, i - 1, r.low, r.high, "CRT achat"))
            }
        }
        return found.takeLast(2)
    }
}
