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

enum class BbState(val letter: Char, val label: String) {
    EXTREME('E', "Extrême"),
    MHV('M', "MHV"),
    REENTRY('R', "Re-entry")
}

class TfState(val tf: Tf, val state: BbState, val dir: Direction)

class ComboMatch(val code: String, val dir: Direction, val states: List<TfState>)

/**
 * Combos BBMA sur 3 timeframes, lus du plus grand au plus petit.
 * R = Re-entry, E = Extrême, M = MHV. Codes : REE, RME, REM, RRE, RMM.
 * Les 3 timeframes doivent aller dans le même sens (tous vente ou tous achat).
 */
object Combos {
    /** Une seule série : D1 · H4 · H1 (H4 · H1 · M15 a été retirée). */
    val TRIPLES: List<List<Tf>> = listOf(
        listOf(Tf.D1, Tf.H4, Tf.H1)
    )

    private val VALID = setOf("REE", "RME", "REM", "RRE", "RMM")

    /** Un Extrême compte s'il date de la dernière bougie terminée ou de la précédente. */
    private const val EXTREME_MAX_AGE = 1
    private const val SL_LOOKBACK = 10

    /** Série de timeframes dont le plus petit est `small`, ou null s'il n'y en a pas. */
    fun tripleFor(small: Tf): List<Tf>? = TRIPLES.firstOrNull { it.last() == small }

    suspend fun scan(markets: List<Market>, triple: List<Tf>): List<Signal> = coroutineScope {
        val gate = Semaphore(4)
        markets.map { m ->
            async {
                gate.withPermit {
                    try {
                        check(m, triple)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun stateOf(tf: Tf, c: List<Candle>): TfState? {
        val b = BbmaCalc(c)
        val hit = b.detect()
        if (hit != null) {
            val st = if (hit.setup.contains("MHV")) BbState.MHV else BbState.REENTRY
            return TfState(tf, st, hit.dir)
        }
        val mk = b.marks()
        val ext = mk.extreme
        if (ext != null && mk.extremeAgo <= EXTREME_MAX_AGE) {
            return TfState(tf, BbState.EXTREME, ext)
        }
        return null
    }

    /**
     * `closed` contient, pour chaque timeframe de la série (du plus grand au plus petit),
     * les bougies déjà terminées.
     */
    fun match(closed: List<List<Candle>>, triple: List<Tf>): ComboMatch? {
        val states = arrayOfNulls<TfState>(triple.size)
        for (i in triple.indices.reversed()) {
            states[i] = stateOf(triple[i], closed[i]) ?: return null
        }
        val list = states.map { it!! }
        val dir = list[0].dir
        if (list.any { it.dir != dir }) return null
        val code = list.joinToString("") { it.state.letter.toString() }
        if (code !in VALID) return null
        return ComboMatch(code, dir, list)
    }

    private suspend fun check(m: Market, triple: List<Tf>): Signal? {
        val closed = ArrayList<List<Candle>>()
        var forming: Candle? = null
        for (tf in triple) {
            val raw = Deriv.candles(m.code, tf.seconds, 300)
            if (raw.size < 100) return null
            // On ignore la dernière bougie (encore en formation)
            closed.add(raw.dropLast(1))
            // À la fin de la boucle : bougie en cours du plus petit timeframe
            forming = raw.last()
        }
        val small = triple.last()
        val c = closed.last()
        val last = c.last()
        // Marché fermé (forex le week-end) : bougies trop anciennes
        val now = System.currentTimeMillis() / 1000
        if (now - last.time > small.seconds * 4L) return null

        val mt = match(closed, triple) ?: return null
        val sig = build(m, small, c, triple, mt) ?: return null
        // Signal périmé : TP1 ou SL déjà touché, ou entrée dépassée
        val f = forming
        return if (f != null && Fresh.stale(sig, f)) null else sig
    }

    fun build(m: Market, small: Tf, c: List<Candle>, triple: List<Tf>, mt: ComboMatch): Signal? {
        val dir = mt.dir
        val atr = Ind.atr(c)
        if (atr <= 0.0) return null
        val last = c.last()
        val entry = last.close
        val from = max(0, c.size - SL_LOOKBACK)
        var sl: Double
        if (dir == Direction.BUY) {
            var lo = Double.MAX_VALUE
            for (k in from until c.size) lo = min(lo, c[k].low)
            sl = lo - 0.3 * atr
        } else {
            var hi = -Double.MAX_VALUE
            for (k in from until c.size) hi = max(hi, c[k].high)
            sl = hi + 0.3 * atr
        }
        var risk = abs(entry - sl)
        if (risk > 4 * atr) return null
        if (risk < 0.5 * atr) {
            sl = if (dir == Direction.BUY) entry - 0.5 * atr else entry + 0.5 * atr
            risk = 0.5 * atr
        }
        val sign = if (dir == Direction.BUY) 1.0 else -1.0
        val names = triple.joinToString(" ") { it.label }
        val reasons = ArrayList<String>()
        reasons.add("Combo BBMA " + mt.code + " sur " + names + " : même sens sur les 3 timeframes")
        for (s in mt.states) {
            reasons.add(s.tf.label + " : " + s.state.label + (if (dir == Direction.BUY) " achat" else " vente"))
        }
        return Signal(
            m, small, dir, "Combo " + mt.code + " " + names,
            entry, sl, entry + sign * 1.5 * risk, entry + sign * 2.5 * risk,
            80, reasons, last.time
        )
    }
}
