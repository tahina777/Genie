package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Niveau de support ou de résistance : prix et nombre de fois où il a été respecté. */
internal class SrLevel(val price: Double, val touches: Int)

/** Zone de Supply (offre) ou Demand (demande). tested = le prix l'a déjà retouchée sans la casser. */
internal class Swing(val i: Int, val price: Double, val high: Boolean, val label: String)

/** Sommets et creux (fractals de 3 bougies de chaque côté), étiquetés par rapport au précédent du même type. */
internal fun buildSwings(c: List<Candle>): List<Swing> {
    val k = 3
    val out = ArrayList<Swing>()
    var prevH: Double? = null
    var prevL: Double? = null
    for (i in k until c.size - k) {
        var h = true
        var l = true
        for (j in 1..k) {
            if (c[i].high <= c[i - j].high || c[i].high < c[i + j].high) h = false
            if (c[i].low >= c[i - j].low || c[i].low > c[i + j].low) l = false
        }
        if (h) {
            val p = prevH
            out.add(Swing(i, c[i].high, true, if (p == null) "H" else if (c[i].high > p) "HH" else "LH"))
            prevH = c[i].high
        }
        if (l) {
            val p = prevL
            out.add(Swing(i, c[i].low, false, if (p == null) "L" else if (c[i].low > p) "HL" else "LL"))
            prevL = c[i].low
        }
    }
    return out
}

internal class SdBox(val low: Double, val high: Double, val buy: Boolean, val startIdx: Int, val tested: Boolean)

/** Profil temps/prix à plage fixe : poids de chaque tranche de prix, POC et zone de valeur. */
internal class VpResult(
    val lo: Double,
    val step: Double,
    val weights: DoubleArray,
    val poc: Int,
    val vaLow: Int,
    val vaHigh: Int,
    val fromIdx: Int
)

/**
 * Marque sur une bougie. kind :
 * E = Extrême, M = MHV, R = Re-entry Mid, N = Re-entry MA5/10, Z = ZZL, 1 = CSM, 2 = CSAK, 3 = CSA, S = sweep de liquidité,
 * G = Engulfing, P = Pin bar, D = Doji, I = Inside bar, T = Étoile du matin/soir, W = Double sommet/creux.
 * buy = true : marque haussière, dessinée sous la bougie ; false : au-dessus.
 */
internal class Mark(val idx: Int, val kind: Char, val buy: Boolean)

/** Re-entry BBMA (MA, Mid BB ou ZZL) : zone de la mèche, entrée à la clôture, stop au-delà de la mèche. */
internal class ReSetup(
    val idx: Int,
    val kind: Char,
    val buy: Boolean,
    val zoneLow: Double,
    val zoneHigh: Double,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double
)

internal fun buildReSetup(c: List<Candle>, marks: List<Mark>, atr: Double): List<ReSetup> {
    val n = c.size
    if (atr <= 0.0 || n < 60) return emptyList()
    var best: Mark? = null
    for (mk in marks) {
        if (mk.kind != 'N' && mk.kind != 'R' && mk.kind != 'Z') continue
        if (mk.idx < n - 30 || mk.idx >= n - 1) continue
        if (best == null || mk.idx > best.idx) best = mk
    }
    val m = best ?: return emptyList()
    val cd = c[m.idx]
    val entry = cd.close
    val sl = if (m.buy) cd.low - 0.3 * atr else cd.high + 0.3 * atr
    val risk = abs(entry - sl)
    if (risk < 0.5 * atr || risk > 4 * atr) return emptyList()
    val sign = if (m.buy) 1.0 else -1.0
    val tp1 = entry + sign * 1.5 * risk
    val tp2 = entry + sign * 3.0 * risk
    // Déjà terminé : stop ou TP1 touché depuis
    for (k in m.idx + 1 until n) {
        if (m.buy && (c[k].low <= sl || c[k].high >= tp1)) return emptyList()
        if (!m.buy && (c[k].high >= sl || c[k].low <= tp1)) return emptyList()
    }
    val bodyEdge = if (m.buy) min(cd.open, cd.close) else max(cd.open, cd.close)
    val zl = if (m.buy) cd.low else bodyEdge
    val zh = if (m.buy) bodyEdge else cd.high
    return listOf(ReSetup(m.idx, m.kind, m.buy, zl, zh, entry, sl, tp1, tp2))
}

