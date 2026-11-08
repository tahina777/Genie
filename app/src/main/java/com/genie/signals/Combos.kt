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

data class BtGroup(
    val name: String,
    val trades: Int,
    val wins: Int,
    val avgR: Double,
    val totalR: Double = 0.0,
    val profitFactor: Double = 0.0,
    val maxDd: Double = 0.0
) {
    val winRate: Double
        get() = if (trades == 0) 0.0 else wins * 100.0 / trades
}

data class BtResult(
    val total: BtGroup,
    val bySetup: List<BtGroup>,
    val byScore: List<BtGroup>,
    val markets: Int,
    val failed: Int,
    val byMarket: List<BtGroup> = emptyList()
)

internal class Trade(
    val family: String,
    val bucket: String,
    val r: Double,
    val market: String = "",
    val time: Long = 0L
)

/**
 * Backtest "pas à pas" : à chaque bougie, Genie ne voit que le passé, comme en direct.
 * Le timeframe supérieur (tendance + niveaux) est lui aussi limité aux bougies déjà terminées.
 * Un trade gagne +1,5 R (TP1) ou perd -1 R (SL). Si SL et TP sont touchés dans la même
 * bougie, on compte la perte (hypothèse prudente). Sans résultat après 60 bougies,
 * le trade est clôturé au dernier prix. Spread et commissions ne sont pas inclus.
 *
 * Les combos BBMA (REE, RME, REM, RRE, RMM) sont testés en M15 (H4 · H1 · M15) et en H1
 * (D1 · H4 · H1). Ils sont comptés à part : ils n'entrent pas dans le total ni dans les scores.
 */
object Backtest {

    private const val WINDOW = 300
    private const val START = 120
    private const val MAX_HOLD = 60

    /** Même correspondance que Scanner.higherSeconds : à garder identique. */
    private fun higherSeconds(s: Int): Int? = when {
        s <= 300 -> 3600
        s <= 900 -> 14400
        s <= 3600 -> 14400
        s <= 14400 -> 86400
        else -> null
    }

