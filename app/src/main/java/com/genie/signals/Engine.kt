package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Combine SMC, BBMA et Supply & Demand et produit un signal avec entrée, SL, TP et score. */
object Engine {

    private const val MIN_SCORE = 50

    private class Draft(
        val dir: Direction,
        val sl: Double,
        val score: Int,
        val setup: String,
        val reasons: List<String>,
        val target: Double?
    )

    private class Level(val price: Double, val touches: Int)

    fun analyse(m: Market, tf: Tf, raw: List<Candle>, htfRaw: List<Candle>? = null): Signal? {
        if (raw.size < 100) return null
        // On ignore la dernière bougie (encore en formation)
        val c = raw.dropLast(1)
        val last = c.last()
        // Marché fermé (forex le week-end) : bougies trop anciennes
        val now = System.currentTimeMillis() / 1000
        if (now - last.time > tf.seconds * 4L) return null

        val atr = Ind.atr(c)
        if (atr <= 0.0) return null

        val bbma = BbmaCalc(c)
        val bh = bbma.detect()
        val smc = SmcCalc(c).detect()
        val sd = SdCalc(c).detect(atr)
        val trend = bbma.trend()
        val entry = last.close

        val draft = (if (smc != null) fromSmc(smc, bh, sd, trend, atr, entry) else null)
            ?: (if (sd != null) fromSd(sd, bh, trend, atr, entry) else null)
            ?: (if (bh != null) fromBbma(bh, trend, atr, entry, c) else null)
            ?: return null

        val risk = abs(entry - draft.sl)
        val sign = if (draft.dir == Direction.BUY) 1.0 else -1.0
        var score = draft.score
        val reasons = draft.reasons.toMutableList()
        var target = draft.target

        // Multi-timeframe + support/résistance du timeframe supérieur
        if (htfRaw != null && htfRaw.size >= 100) {
            val h = htfRaw.dropLast(1)
            val atrH = Ind.atr(h)
            if (atrH > 0.0) {
                val wanted = if (draft.dir == Direction.BUY) 1 else -1
                val ht = BbmaCalc(h).trend()
                if (ht == wanted) {
                    score += 10
                    reasons.add("Tendance du timeframe supérieur dans le même sens (+10)")
                } else if (ht == -wanted) {
                    score -= 20
                    reasons.add("Attention : tendance du timeframe supérieur à contre-courant (-20)")
                }

                val lv = levels(h, atrH)

                // Rebond sur un niveau dans le bon sens
                val bounce = lv.any {
                    if (draft.dir == Direction.BUY)
                        it.price <= entry + 0.2 * atrH && entry - it.price <= 0.75 * atrH
                    else
                        it.price >= entry - 0.2 * atrH && it.price - entry <= 0.75 * atrH
                }
                if (bounce) {
                    score += 10
                    reasons.add(
                        if (draft.dir == Direction.BUY) "Entrée sur un support du timeframe supérieur (+10)"
                        else "Entrée sur une résistance du timeframe supérieur (+10)"
                    )
                }

                // Niveau opposé qui bloque le chemin vers TP1
                val blocked = lv.any {
                    val d = (it.price - entry) * sign
                    d > 0.2 * atrH && d < 1.5 * risk
                }
                if (blocked) {
                    score -= 10
                    reasons.add("Niveau du timeframe supérieur proche avant TP1 (-10)")
                }

                // Niveau suivant = cible TP2 (si le setup n'a pas déjà une cible)
                if (target == null) {
                    val ahead = lv
                        .filter { (it.price - entry) * sign >= 2.0 * risk }
                        .minByOrNull { abs(it.price - entry) }
                    if (ahead != null) {
                        target = ahead.price
                        reasons.add("TP2 placé sur le prochain niveau du timeframe supérieur")
                    }
                }
            }
        }

        if (score < MIN_SCORE) return null

        val tp1 = entry + sign * 1.5 * risk
        var tp2 = entry + sign * 2.5 * risk
        val t = target
        if (t != null && abs(t - entry) >= 2.0 * risk && (t - entry) * sign > 0) {
            tp2 = if (draft.dir == Direction.BUY) max(tp2, t) else min(tp2, t)
        }

        return Signal(
            market = m,
            tf = tf,
            dir = draft.dir,
            setup = draft.setup,
            entry = entry,
            sl = draft.sl,
            tp1 = tp1,
            tp2 = tp2,
            score = min(score, 100),
            reasons = reasons,
            time = last.time
        )
    }