/** État BBMA d'un timeframe sur la dernière bougie terminée, pour la ligne multi timeframe. */
internal fun bbmaTfText(tf: Tf, closed: List<Candle>): String {
    if (closed.size < 60) return tf.label + " : —"
    val b = BbmaCalc(closed)
    val mk = b.marks()
    val hit = b.detect()
    var dir: Direction? = null
    val st: String
    if (hit != null) {
        dir = hit.dir
        st = if (hit.setup.contains("MHV")) "MHV" else hit.sub.ifEmpty { "Re-entry" }
    } else if (mk.extreme != null) {
        dir = mk.extreme
        st = if (mk.extremeAgo == 0) "Extrême" else "Extrême (il y a " + mk.extremeAgo + ")"
    } else {
        return tf.label + " : —"
    }
    val arrow = if (dir == Direction.BUY) "▲" else "▼"
    val csak = if (mk.candle == "CSAK" && mk.candleDir == dir) " + CSAK" else ""
    return tf.label + " : " + st + " " + arrow + csak
}

internal class LiqLine(val price: Double, val fromIdx: Int, val label: String, val high: Boolean)

internal class PatLine(val i1: Int, val p1: Double, val i2: Int, val p2: Double, val buy: Boolean)

/** Points hauts/bas respectés au moins 2 fois, regroupés par proximité. */
internal fun chartLevels(c: List<Candle>, atr: Double): List<SrLevel> {
    if (atr <= 0.0) return emptyList()
    val k = 5
    val pts = ArrayList<Double>()
    for (i in k until c.size - k) {
        var isHigh = true
        var isLow = true
        for (d in 1..k) {
            if (c[i].high <= c[i - d].high || c[i].high <= c[i + d].high) isHigh = false
            if (c[i].low >= c[i - d].low || c[i].low >= c[i + d].low) isLow = false
        }
        if (isHigh) pts.add(c[i].high)
        if (isLow) pts.add(c[i].low)
    }
    pts.sort()
    val out = ArrayList<SrLevel>()
    var group = ArrayList<Double>()
    for (p in pts) {
        if (group.isNotEmpty() && p - group.last() > 0.3 * atr) {
            if (group.size >= 2) out.add(SrLevel(group.average(), group.size))
            group = ArrayList()
        }
        group.add(p)
    }
    if (group.size >= 2) out.add(SrLevel(group.average(), group.size))
    return out
}

/**
 * Zones Supply et Demand : une petite bougie de base suivie d'une impulsion d'au moins 1,5 ATR.
 * Une zone est supprimée si le prix clôture au-delà (zone cassée). Elle est « testée » si le prix l'a touchée.
 * On garde les 3 zones de demande et les 3 zones d'offre les plus proches du prix.
 */
