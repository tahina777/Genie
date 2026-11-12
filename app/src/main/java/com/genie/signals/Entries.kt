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

/** Une entrée limite : zone, prix d'entrée au bord de la zone, stop et cibles. */
class EntryLevel(
    val buy: Boolean,
    val kind: String,
    val zoneLow: Double,
    val zoneHigh: Double,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val dist: Double,
    val distAtr: Double,
    val ago: Int
)

class PendingEntry(
    val market: Market,
    val tf: Tf,
    val level: EntryLevel,
    val price: Double,
    val time: Long
) {
    /** Pour réutiliser le calculateur de lot. */
    fun toSignal(): Signal {
        val dir = if (level.buy) Direction.BUY else Direction.SELL
        return Signal(
            market, tf, dir, level.kind + " (entrée limite)",
            level.entry, level.sl, level.tp1, level.tp2, 0,
            emptyList(), time
        )
    }
}

private class Cand(
    val low: Double,
    val high: Double,
    val buy: Boolean,
    val kind: String,
    val idx: Int,
    val start: Int
)

/**
 * Entrées en attente (ordres limites) : zones fraîches, jamais touchées, autour du prix actuel.
 * Types : Demande / Offre (bougie de base puis impulsion), Order block, FVG non comblé.
 * Pour chaque côté, on garde la zone la plus proche du prix (à 6 ATR au plus).
 */
object Entries {
    private const val LOOKBACK = 150
    private const val MAX_DIST_ATR = 6.0

    /** `all` contient les bougies, la dernière étant encore en formation. */
    fun compute(all: List<Candle>): List<EntryLevel> {
        val n = all.size
        if (n < 60) return emptyList()
        val closedN = n - 1
        val closed = all.subList(0, closedN)
        val atr = Ind.atr(closed)
        if (atr <= 0.0) return emptyList()
        val price = all[n - 1].close
        val cands = ArrayList<Cand>()

        // Demande / Offre et Order block : bougie suivie d'une impulsion d'au moins 1,5 ATR
        val from = max(3, closedN - LOOKBACK)
        for (i in from..(closedN - 5)) {
            val b = all[i]
            val body = abs(b.close - b.open)
            val range = b.high - b.low
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (k in (i + 1)..(i + 3)) {
                hi = max(hi, all[k].high)
                lo = min(lo, all[k].low)
            }
            val up = (hi - b.high) / atr
            val down = (b.low - lo) / atr
            val base = body <= 0.6 * atr && range <= 1.2 * atr
            if (up >= 1.5 && all[i + 1].close > b.high && up >= down) {
                if (base) {
                    cands.add(Cand(b.low, b.high, true, "Demande", i, i + 2))
                } else if (b.close < b.open) {
                    cands.add(Cand(b.low, b.high, true, "Order block", i, i + 2))
                }
            } else if (down >= 1.5 && all[i + 1].close < b.low) {
                if (base) {
                    cands.add(Cand(b.low, b.high, false, "Offre", i, i + 2))
                } else if (b.close > b.open) {
                    cands.add(Cand(b.low, b.high, false, "Order block", i, i + 2))
                }
            }
        }

        // FVG : déséquilibre entre la bougie i-1 et la bougie i+1
        for (i in max(2, closedN - LOOKBACK)..(closedN - 2)) {
            val a = all[i - 1]
            val z = all[i + 1]
            if (a.high < z.low && z.low - a.high >= 0.3 * atr) {
                cands.add(Cand(a.high, z.low, true, "FVG", i, i + 2))
            } else if (a.low > z.high && a.low - z.high >= 0.3 * atr) {
                cands.add(Cand(z.high, a.low, false, "FVG", i, i + 2))
            }
        }

        var bestBuy: EntryLevel? = null
        var bestSell: EntryLevel? = null
        for (cd in cands) {
            // Zone fraîche : jamais touchée depuis sa formation
            var fresh = true
            for (j in cd.start until n) {
                if (cd.buy) {
                    if (all[j].low <= cd.high) {
                        fresh = false
                        break
                    }
                } else {
                    if (all[j].high >= cd.low) {
                        fresh = false
                        break
                    }
                }
            }
            if (!fresh) continue
            if (cd.buy && cd.high >= price) continue
            if (!cd.buy && cd.low <= price) continue

            val entry = if (cd.buy) cd.high else cd.low
            var sl = if (cd.buy) cd.low - 0.3 * atr else cd.high + 0.3 * atr
            var risk = abs(entry - sl)
            if (risk > 4 * atr) continue
            if (risk < 0.5 * atr) {
                sl = if (cd.buy) entry - 0.5 * atr else entry + 0.5 * atr
                risk = 0.5 * atr
            }
            val dist = abs(price - entry)
            if (dist / atr > MAX_DIST_ATR) continue

            val sign = if (cd.buy) 1.0 else -1.0
            val lvl = EntryLevel(
                cd.buy, cd.kind, cd.low, cd.high, entry, sl,
                entry + sign * 1.5 * risk, entry + sign * 2.5 * risk,
                dist, dist / atr, closedN - cd.idx
            )
            if (cd.buy) {
                val cur = bestBuy
                if (cur == null || lvl.dist < cur.dist) bestBuy = lvl
            } else {
                val cur = bestSell
                if (cur == null || lvl.dist < cur.dist) bestSell = lvl
            }
        }
        return listOfNotNull(bestBuy, bestSell).sortedBy { it.dist }
    }

    suspend fun scan(markets: List<Market>, tf: Tf): List<PendingEntry> = coroutineScope {
        val gate = Semaphore(4)
        markets.map { m ->
            async {
                gate.withPermit {
                    try {
                        val all = Deriv.candles(m.code, tf.seconds, 300)
                        val last = all.lastOrNull()
                        val now = System.currentTimeMillis() / 1000
                        val out: List<PendingEntry> =
                            if (last == null || all.size < 100 || now - last.time > tf.seconds * 4L) {
                                emptyList()
                            } else {
                                compute(all).map { PendingEntry(m, tf, it, last.close, last.time) }
                            }
                        out
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList<PendingEntry>()
                    }
                }
            }
        }.awaitAll().flatten().sortedBy { it.level.distAtr }
    }
}