    /** Points hauts/bas respectés au moins 2 fois, regroupés par proximité. */
    private fun levels(h: List<Candle>, atrH: Double): List<Level> {
        val n = 5
        val pts = mutableListOf<Double>()
        for (i in n until h.size - n) {
            var isHigh = true
            var isLow = true
            for (k in 1..n) {
                if (h[i].high <= h[i - k].high || h[i].high <= h[i + k].high) isHigh = false
                if (h[i].low >= h[i - k].low || h[i].low >= h[i + k].low) isLow = false
            }
            if (isHigh) pts.add(h[i].high)
            if (isLow) pts.add(h[i].low)
        }
        pts.sort()

        val out = mutableListOf<Level>()
        var group = mutableListOf<Double>()
        fun flush() {
            if (group.size >= 2) out.add(Level(group.average(), group.size))
            group = mutableListOf()
        }
        for (p in pts) {
            if (group.isNotEmpty() && p - group.last() > 0.3 * atrH) flush()
            group.add(p)
        }
        flush()
        return out
    }

    private fun fromSmc(smc: SmcHit, bh: BbmaHit?, sd: SdHit?, trend: Int, atr: Double, entry: Double): Draft? {
        val z = smc.zone
        var sl = if (smc.dir == Direction.BUY) z.low - 0.3 * atr else z.high + 0.3 * atr
        var risk = abs(entry - sl)
        if (risk > 4 * atr) return null
        if (risk < 0.5 * atr) {
            sl = if (smc.dir == Direction.BUY) entry - 0.5 * atr else entry + 0.5 * atr
            risk = 0.5 * atr
        }

        var score = 50
        val reasons = mutableListOf<String>()
        reasons.addAll(smc.reasons)
        score += if (smc.kind == "CHoCH") 15 else 8
        if (smc.sweep) score += 10
        val wanted = if (smc.dir == Direction.BUY) 1 else -1
        if (trend == wanted) {
            score += 5
            reasons.add("EMA50 dans le sens du signal")
        }
        if (bh != null) {
            if (bh.dir == smc.dir) {
                score += 20
                reasons.addAll(bh.reasons)
                reasons.add("Confluence SMC + BBMA")
            } else {
                score -= 15
                reasons.add("Attention : BBMA indique la direction opposée")
            }
        }
        if (sd != null) {
            if (sd.dir == smc.dir) {
                score += 15
                reasons.addAll(sd.reasons)
                reasons.add("Confluence SMC + Supply & Demand")
            } else {
                score -= 10
                reasons.add("Attention : zone Supply & Demand de direction opposée")
            }
        }
        val setup = "SMC " + smc.kind + " + " + smc.zoneType + (if (bh != null && bh.dir == smc.dir) " + " + bh.setup else "")
        return Draft(smc.dir, sl, score, setup, reasons, smc.target)
    }

    private fun fromSd(sd: SdHit, bh: BbmaHit?, trend: Int, atr: Double, entry: Double): Draft? {
        var sl = if (sd.dir == Direction.BUY) sd.low - 0.3 * atr else sd.high + 0.3 * atr
        var risk = abs(entry - sl)
        if (risk > 4 * atr) return null
        if (risk < 0.5 * atr) {
            sl = if (sd.dir == Direction.BUY) entry - 0.5 * atr else entry + 0.5 * atr
            risk = 0.5 * atr
        }

        var score = 45 + min(15, max(0, ((sd.strength - 1.5) * 6).toInt()))
        val reasons = mutableListOf<String>()
        reasons.addAll(sd.reasons)
        val wanted = if (sd.dir == Direction.BUY) 1 else -1
        if (trend == wanted) {
            score += 5
            reasons.add("EMA50 dans le sens du signal")
        }
        if (bh != null) {
            if (bh.dir == sd.dir) {
                score += 20
                reasons.addAll(bh.reasons)
                reasons.add("Confluence Supply & Demand + BBMA")
            } else {
                score -= 15
                reasons.add("Attention : BBMA indique la direction opposée")
            }
        }
        return Draft(sd.dir, sl, score, "Supply & Demand", reasons, null)
    }

    private fun fromBbma(bh: BbmaHit, trend: Int, atr: Double, entry: Double, c: List<Candle>): Draft? {
        val e = bh.extremeIdx
        var sl: Double
        if (bh.dir == Direction.BUY) {
            var lo = Double.MAX_VALUE
            for (k in e until c.size) lo = min(lo, c[k].low)
            sl = lo - 0.3 * atr
        } else {
            var hi = -Double.MAX_VALUE
            for (k in e until c.size) hi = max(hi, c[k].high)
            sl = hi + 0.3 * atr
        }
        var risk = abs(entry - sl)
        if (risk > 4 * atr) return null
        if (risk < 0.5 * atr) {
            sl = if (bh.dir == Direction.BUY) entry - 0.5 * atr else entry + 0.5 * atr
            risk = 0.5 * atr
        }

        var score = 40 + 5
        val reasons = mutableListOf<String>()
        reasons.addAll(bh.reasons)
        val wanted = if (bh.dir == Direction.BUY) 1 else -1
        if (trend == wanted) {
            score += 10
            reasons.add("EMA50 dans le sens du signal")
        } else {
            reasons.add("Signal contre la tendance EMA50 : prudence")
        }
        return Draft(bh.dir, sl, score, bh.setup, reasons, null)
    }
}
