package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object Ind {

    fun sma(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= n) sum -= v[i - n]
            if (i >= n - 1) out[i] = sum / n
        }
        return out
    }

    /** Moyenne mobile pondérée linéaire (utilisée dans BBMA pour MA5/MA10). */
    fun wma(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        val den = n * (n + 1) / 2.0
        for (i in n - 1 until v.size) {
            var s = 0.0
            for (k in 0 until n) s += v[i - n + 1 + k] * (k + 1)
            out[i] = s / den
        }
        return out
    }

    fun ema(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (v.size < n) return out
        val k = 2.0 / (n + 1)
        var prev = 0.0
        for (i in 0 until n) prev += v[i]
        prev /= n
        out[n - 1] = prev
        for (i in n until v.size) {
            prev = v[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    class Bands(val mid: DoubleArray, val up: DoubleArray, val low: DoubleArray)

    fun bollinger(v: DoubleArray, n: Int = 20, mult: Double = 2.0): Bands {
        val mid = sma(v, n)
        val up = DoubleArray(v.size) { Double.NaN }
        val low = DoubleArray(v.size) { Double.NaN }
        for (i in n - 1 until v.size) {
            var s = 0.0
            for (k in i - n + 1..i) {
                val d = v[k] - mid[i]
                s += d * d
            }
            val sd = sqrt(s / n)
            up[i] = mid[i] + mult * sd
            low[i] = mid[i] - mult * sd
        }
        return Bands(mid, up, low)
    }

    fun atr(c: List<Candle>, n: Int = 14): Double {
        if (c.size < n + 1) return 0.0
        var sum = 0.0
        for (i in c.size - n until c.size) {
            val prevClose = c[i - 1].close
            val tr = max(
                c[i].high - c[i].low,
                max(abs(c[i].high - prevClose), abs(c[i].low - prevClose))
            )
            sum += tr
        }
        return sum / n
    }
}