internal fun buildSdBoxes(c: List<Candle>, atr: Double): List<SdBox> {
    val out = ArrayList<SdBox>()
    val n = c.size
    if (atr <= 0.0 || n < 30) return out
    val cn = n - 1 // nombre de bougies terminées : la dernière (indice cn) est en formation
    val price = c[n - 1].close
    for (i in max(2, cn - 200)..(cn - 5)) {
        val b = c[i]
        val body = abs(b.close - b.open)
        val range = b.high - b.low
        if (!(body <= 0.6 * atr && range <= 1.2 * atr)) continue
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (k in (i + 1)..(i + 3)) {
            hi = max(hi, c[k].high)
            lo = min(lo, c[k].low)
        }
        val up = (hi - b.high) / atr
        val down = (b.low - lo) / atr
        val isDemand = up >= 1.5 && c[i + 1].close > b.high && up >= down
        val isSupply = !isDemand && down >= 1.5 && c[i + 1].close < b.low
        if (!isDemand && !isSupply) continue

        var broken = false
        var tested = false
        for (j in (i + 4)..(n - 1)) {
            val k = c[j]
            if (isDemand) {
                if (j < cn && k.close < b.low) {
                    broken = true
                    break
                }
                if (k.low <= b.high) tested = true
            } else {
                if (j < cn && k.close > b.high) {
                    broken = true
                    break
                }
                if (k.high >= b.low) tested = true
            }
        }
        if (broken) continue
        out.add(SdBox(b.low, b.high, isDemand, i, tested))
    }
    val demand = out.filter { it.buy }.sortedBy { abs((it.low + it.high) / 2.0 - price) }.take(3)
    val supply = out.filter { !it.buy }.sortedBy { abs((it.low + it.high) / 2.0 - price) }.take(3)
    return demand + supply
}

/**
 * Profil temps/prix à plage fixe : les `rangeN` dernières bougies terminées.
 * Chaque bougie ajoute 1 à chaque tranche de prix touchée, et 1 de plus sur son corps.
 * POC = tranche la plus fréquentée. Zone de valeur = 70 % du total autour du POC.
 */
internal fun buildVp(c: List<Candle>, rangeN: Int, bins: Int): VpResult? {
    val n = c.size
    val cn = n - 1
    val from = max(0, cn - rangeN)
    if (cn - from < 20) return null
    var hi = Double.NEGATIVE_INFINITY
    var lo = Double.POSITIVE_INFINITY
    for (j in from until cn) {
        hi = max(hi, c[j].high)
        lo = min(lo, c[j].low)
    }
    if (hi <= lo) return null
    val step = (hi - lo) / bins
    val w = DoubleArray(bins)
    for (j in from until cn) {
        val k = c[j]
        val bLo = ((k.low - lo) / step).toInt().coerceIn(0, bins - 1)
        val bHi = ((k.high - lo) / step).toInt().coerceIn(0, bins - 1)
        val bodyLo = ((min(k.open, k.close) - lo) / step).toInt().coerceIn(0, bins - 1)
        val bodyHi = ((max(k.open, k.close) - lo) / step).toInt().coerceIn(0, bins - 1)
        for (b in bLo..bHi) w[b] += 1.0
        for (b in bodyLo..bodyHi) w[b] += 1.0
    }
    var poc = 0
    var total = 0.0
    for (b in 0 until bins) {
        total += w[b]
        if (w[b] > w[poc]) poc = b
    }
    var vaL = poc
    var vaH = poc
    var acc = w[poc]
    while (acc < 0.7 * total && (vaL > 0 || vaH < bins - 1)) {
        val below = if (vaL > 0) w[vaL - 1] else -1.0
        val above = if (vaH < bins - 1) w[vaH + 1] else -1.0
        if (above >= below) {
            vaH++
            acc += w[vaH]
        } else {
            vaL--
            acc += w[vaL]
        }
    }
    return VpResult(lo, step, w, poc, vaL, vaH, from)
}

/** Ligne de repères BBMA : Extrême, MHV / Re-entry, bougie CSM / CSAK / CSA. */
internal fun marksLine(c: List<Candle>): String? {
    return try {
        val cc = c.dropLast(1)
        val b = BbmaCalc(cc)
        val mk = b.marks()
        val hit = b.detect()
        val parts = ArrayList<String>()
        val ext = mk.extreme
        if (ext != null) {
            val side = if (ext == Direction.SELL) "Extrême haut" else "Extrême bas"
            parts.add(if (mk.extremeAgo == 0) side else side + " (il y a " + mk.extremeAgo + " bougies)")
        }
        if (hit != null) parts.add(if (hit.setup.contains("MHV")) "MHV" else hit.sub.ifEmpty { "Re-entry" })
        val cn = mk.candle
        val cd = mk.candleDir
        if (cn != null && cd != null) {
            parts.add(cn + (if (cd == Direction.BUY) " haussier" else " baissier"))
        }
        if (parts.isEmpty()) null else "Repères BBMA : " + parts.joinToString(" → ")
    } catch (e: Exception) {
        null
    }
}

