package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BbmaHit(
    val setup: String,
    val dir: Direction,
    val extremeIdx: Int,
    val reasons: List<String>,
    val sub: String = ""        // pour Reentry : "Re-entry MA", "Re-entry Mid" ou "ZZL"
)

/**
 * Type de Re-entry sur la bougie i (après un Extrême e), pour une vente (sell) ou un achat.
 * 0 = aucun, 1 = Re-entry MA5/10 (la mèche coupe une MA5/10 high ou low),
 * 2 = Re-entry Mid BB (la mèche coupe la bande médiane), 3 = ZZL (les deux sur la même bougie).
 * Dans tous les cas la bougie clôture du bon côté (rejet) et le prix a déjà passé la bande médiane après l'Extrême.
 */
fun bbmaReentryKind(
    c: List<Candle>, mid: DoubleArray,
    m5h: DoubleArray, m10h: DoubleArray, m5l: DoubleArray, m10l: DoubleArray,
    i: Int, e: Int, sell: Boolean
): Int {
    if (e < 0 || e >= i || mid[i].isNaN()) return 0
    var went = false
    for (k in e + 1 until i) {
        if (sell && c[k].close < mid[k]) went = true
        if (!sell && c[k].close > mid[k]) went = true
    }
    if (!went) return 0
    val cur = c[i]
    val lines = doubleArrayOf(m5h[i], m10h[i], m5l[i], m10l[i])
    var maCut = false
    for (l in lines) {
        if (l.isNaN()) continue
        if (sell && cur.high >= l && cur.close < l) maCut = true
        if (!sell && cur.low <= l && cur.close > l) maCut = true
    }
    val midCut = if (sell) cur.high >= mid[i] && cur.close < mid[i]
    else cur.low <= mid[i] && cur.close > mid[i]
    return when {
        maCut && midCut -> 3
        midCut -> 2
        maCut -> 1
        else -> 0
    }
}

/** Re-entry en attente : niveau (zone) vers lequel le prix revient, pas encore touché. kind : 1 = MA5/10, 2 = Mid BB, 3 = ZZL. */
class BbmaPending(val dir: Direction, val kind: Int, val low: Double, val high: Double, val extremeIdx: Int)

fun bbmaPendingName(kind: Int): String = when (kind) {
    3 -> "ZZL"
    2 -> "Re-entry Mid"
    else -> "Re-entry MA"
}

private fun reentrySub(k: Int): String = when (k) {
    3 -> "ZZL"
    2 -> "Re-entry Mid"
    else -> "Re-entry MA"
}

