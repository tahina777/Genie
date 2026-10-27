package com.genie.signals

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SdHit(
    val dir: Direction,
    val low: Double,
    val high: Double,
    val strength: Double,
    val reasons: List<String>
)

/**
 * Zones d'offre et de demande : une petite bougie de base suivie d'une impulsion
 * d'au moins 1,5 ATR. La zone doit être fraîche (jamais retouchée depuis) et la
 * dernière bougie doit la toucher pour la première fois sans la casser.
 */
class SdCalc(private val c: List<Candle>) {

    fun detect(atr: Double): SdHit? {
        val n = c.size
        if (n < 60 || atr <= 0.0) return null
        val last = c[n - 1]
        var best: SdHit? = null
        var i = max(1, n - 120)
        while (i <= n - 6) {
            val b = c[i]
            val small = abs(b.close - b.open) <= 0.6 * atr && (b.high - b.low) <= 1.2 * atr
            if (small) {
                var hi = Double.NEGATIVE_INFINITY
                var lo = Double.POSITIVE_INFINITY
                for (k in (i + 1)..(i + 3)) {
                    hi = max(hi, c[k].high)
                    lo = min(lo, c[k].low)
                }
                val up = (hi - b.high) / atr
                val down = (b.low - lo) / atr
                val hit = when {
                    up >= 1.5 && c[i + 1].close > b.high && up >= down ->
                        check(i, Direction.BUY, up, last)
                    down >= 1.5 && c[i + 1].close < b.low ->
                        check(i, Direction.SELL, down, last)
                    else -> null
                }
                if (hit != null) {
                    val cur = best
                    if (cur == null || hit.strength > cur.strength) best = hit
                }
            }
            i++
        }
        return best
    }

    private fun check(i: Int, dir: Direction, power: Double, last: Candle): SdHit? {
        val n = c.size
        val b = c[i]
        val low = b.low
        val high = b.high
        // La zone doit être fraîche : jamais touchée depuis l'impulsion
        for (k in (i + 4) until (n - 1)) {
            if (dir == Direction.BUY) {
                if (c[k].low <= high) return null
            } else {
                if (c[k].high >= low) return null
            }
        }
        // La dernière bougie touche la zone sans la casser
        if (dir == Direction.BUY) {
            if (last.low > high || last.close < low) return null
        } else {
            if (last.high < low || last.close > high) return null
        }
        val label = if (dir == Direction.BUY) "demande" else "offre"
        val reasons = listOf(
            "Zone de " + label + " fraîche touchée pour la première fois",
            "Impulsion de " + String.format(Locale.US, "%.1f", power) + " ATR depuis la zone"
        )
        return SdHit(dir, low, high, power, reasons)
    }
}