/**
 * Marques BBMA sur chaque bougie terminée, avec exactement les règles de Bbma.kt.
 * Extrême : première bougie d'une série où la MA5 sort de la bande de Bollinger.
 * MHV et Re-entry : même règle que le moteur. CSM, CSAK, CSA : tes définitions.
 */
internal fun buildBbmaMarks(
    c: List<Candle>,
    up: DoubleArray,
    mid: DoubleArray,
    low: DoubleArray,
    m5h: DoubleArray,
    m5l: DoubleArray,
    m10h: DoubleArray,
    m10l: DoubleArray
): List<Mark> {
    val out = ArrayList<Mark>()
    val n = c.size
    for (i in 56 until n - 1) {
        // Extrême : première bougie de la série
        if (m5h[i] > up[i] && !(m5h[i - 1] > up[i - 1])) out.add(Mark(i, 'E', false))
        if (m5l[i] < low[i] && !(m5l[i - 1] < low[i - 1])) out.add(Mark(i, 'E', true))

        // Dernier Extrême dans les 15 bougies précédentes
        var e = -1
        var sell = false
        for (k in i downTo max(i - 15, 0)) {
            if (m5h[k] > up[k]) {
                e = k
                sell = true
                break
            }
            if (m5l[k] < low[k]) {
                e = k
                sell = false
                break
            }
        }
        if (e != -1 && e != i) {
            val cur = c[i]
            if (sell) {
                if (cur.high > up[i] && m5h[i] <= up[i] && cur.close < up[i]) {
                    out.add(Mark(i, 'M', false))
                } else {
                    val rk = bbmaReentryKind(c, mid, m5h, m10h, m5l, m10l, i, e, true)
                    if (rk > 0) out.add(Mark(i, if (rk == 1) 'N' else if (rk == 2) 'R' else 'Z', false))
                }
            } else {
                if (cur.low < low[i] && m5l[i] >= low[i] && cur.close > low[i]) {
                    out.add(Mark(i, 'M', true))
                } else {
                    val rk = bbmaReentryKind(c, mid, m5h, m10h, m5l, m10l, i, e, false)
                    if (rk > 0) out.add(Mark(i, if (rk == 1) 'N' else if (rk == 2) 'R' else 'Z', true))
                }
            }
        }

        // Bougies CSM / CSAK / CSA
        val cl = c[i].close
        val maHigh = max(m5h[i], m10h[i])
        val maLow = min(m5l[i], m10l[i])
        if (cl > up[i]) {
            out.add(Mark(i, '1', true))
        } else if (cl < low[i]) {
            out.add(Mark(i, '1', false))
        } else if (cl > maHigh) {
            out.add(Mark(i, if (cl > mid[i]) '2' else '3', true))
        } else if (cl < maLow) {
            out.add(Mark(i, if (cl < mid[i]) '2' else '3', false))
        }
    }
    return out
}

/**
 * Patterns de chandeliers et double sommet / creux, sur les bougies terminées.
 * Les patterns de retournement ne comptent qu'après un mouvement de 5 bougies dans le sens inverse.
 * Affichage seulement : ils ne changent pas le score des signaux.
 */