/** Repères BBMA du moment : Extrême le plus récent et type de la dernière bougie. */
data class BbmaMarks(
    val extreme: Direction?,    // SELL = extrême haut, BUY = extrême bas
    val extremeAgo: Int,        // nombre de bougies depuis l'extrême
    val candle: String?,        // "CSM", "CSAK" ou "CSA"
    val candleDir: Direction?   // BUY = haussière, SELL = baissière
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
    private val ma10h = Ind.wma(highs, 10)
    private val ma10l = Ind.wma(lows, 10)
    private val ema50 = Ind.ema(closes, 50)

    /** 1 = tendance haussière (EMA50 monte), -1 = baissière, 0 = indéterminé. */
    fun trend(): Int {
        val i = c.lastIndex
        if (i < 56 || ema50[i].isNaN() || ema50[i - 5].isNaN()) return 0
        return if (ema50[i] > ema50[i - 5]) 1 else -1
    }

    /**
     * Repères affichés dans la carte du signal.
     * CSM  : la bougie clôture hors de la bande de Bollinger haute (ou basse).
     * CSAK : la bougie clôture au-delà des MA5/10 high (ou low) et aussi au-delà de la bande médiane.
     * CSA  : la bougie clôture au-delà des MA5/10 high (ou low).
     */
    fun marks(): BbmaMarks {
        val i = c.lastIndex
        if (i < 56) return BbmaMarks(null, 0, null, null)

        var ext: Direction? = null
        var ago = 0
        for (k in i downTo max(i - 15, 0)) {
            if (ma5h[k] > up[k]) {
                ext = Direction.SELL
                ago = i - k
                break
            }
            if (ma5l[k] < low[k]) {
                ext = Direction.BUY
                ago = i - k
                break
            }
        }

        var name: String? = null
        var dir: Direction? = null
        val cl = c[i].close
        val maHigh = max(ma5h[i], ma10h[i])
        val maLow = min(ma5l[i], ma10l[i])
        if (cl > up[i]) {
            name = "CSM"
            dir = Direction.BUY
        } else if (cl < low[i]) {
            name = "CSM"
            dir = Direction.SELL
        } else if (cl > maHigh) {
            dir = Direction.BUY
            name = if (cl > mid[i]) "CSAK" else "CSA"
        } else if (cl < maLow) {
            dir = Direction.SELL
            name = if (cl < mid[i]) "CSAK" else "CSA"
        }
        return BbmaMarks(ext, ago, name, dir)
    }

    /**
     * Re-entry en attente sur la dernière bougie terminée : après un Extrême, le prix a déjà repassé la bande médiane
     * et revient vers la MA5/10, la bande médiane ou les deux (ZZL), sans les avoir touchées (pas de Re-entry
     * sur les 3 dernières bougies). Le niveau doit être à moins de 0,8 ATR du prix.
     */
    fun pending(): BbmaPending? {
        val i = c.lastIndex
        if (i < 56) return null
        var e = -1
        var dir: Direction? = null
        for (k in i downTo max(i - 15, 0)) {
            if (ma5h[k] > up[k]) { e = k; dir = Direction.SELL; break }
            if (ma5l[k] < low[k]) { e = k; dir = Direction.BUY; break }
        }
        if (e == -1 || dir == null || e == i) return null
        val sell = dir == Direction.SELL
        var went = false
        for (k in e + 1..i) {
            if (sell && c[k].close < mid[k]) went = true
            if (!sell && c[k].close > mid[k]) went = true
        }
        if (!went) return null
        // Déjà touché récemment : ce n'est plus en attente
        for (k in max(e + 1, i - 3)..i) {
            if (bbmaReentryKind(c, mid, ma5h, ma10h, ma5l, ma10l, k, e, sell) > 0) return null
        }
        val cur = c[i]
        val atr = Ind.atr(c)
        if (atr <= 0.0) return null
        val levels = ArrayList<Pair<Double, Boolean>>()
        if (!mid[i].isNaN()) levels.add(Pair(mid[i], true))
        for (v in doubleArrayOf(ma5h[i], ma10h[i], ma5l[i], ma10l[i])) {
            if (!v.isNaN()) levels.add(Pair(v, false))
        }
        val cand = levels.filter { if (sell) it.first > cur.high else it.first < cur.low }
        if (cand.isEmpty()) return null
        val near = if (sell) cand.minByOrNull { it.first }!! else cand.maxByOrNull { it.first }!!
        if (abs(near.first - cur.close) > 0.8 * atr) return null
        val cluster = cand.filter { abs(it.first - near.first) <= 0.3 * atr }
        val hasMid = cluster.any { it.second }
        val hasMa = cluster.any { !it.second }
        val kind = if (hasMid && hasMa) 3 else if (hasMid) 2 else 1
        return BbmaPending(dir, kind, cluster.minOf { it.first }, cluster.maxOf { it.first }, e)
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
            // Reentry : MA5/10, bande médiane ou les deux (ZZL), avec rejet
            val rk = bbmaReentryKind(c, mid, ma5h, ma10h, ma5l, ma10l, i, e, true)
            if (rk > 0) {
                return BbmaHit(
                    "BBMA Reentry", Direction.SELL, e,
                    listOf("Extreme vente puis " + reentrySub(rk) + " : la mèche coupe " +
                        (if (rk == 1) "la MA5/10" else if (rk == 2) "la bande médiane" else "la MA5/10 et la bande médiane") +
                        " puis la bougie clôture en dessous (rejet)"),
                    reentrySub(rk)
                )
            }
        } else {
            if (cur.low < low[i] && ma5l[i] >= low[i] && cur.close > low[i]) {
                return BbmaHit(
                    "BBMA MHV", Direction.BUY, e,
                    listOf("Extreme achat puis MHV : le prix retouche la bande basse sans la dépasser avec la MA5")
                )
            }
            val rk = bbmaReentryKind(c, mid, ma5h, ma10h, ma5l, ma10l, i, e, false)
            if (rk > 0) {
                return BbmaHit(
                    "BBMA Reentry", Direction.BUY, e,
                    listOf("Extreme achat puis " + reentrySub(rk) + " : la mèche coupe " +
                        (if (rk == 1) "la MA5/10" else if (rk == 2) "la bande médiane" else "la MA5/10 et la bande médiane") +
                        " puis la bougie clôture au-dessus (rejet)"),
                    reentrySub(rk)
                )
            }
        }
        return null
    }
}
