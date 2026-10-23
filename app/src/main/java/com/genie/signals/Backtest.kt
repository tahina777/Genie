package com.genie.signals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

data class BtGroup(val name: String, val trades: Int, val wins: Int, val avgR: Double) {
    val winRate: Double
        get() = if (trades == 0) 0.0 else wins * 100.0 / trades
}

data class BtResult(
    val total: BtGroup,
    val bySetup: List<BtGroup>,
    val byScore: List<BtGroup>,
    val markets: Int,
    val failed: Int
)

internal class Trade(val family: String, val bucket: String, val r: Double)

/**
 * Backtest "pas à pas" : à chaque bougie, Genie ne voit que le passé, comme en direct.
 * Un trade gagne +1,5 R (TP1) ou perd -1 R (SL). Si SL et TP sont touchés dans la même
 * bougie, on compte la perte (hypothèse prudente). Sans résultat après 60 bougies,
 * le trade est clôturé au dernier prix. Spread et commissions ne sont pas inclus.
 */
object Backtest {
    private const val WINDOW = 300
    private const val START = 120
    private const val MAX_HOLD = 60

    suspend fun run(markets: List<Market>, tf: Tf): BtResult = coroutineScope {
        val failed = AtomicInteger(0)
        val gate = Semaphore(3)
        val lists = markets.map { m ->
            async {
                gate.withPermit {
                    try {
                        val candles = Deriv.candles(m.code, tf.seconds, 1000)
                        withContext(Dispatchers.Default) { simulate(m, tf, candles) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                        emptyList<Trade>()
                    }
                }
            }
        }.awaitAll()
        val all = lists.flatten()

        val total = group("Tous les signaux", all)
        val families = listOf("SMC + BBMA (confluence)", "SMC seul", "BBMA MHV", "BBMA Reentry")
        val bySetup = families
            .map { f -> group(f, all.filter { it.family == f }) }
            .filter { it.trades > 0 }
        val buckets = listOf("Score 80 et plus", "Score 65 à 79", "Score 50 à 64")
        val byScore = buckets
            .map { b -> group(b, all.filter { it.bucket == b }) }
            .filter { it.trades > 0 }
        BtResult(total, bySetup, byScore, markets.size, failed.get())
    }

    private fun group(name: String, t: List<Trade>): BtGroup {
        val wins = t.count { it.r > 0 }
        val avg = if (t.isEmpty()) 0.0 else t.sumOf { it.r } / t.size
        return BtGroup(name, t.size, wins, avg)
    }

    private fun family(setup: String): String = when {
        setup.startsWith("SMC") && setup.contains("BBMA") -> "SMC + BBMA (confluence)"
        setup.startsWith("SMC") -> "SMC seul"
        else -> setup
    }

    private fun bucket(score: Int): String = when {
        score >= 80 -> "Score 80 et plus"
        score >= 65 -> "Score 65 à 79"
        else -> "Score 50 à 64"
    }

    private fun simulate(m: Market, tf: Tf, c: List<Candle>): List<Trade> {
        val out = ArrayList<Trade>()
        val now = System.currentTimeMillis() / 1000
        var i = START
        while (i < c.size - MAX_HOLD - 2) {
            val from = max(0, i - WINDOW + 1)
            // Décale les heures pour que la bougie i paraisse récente (le moteur ignore les marchés "fermés")
            val delta = now - c[i].time - tf.seconds
            val slice = ArrayList<Candle>(i - from + 2)
            for (k in from..i + 1) {
                val x = c[k]
                slice.add(Candle(x.time + delta, x.open, x.high, x.low, x.close))
            }
            val s = Engine.analyse(m, tf, slice)
            if (s == null) {
                i++
                continue
            }
            val risk = abs(s.entry - s.sl)
            if (risk <= 0.0) {
                i++
                continue
            }
            val buy = s.dir == Direction.BUY
            var r = Double.NaN
            var end = i
            var j = i + 1
            while (j <= i + MAX_HOLD) {
                val k = c[j]
                val hitSl = if (buy) k.low <= s.sl else k.high >= s.sl
                val hitTp = if (buy) k.high >= s.tp1 else k.low <= s.tp1
                if (hitSl) {
                    r = -1.0
                    end = j
                    break
                }
                if (hitTp) {
                    r = 1.5
                    end = j
                    break
                }
                j++
            }
            if (r.isNaN()) {
                end = i + MAX_HOLD
                val sign = if (buy) 1.0 else -1.0
                r = (c[end].close - s.entry) * sign / risk
            }
            out.add(Trade(family(s.setup), bucket(s.score), r))
            i = end + 1
        }
        return out
    }
}