internal fun buildPatterns(c: List<Candle>, atr: Double): Pair<List<Mark>, List<PatLine>> {
    val marks = ArrayList<Mark>()
    val lines = ArrayList<PatLine>()
    val n = c.size
    if (atr <= 0.0 || n < 30) return Pair(marks, lines)

    for (i in 8 until n - 1) {
        val cur = c[i]
        val prev = c[i - 1]
        val body = abs(cur.close - cur.open)
        val range = cur.high - cur.low
        val pBody = abs(prev.close - prev.open)
        val down = prev.close < c[i - 6].close
        val up = prev.close > c[i - 6].close

        // Engulfing : le corps avale le corps précédent
        if (body >= 0.3 * atr && body > pBody) {
            if (down && prev.close < prev.open && cur.close > cur.open &&
                cur.open <= prev.close && cur.close >= prev.open
            ) {
                marks.add(Mark(i, 'G', true))
            }
            if (up && prev.close > prev.open && cur.close < cur.open &&
                cur.open >= prev.close && cur.close <= prev.open
            ) {
                marks.add(Mark(i, 'G', false))
            }
        }

        // Pin bar : marteau (mèche basse) ou étoile filante (mèche haute)
        if (range >= 0.5 * atr) {
            val upper = cur.high - max(cur.open, cur.close)
            val lower = min(cur.open, cur.close) - cur.low
            if (down && lower >= 2 * body && lower >= 0.6 * range) marks.add(Mark(i, 'P', true))
            if (up && upper >= 2 * body && upper >= 0.6 * range) marks.add(Mark(i, 'P', false))
        }

        // Doji : corps minuscule
        if (range >= 0.5 * atr && body <= 0.1 * range) marks.add(Mark(i, 'D', false))

        // Inside bar : la bougie reste dans celle d'avant
        if ((prev.high - prev.low) >= 0.8 * atr && cur.high < prev.high && cur.low > prev.low) {
            marks.add(Mark(i, 'I', false))
        }

        // Étoile du matin / du soir : bougies i-2, i-1 et i
        val a = c[i - 2]
        val aBody = abs(a.close - a.open)
        val aMid = (a.open + a.close) / 2.0
        val pMid = (prev.open + prev.close) / 2.0
        if (aBody >= 0.6 * atr && pBody <= 0.35 * aBody) {
            if (a.close < c[i - 7].close && a.close < a.open && pMid < a.close &&
                cur.close > cur.open && cur.close > aMid
            ) {
                marks.add(Mark(i, 'T', true))
            }
            if (a.close > c[i - 7].close && a.close > a.open && pMid > a.close &&
                cur.close < cur.open && cur.close < aMid
            ) {
                marks.add(Mark(i, 'T', false))
            }
        }
    }

    // Double sommet / double creux : deux pivots consécutifs au même niveau
    val k = 3
    val ph = ArrayList<Int>()
    val pl = ArrayList<Int>()
    for (i in k until n - k) {
        var isH = true
        var isL = true
        for (d in 1..k) {
            if (c[i].high <= c[i - d].high || c[i].high <= c[i + d].high) isH = false
            if (c[i].low >= c[i - d].low || c[i].low >= c[i + d].low) isL = false
        }
        if (isH) ph.add(i)
        if (isL) pl.add(i)
    }
    for (a in 0 until ph.size - 1) {
        val i1 = ph[a]
        val i2 = ph[a + 1]
        if (i2 - i1 < 5 || i2 - i1 > 60) continue
        if (abs(c[i1].high - c[i2].high) > 0.3 * atr) continue
        var trough = Double.MAX_VALUE
        for (j in i1..i2) trough = min(trough, c[j].low)
        if (min(c[i1].high, c[i2].high) - trough < atr) continue
        marks.add(Mark(i2, 'W', false))
        lines.add(PatLine(i1, c[i1].high, i2, c[i2].high, false))
    }
    for (a in 0 until pl.size - 1) {
        val i1 = pl[a]
        val i2 = pl[a + 1]
        if (i2 - i1 < 5 || i2 - i1 > 60) continue
        if (abs(c[i1].low - c[i2].low) > 0.3 * atr) continue
        var peak = -Double.MAX_VALUE
        for (j in i1..i2) peak = max(peak, c[j].high)
        if (peak - max(c[i1].low, c[i2].low) < atr) continue
        marks.add(Mark(i2, 'W', true))
        lines.add(PatLine(i1, c[i1].low, i2, c[i2].low, true))
    }
    return Pair(marks, lines)
}

