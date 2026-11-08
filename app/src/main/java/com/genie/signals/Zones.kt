package com.genie.signals

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PdInfo(val zone: String, val pct: Int)

/**
 * Zones Premium / Discount : on coupe en deux le range des 100 dernières bougies.
 * Au-dessus de 55 % = Premium (cher), en dessous de 45 % = Discount (bon marché).
 */
object PdZone {
    fun of(c: List<Candle>, price: Double, n: Int = 100): PdInfo? {
        if (c.size < 20) return null
        val from = max(0, c.size - n)
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (k in from until c.size) {
            hi = max(hi, c[k].high)
            lo = min(lo, c[k].low)
        }
        val range = hi - lo
        if (range <= 0.0) return null
        val pos = ((price - lo) / range).coerceIn(0.0, 1.0)
        val pct = (pos * 100).roundToInt()
        val zone = when {
            pos >= 0.55 -> "Premium"
            pos <= 0.45 -> "Discount"
            else -> "Équilibre"
        }
        return PdInfo(zone, pct)
    }
}
