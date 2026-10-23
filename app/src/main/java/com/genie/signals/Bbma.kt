package com.genie.signals

import kotlin.math.max

data class BbmaHit(
    val setup: String,
    val dir: Direction,
    val extremeIdx: Int,
    val reasons: List<String>
)

/**
 * BBMA : Bollinger Bands (20, 2) + MA5/MA10 sur les hauts et les bas + EMA50.
 * Setups détectés : Extreme (préparation), MHV et Reentry.
 */
class BbmaCalc(private val c: List<Candle>) {
    private val closes = DoubleArray(c.size) { c[it].close }
    private val highs = DoubleArray(c.size) { c[it].high }
    private val lows = DoubleArray(c.size) { c[it].low }
    private val bb = Ind.bollinger(closes)
    private val mid = bb.mid
    private val up = bb.up
    private val low = bb.low
    private val ma5h = Ind.wma(highs, 5)
    private val ma5l = Ind.wma(lows, 5)
    private val ema50 = Ind.ema(closes, 50)

    /** 1 = tendance haussière (EMA50 monte), -1 = baissière, 0 = indéterminé. */
    fun trend(): Int {
        val i = c.lastIndex
        if (i < 56 || ema50[i].isNaN() || ema50[i - 5].isNaN()) return 0
        return if (ema50[i] > ema50[i - 5]) 1 else -1
    }

    fun detect(): BbmaHit? {
        val i = c.lastIndex
        if (i < 56) return null

        // Dernier "Extreme" dans les 15 dernières bougies
        var e = -1
        var dir: Direction? = null
        for (k in i downTo max(i - 15, 0)) {
            if (ma5h[k] > up[k]) {
                e = k; dir = Direction.SELL; break
            }
            if (ma5l[k] < low[k]) {
                e = k; dir = Direction.BUY; break
            }
        }
        if (e == -1 || dir == null) return null
        // L'Extreme seul n'est qu'une alerte de préparation, pas une entrée
        if (e == i) return null

        val cur = c[i]

        if (dir == Direction.SELL) {
            // MHV : le prix retouche la bande haute sans que la MA5 ressorte
            if (cur.high > up[i] && ma5h[i] <= up[i] && cur.close < up[i]) {
                return BbmaHit(
                    "BBMA MHV", Direction.SELL, e,
                    listOf("Extreme vente puis MHV : le prix retouche la bande haute sans la dépasser avec la MA5")
                )
            }
            // Reentry : retour sur la bande médiane puis rejet
            var wentBelow = false
            for (k in e + 1 until i) if (c[k].close < mid[k]) wentBelow = true
            if (wentBelow && cur.high >= mid[i] && cur.close < mid[i]) {
                return BbmaHit(
                    "BBMA Reentry", Direction.SELL, e,
                    listOf("Extreme vente puis Reentry : rejet sur la bande médiane")
                )
            }
        } else {
            if (cur.low < low[i] && ma5l[i] >= low[i] && cur.close > low[i]) {
                return BbmaHit(
                    "BBMA MHV", Direction.BUY, e,
                    listOf("Extreme achat puis MHV : le prix retouche la bande basse sans la dépasser avec la MA5")
                )
            }
            var wentAbove = false
            for (k in e + 1 until i) if (c[k].close > mid[k]) wentAbove = true
            if (wentAbove && cur.low <= mid[i] && cur.close > mid[i]) {
                return BbmaHit(
                    "BBMA Reentry", Direction.BUY, e,
                    listOf("Extreme achat puis Reentry : rejet sur la bande médiane")
                )
            }
        }
        return null
    }
}