/** Regroupe des points proches ; renvoie (indice du premier point, prix moyen) pour les groupes d'au moins 2. */
internal fun clusterLines(pts: List<Pair<Int, Double>>, tol: Double): List<Pair<Int, Double>> {
    val sorted = pts.sortedBy { it.second }
    val out = ArrayList<Pair<Int, Double>>()
    var grp = ArrayList<Pair<Int, Double>>()
    fun flush() {
        if (grp.size >= 2) {
            var first = Int.MAX_VALUE
            var sum = 0.0
            for (g in grp) {
                first = min(first, g.first)
                sum += g.second
            }
            out.add(Pair(first, sum / grp.size))
        }
        grp = ArrayList()
    }
    for (p in sorted) {
        if (grp.isNotEmpty() && p.second - grp.last().second > tol) flush()
        grp.add(p)
    }
    flush()
    return out
}

/**
 * Liquidité : hauts et bas égaux (EQH, EQL), hauts et bas récents non touchés,
 * et sweeps (la mèche dépasse un niveau mais la bougie clôture de l'autre côté).
 */
internal fun buildLiquidity(c: List<Candle>, atr: Double): Pair<List<LiqLine>, List<Mark>> {
    val marks = ArrayList<Mark>()
    val lines = ArrayList<LiqLine>()
    val n = c.size
    if (atr <= 0.0 || n < 30) return Pair(lines, marks)

    val k = 3
    val ph = ArrayList<Int>()
    val pl = ArrayList<Int>()
    for (i in k until n - k) {
        var isH = true
        var isL = true
        for (d in 1..k) {
            if (c[i].high <= c[i - d].high || c[i].high <= c[i + d].high) isH = false
            if (c[i].low >= c[i - d].low || c[i].low >= c[i + d].low) isL = false
        }
        if (isH) ph.add(i)
        if (isL) pl.add(i)
    }

    val last = c.last().close
    val tol = 0.15 * atr
    val done = HashSet<Int>()

    val openHighs = ArrayList<Int>()
    for (p in ph) {
        val price = c[p].high
        var j = p + 1
        while (j < n && c[j].high <= price) j++
        if (j >= n) {
            openHighs.add(p)
        } else if (j <= n - 2 && c[j].close <= price && done.add(j * 2)) {
            marks.add(Mark(j, 'S', false))
        }
    }
    val openLows = ArrayList<Int>()
    for (p in pl) {
        val price = c[p].low
        var j = p + 1
        while (j < n && c[j].low >= price) j++
        if (j >= n) {
            openLows.add(p)
        } else if (j <= n - 2 && c[j].close >= price && done.add(j * 2 + 1)) {
            marks.add(Mark(j, 'S', true))
        }
    }

    val eqHigh = clusterLines(openHighs.map { Pair(it, c[it].high) }, tol)
    val eqLow = clusterLines(openLows.map { Pair(it, c[it].low) }, tol)
    for (e in eqHigh.sortedBy { abs(it.second - last) }.take(3)) {
        lines.add(LiqLine(e.second, e.first, "EQH", true))
    }
    for (e in eqLow.sortedBy { abs(it.second - last) }.take(3)) {
        lines.add(LiqLine(e.second, e.first, "EQL", false))
    }

    val singleHighs = openHighs
        .filter { p -> eqHigh.none { abs(it.second - c[p].high) <= tol } }
        .sortedBy { abs(c[it].high - last) }
        .take(3)
    for (p in singleHighs) lines.add(LiqLine(c[p].high, p, "Liq. haute", true))
    val singleLows = openLows
        .filter { p -> eqLow.none { abs(it.second - c[p].low) <= tol } }
        .sortedBy { abs(c[it].low - last) }
        .take(3)
    for (p in singleLows) lines.add(LiqLine(c[p].low, p, "Liq. basse", false))

    return Pair(lines, marks)
}