    suspend fun run(markets: List<Market>, tf: Tf): BtResult = coroutineScope {
        val failed = AtomicInteger(0)
        val gate = Semaphore(3)
        val hs = higherSeconds(tf.seconds)
        val triple = Combos.tripleFor(tf)
        val lists = markets.map { m ->
            async {
                gate.withPermit {
                    try {
                        val cache = HashMap<Int, List<Candle>>()
                        val candles = Deriv.candles(m.code, tf.seconds, 1000)
                        cache[tf.seconds] = candles
                        val htf = if (hs == null) null else try {
                            Deriv.candles(m.code, hs, 1000)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        if (hs != null && htf != null) cache[hs] = htf

                        var combos: List<Trade> = emptyList()
                        if (triple != null) {
                            val series = ArrayList<List<Candle>>()
                            for (t in triple) {
                                var s = cache[t.seconds]
                                if (s == null) {
                                    s = try {
                                        Deriv.candles(m.code, t.seconds, 1000)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (s != null) cache[t.seconds] = s
                                }
                                if (s == null) {
                                    series.clear()
                                    break
                                }
                                series.add(s)
                            }
                            if (series.size == triple.size) {
                                combos = withContext(Dispatchers.Default) {
                                    simulateCombo(m, tf, triple, series)
                                }
                            }
                        }
                        val engine = withContext(Dispatchers.Default) {
                            simulate(m, tf, candles, htf, hs)
                        }
                        Pair(engine, combos)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                        Pair(emptyList<Trade>(), emptyList<Trade>())
                    }
                }
            }
        }.awaitAll()

        val all = lists.flatMap { it.first }
        val comboAll = lists.flatMap { it.second }
        val total = group("Tous les signaux", all)

        val families = listOf("SMC + BBMA (confluence)", "SMC seul", "BBMA MHV", "BBMA Reentry", "Supply & Demand")
        val comboFamilies = listOf("Combo REE", "Combo RME", "Combo REM", "Combo RRE", "Combo RMM")
        val bySetup = (
            families.map { f -> group(f, all.filter { it.family == f }) } +
                comboFamilies.map { f -> group(f, comboAll.filter { it.family == f }) }
            ).filter { it.trades > 0 }

        val buckets = listOf("Score 80 et plus", "Score 65 à 79", "Score 50 à 64")
        val byScore = buckets
            .map { b -> group(b, all.filter { it.bucket == b }) }
            .filter { it.trades > 0 }

        val byMarket = all.groupBy { it.market }
            .map { (k, v) -> group(k, v) }
            .sortedByDescending { it.totalR }

        BtResult(total, bySetup, byScore, markets.size, failed.get(), byMarket)
    }

    private fun group(name: String, t: List<Trade>): BtGroup {
        val wins = t.count { it.r > 0 }
        val sum = t.sumOf { it.r }
        val avg = if (t.isEmpty()) 0.0 else sum / t.size
        val gain = t.filter { it.r > 0 }.sumOf { it.r }
        val loss = -t.filter { it.r < 0 }.sumOf { it.r }
        val pf = if (loss == 0.0) (if (gain > 0) 99.0 else 0.0) else gain / loss
        var eq = 0.0
        var peak = 0.0
        var dd = 0.0
        for (x in t.sortedBy { it.time }) {
            eq += x.r
            if (eq > peak) peak = eq
            if (peak - eq > dd) dd = peak - eq
        }
        return BtGroup(name, t.size, wins, avg, sum, pf, dd)
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

    /** Nombre de bougies déjà terminées à l'instant t (la liste est triée par heure). */
    private fun countClosed(s: List<Candle>, sec: Int, t: Long): Int {
        var lo = 0
        var hi = s.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (s[mid].time + sec <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Bougies du timeframe supérieur déjà terminées à l'instant t, plus une copie de la dernière
     * en fin de liste (le moteur ignore la dernière bougie, supposée en formation).
     */
    private fun htfSlice(htf: List<Candle>, hs: Int, t: Long): List<Candle>? {
        val n = countClosed(htf, hs, t)
        if (n < 2) return null
        val from = max(0, n - WINDOW)
        val out = ArrayList<Candle>(n - from + 1)
        for (k in from until n) out.add(htf[k])
        out.add(htf[n - 1])
        return out
    }

    /** Résultat d'un trade entré à la clôture de la bougie i : (R, indice de la bougie de sortie). */
    private fun outcome(c: List<Candle>, i: Int, s: Signal, risk: Double): Pair<Double, Int> {
        val buy = s.dir == Direction.BUY
        var j = i + 1
        while (j <= i + MAX_HOLD) {
            val k = c[j]
            val hitSl = if (buy) k.low <= s.sl else k.high >= s.sl
            val hitTp = if (buy) k.high >= s.tp1 else k.low <= s.tp1
            if (hitSl) return Pair(-1.0, j)
            if (hitTp) return Pair(1.5, j)
            j++
        }
        val end = i + MAX_HOLD
        val sign = if (buy) 1.0 else -1.0
        return Pair((c[end].close - s.entry) * sign / risk, end)
    }

    private fun simulate(m: Market, tf: Tf, c: List<Candle>, htf: List<Candle>?, hs: Int?): List<Trade> {
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
            val h = if (htf == null || hs == null) null else htfSlice(htf, hs, c[i + 1].time)
            val s = Engine.analyse(m, tf, slice, h)
            if (s == null) {
                i++
                continue
            }
            val risk = abs(s.entry - s.sl)
            if (risk <= 0.0) {
                i++
                continue
            }
            val (r, end) = outcome(c, i, s, risk)
            out.add(Trade(family(s.setup), bucket(s.score), r, m.code, c[i].time))
            i = end + 1
        }
        return out
    }

    /** Combos BBMA : à chaque bougie du plus petit timeframe, on regarde les 3 timeframes. */
    private fun simulateCombo(m: Market, tf: Tf, triple: List<Tf>, series: List<List<Candle>>): List<Trade> {
        val out = ArrayList<Trade>()
        val c = series.last()
        var i = START
        while (i < c.size - MAX_HOLD - 2) {
            // La bougie i vient de se terminer à cet instant
            val t = c[i].time + tf.seconds
            val closed = ArrayList<List<Candle>>(triple.size)
            var ok = true
            for (k in triple.indices) {
                val s = series[k]
                val n = if (k == triple.lastIndex) i + 1 else countClosed(s, triple[k].seconds, t)
                if (n < 100) {
                    ok = false
                    break
                }
                closed.add(s.subList(max(0, n - 299), n))
            }
            if (!ok) {
                i++
                continue
            }
            val mt = Combos.match(closed, triple)
            if (mt == null) {
                i++
                continue
            }
            val sig = Combos.build(m, tf, closed.last(), triple, mt)
            if (sig == null) {
                i++
                continue
            }
            val risk = abs(sig.entry - sig.sl)
            if (risk <= 0.0) {
                i++
                continue
            }
            val (r, end) = outcome(c, i, sig, risk)
            out.add(Trade("Combo " + mt.code, "Combo", r, m.code, c[i].time))
            i = end + 1
        }
        return out
    }
}
