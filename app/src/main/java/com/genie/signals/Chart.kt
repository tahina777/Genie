package com.genie.signals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Ce qu'il faut pour dessiner un graphique : celui d'un signal, d'une ligne du journal, d'un marché libre ou d'une entrée en attente. */
class ChartSpec(
    val name: String,
    val code: String,
    val tfLabel: String,
    val tfSeconds: Int,
    val buy: Boolean?,
    val entry: Double?,
    val sl: Double?,
    val tp1: Double?,
    val tp2: Double?,
    val time: Long,
    val decimals: Int,
    val setup: String,
    val zoneLow: Double? = null,
    val zoneHigh: Double? = null
)

fun chartSpecOf(s: Signal) = ChartSpec(
    s.market.name, s.market.code, s.tf.label, s.tf.seconds, s.dir == Direction.BUY,
    s.entry, s.sl, s.tp1, s.tp2, s.time, s.market.decimals, s.setup
)

fun chartSpecOf(e: JEntry) = ChartSpec(
    e.market, e.code, e.tf, e.tfSeconds, e.buy,
    e.entry, e.sl, e.tp1, null, e.time, e.decimals, e.setup
)

fun chartSpecOf(p: PendingEntry): ChartSpec {
    val lv = p.level
    return ChartSpec(
        p.market.name, p.market.code, p.tf.label, p.tf.seconds, lv.buy,
        lv.entry, lv.sl, lv.tp1, lv.tp2, 0L, p.market.decimals,
        lv.kind + " (entrée limite " + (if (lv.buy) "achat" else "vente") + ")",
        lv.zoneLow, lv.zoneHigh
    )
}

/** Graphique libre d'un marché, sans signal. */
fun plainChartSpec(m: Market, tf: Tf) = ChartSpec(
    m.name, m.code, tf.label, tf.seconds, null,
    null, null, null, null, 0L, m.decimals, "Graphique libre"
)

/** Zone SMC détectée par le moteur. */
private class ChartZone(val low: Double, val high: Double, val buy: Boolean)

/** Niveau de support ou de résistance : prix et nombre de fois où il a été respecté. */
private class SrLevel(val price: Double, val touches: Int)

/** Zone de Supply (offre) ou Demand (demande). tested = le prix l'a déjà retouchée sans la casser. */
private class SdBox(val low: Double, val high: Double, val buy: Boolean, val startIdx: Int, val tested: Boolean)

/** Profil temps/prix à plage fixe : poids de chaque tranche de prix, POC et zone de valeur. */
private class VpResult(
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
 * E = Extrême, M = MHV, R = Re-entry, 1 = CSM, 2 = CSAK, 3 = CSA, S = sweep de liquidité,
 * G = Engulfing, P = Pin bar, D = Doji, I = Inside bar, T = Étoile du matin/soir, W = Double sommet/creux.
 * buy = true : marque haussière, dessinée sous la bougie ; false : au-dessus.
 */
private class Mark(val idx: Int, val kind: Char, val buy: Boolean)

private class LiqLine(val price: Double, val fromIdx: Int, val label: String, val high: Boolean)

private class PatLine(val i1: Int, val p1: Double, val i2: Int, val p2: Double, val buy: Boolean)

private class ChartData(
    val c: List<Candle>,
    val bbUp: DoubleArray,
    val bbMid: DoubleArray,
    val bbLow: DoubleArray,
    val ma5h: DoubleArray,
    val ma5l: DoubleArray,
    val ma10h: DoubleArray,
    val ma10l: DoubleArray,
    val ema50: DoubleArray,
    val zones: List<ChartZone>,
    val levels: List<SrLevel>,
    val sdBoxes: List<SdBox>,
    val pdHigh: Double,
    val pdLow: Double,
    val bbmaLine: String?,
    val marks: List<Mark>,
    val liq: List<LiqLine>,
    val entries: List<EntryLevel>,
    val patLines: List<PatLine>
)

private fun fmtPrice(v: Double, d: Int): String =
    String.format(Locale.US, "%." + d + "f", v)

/** Texte affiché sur la bougie pour les marques en lettres. */
private fun markText(kind: Char, buy: Boolean): String = when (kind) {
    'G' -> "EN"
    'P' -> "PB"
    'D' -> "DJ"
    'I' -> "IB"
    'T' -> "ST"
    'W' -> if (buy) "DC" else "DS"
    else -> kind.toString()
}

/** Points hauts/bas respectés au moins 2 fois, regroupés par proximité. */
private fun chartLevels(c: List<Candle>, atr: Double): List<SrLevel> {
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
private fun buildSdBoxes(c: List<Candle>, atr: Double): List<SdBox> {
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
private fun buildVp(c: List<Candle>, rangeN: Int, bins: Int): VpResult? {
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
private fun marksLine(c: List<Candle>): String? {
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
        if (hit != null) parts.add(if (hit.setup.contains("MHV")) "MHV" else "Re-entry")
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
private fun buildBbmaMarks(
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
                    var went = false
                    for (k in e + 1 until i) if (c[k].close < mid[k]) went = true
                    if (went && cur.high >= mid[i] && cur.close < mid[i]) out.add(Mark(i, 'R', false))
                }
            } else {
                if (cur.low < low[i] && m5l[i] >= low[i] && cur.close > low[i]) {
                    out.add(Mark(i, 'M', true))
                } else {
                    var went = false
                    for (k in e + 1 until i) if (c[k].close > mid[k]) went = true
                    if (went && cur.low <= mid[i] && cur.close > mid[i]) out.add(Mark(i, 'R', true))
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
private fun buildPatterns(c: List<Candle>, atr: Double): Pair<List<Mark>, List<PatLine>> {
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
private fun clusterLines(pts: List<Pair<Int, Double>>, tol: Double): List<Pair<Int, Double>> {
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
private fun buildLiquidity(c: List<Candle>, atr: Double): Pair<List<LiqLine>, List<Mark>> {
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

private fun buildData(c: List<Candle>, htf: List<Candle>?): ChartData {
    val n = c.size
    val closes = DoubleArray(n) { c[it].close }
    val highs = DoubleArray(n) { c[it].high }
    val lows = DoubleArray(n) { c[it].low }
    val bb = Ind.bollinger(closes)
    val w5h = Ind.wma(highs, 5)
    val w5l = Ind.wma(lows, 5)
    val w10h = Ind.wma(highs, 10)
    val w10l = Ind.wma(lows, 10)
    val e50 = Ind.ema(closes, 50)

    val upA = DoubleArray(n) { bb.up[it] }
    val midA = DoubleArray(n) { bb.mid[it] }
    val lowA = DoubleArray(n) { bb.low[it] }
    val m5h = DoubleArray(n) { w5h[it] }
    val m5l = DoubleArray(n) { w5l[it] }
    val m10h = DoubleArray(n) { w10h[it] }
    val m10l = DoubleArray(n) { w10l[it] }
    val e50A = DoubleArray(n) { e50[it] }

    val atr = Ind.atr(c)
    val zones = ArrayList<ChartZone>()
    try {
        val smc = SmcCalc(c).detect()
        if (smc != null) zones.add(ChartZone(smc.zone.low, smc.zone.high, smc.dir == Direction.BUY))
    } catch (e: Exception) {
        // zone indisponible
    }

    var sdBoxes: List<SdBox> = emptyList()
    try {
        sdBoxes = buildSdBoxes(c, atr)
    } catch (e: Exception) {
        // zones Supply/Demand indisponibles
    }

    var pdHigh = Double.NEGATIVE_INFINITY
    var pdLow = Double.POSITIVE_INFINITY
    for (k in max(0, n - 100) until n) {
        pdHigh = max(pdHigh, c[k].high)
        pdLow = min(pdLow, c[k].low)
    }

    val marks = ArrayList<Mark>()
    try {
        marks.addAll(buildBbmaMarks(c, upA, midA, lowA, m5h, m5l, m10h, m10l))
    } catch (e: Exception) {
        // marques BBMA indisponibles
    }
    var liq: List<LiqLine> = emptyList()
    try {
        val res = buildLiquidity(c, atr)
        liq = res.first
        marks.addAll(res.second)
    } catch (e: Exception) {
        // liquidité indisponible
    }
    var patLines: List<PatLine> = emptyList()
    try {
        val res = buildPatterns(c, atr)
        marks.addAll(res.first)
        patLines = res.second
    } catch (e: Exception) {
        // patterns indisponibles
    }
    var ents: List<EntryLevel> = emptyList()
    try {
        ents = Entries.compute(c, htf)
    } catch (e: Exception) {
        // entrées indisponibles
    }

    return ChartData(
        c = c,
        bbUp = upA,
        bbMid = midA,
        bbLow = lowA,
        ma5h = m5h,
        ma5l = m5l,
        ma10h = m10h,
        ma10l = m10l,
        ema50 = e50A,
        zones = zones,
        levels = chartLevels(c, atr),
        sdBoxes = sdBoxes,
        pdHigh = pdHigh,
        pdLow = pdLow,
        bbmaLine = marksLine(c),
        marks = marks,
        liq = liq,
        entries = ents,
        patLines = patLines
    )
}

@Composable
fun ChartScreen(spec: ChartSpec, onBack: () -> Unit) {
    var data by remember(spec) { mutableStateOf<ChartData?>(null) }
    var error by remember(spec) { mutableStateOf<String?>(null) }
    var showBb by remember { mutableStateOf(true) }
    var showMa by remember { mutableStateOf(true) }
    var showEma by remember { mutableStateOf(false) }
    var showLiq by remember { mutableStateOf(false) }
    var showEntries by remember { mutableStateOf(false) }
    var showFar by remember { mutableStateOf(false) }
    var showZones by remember { mutableStateOf(false) }
    var showSd by remember { mutableStateOf(false) }
    var showSr by remember { mutableStateOf(false) }
    var showVp by remember { mutableStateOf(false) }
    var vpRange by remember { mutableStateOf(100) }
    var showE by remember { mutableStateOf(false) }
    var showM by remember { mutableStateOf(false) }
    var showR by remember { mutableStateOf(false) }
    var showCs by remember { mutableStateOf(false) }
    var showCsa by remember { mutableStateOf(false) }
    var showEng by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var showDoji by remember { mutableStateOf(false) }
    var showInside by remember { mutableStateOf(false) }
    var showStar by remember { mutableStateOf(false) }
    var showDouble by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(80) }
    var shift by remember { mutableStateOf(0f) }
    val tm = rememberTextMeasurer()

    LaunchedEffect(spec) {
        try {
            val raw = Deriv.candles(spec.code, spec.tfSeconds, 300)
            if (raw.size < 60) {
                error = "Pas assez de bougies pour dessiner le graphique."
            } else {
                val hs = Htf.seconds(spec.tfSeconds)
                val htf = if (hs == null) null else try {
                    Deriv.candles(spec.code, hs, 300)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                data = withContext(Dispatchers.Default) { buildData(raw, htf) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Impossible de charger le graphique : vérifie ta connexion internet."
        }
    }

    val green = Color(0xFF26A69A)
    val red = Color(0xFFEF5350)
    val tealTp2 = Color(0xFF80CBC4)
    val entryBlue = Color(0xFF29B6F6)
    val neutralGray = Color(0xFFB0BEC5)
    val supportColor = Color(0xFF9CCC65)
    val resistColor = Color(0xFFFF7043)
    val testedColor = Color(0xFFFFB74D)
    val pocColor = Color(0xFFFFD54F)
    val vaColor = Color(0xFF42A5F5)
    val buy = spec.buy
    val sideColor = if (buy == null) Color.Gray else if (buy) green else red

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Retour") }
            Text(
                spec.name + " · " + spec.tfLabel,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            if (buy != null) {
                Text(
                    if (buy) "ACHAT" else "VENTE",
                    color = sideColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(spec.setup, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val d = data
        val err = error
        if (d == null) {
            if (err != null) {
                Text(err, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            // Rangée 1 : entrées, indicateurs, liquidité
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showEntries, onClick = { showEntries = !showEntries }, label = { Text("Entrées") })
                FilterChip(selected = showFar, onClick = { showFar = !showFar }, label = { Text("Lointaines") })
                FilterChip(selected = showBb, onClick = { showBb = !showBb }, label = { Text("Bollinger") })
                FilterChip(selected = showMa, onClick = { showMa = !showMa }, label = { Text("MA5/10") })
                FilterChip(selected = showEma, onClick = { showEma = !showEma }, label = { Text("EMA50") })
                FilterChip(selected = showLiq, onClick = { showLiq = !showLiq }, label = { Text("Liquidité") })
            }
            // Rangée 2 : zones, supports/résistances, profil
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showZones, onClick = { showZones = !showZones }, label = { Text("Zones") })
                FilterChip(selected = showSd, onClick = { showSd = !showSd }, label = { Text("Supply/Demand") })
                FilterChip(selected = showSr, onClick = { showSr = !showSr }, label = { Text("S/R") })
                FilterChip(selected = showVp, onClick = { showVp = !showVp }, label = { Text("Profil de volume") })
                if (showVp) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            vpRange = when (vpRange) {
                                50 -> 100
                                100 -> 200
                                else -> 50
                            }
                        },
                        label = { Text("Plage " + vpRange) }
                    )
                }
            }
            // Rangée 3 : repères BBMA
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showE, onClick = { showE = !showE }, label = { Text("Extrême") })
                FilterChip(selected = showM, onClick = { showM = !showM }, label = { Text("MHV") })
                FilterChip(selected = showR, onClick = { showR = !showR }, label = { Text("Re-entry") })
                FilterChip(selected = showCs, onClick = { showCs = !showCs }, label = { Text("CSM/CSAK") })
                FilterChip(selected = showCsa, onClick = { showCsa = !showCsa }, label = { Text("CSA") })
            }
            // Rangée 4 : patterns
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showEng, onClick = { showEng = !showEng }, label = { Text("Engulfing") })
                FilterChip(selected = showPin, onClick = { showPin = !showPin }, label = { Text("Pin bar") })
                FilterChip(selected = showDoji, onClick = { showDoji = !showDoji }, label = { Text("Doji") })
                FilterChip(selected = showInside, onClick = { showInside = !showInside }, label = { Text("Inside bar") })
                FilterChip(selected = showStar, onClick = { showStar = !showStar }, label = { Text("Étoile") })
                FilterChip(selected = showDouble, onClick = { showDouble = !showDouble }, label = { Text("Double top/bottom") })
            }

            // Entrées affichées : proches (2 ATR au plus) sauf si « Lointaines » est actif
            val shownEntries: List<EntryLevel> =
                if (showEntries) d.entries.filter { showFar || it.distAtr <= 2.0 } else emptyList()

            // Profil de volume à plage fixe : calculé sur les dernières bougies, il ne bouge pas avec le défilement
            val vpData = remember(d, vpRange) { buildVp(d.c, vpRange, 40) }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .clipToBounds()
                    .pointerInput(d) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            val cw = (size.width - 64.dp.toPx()) / visible
                            val maxShift = (d.c.size - visible).coerceAtLeast(0).toFloat()
                            shift = (shift + dragAmount / cw).coerceIn(0f, maxShift)
                        }
                    }
            ) {
                val labelW = 64.dp.toPx()
                val w = size.width
                val h = size.height
                val plotW = w - labelW
                val n = d.c.size
                val vis = min(visible, n)
                val end = n - shift.toInt().coerceIn(0, max(0, n - vis))
                val start = max(0, end - vis)
                val cw = plotW / vis
                val dec = spec.decimals
                val lastC = d.c.last()
                val price = lastC.close

                val entry = spec.entry
                val slP = spec.sl
                val tp1P = spec.tp1
                val tp2P = spec.tp2
                val zl = spec.zoneLow
                val zh = spec.zoneHigh
                val ents = shownEntries

                // Supports et résistances : les 3 plus proches sous le prix et les 3 plus proches au-dessus
                val srShown: List<SrLevel> = if (showSr) {
                    d.levels.filter { it.price < price }.sortedByDescending { it.price }.take(3) +
                        d.levels.filter { it.price >= price }.sortedBy { it.price }.take(3)
                } else {
                    emptyList()
                }

                var hi = Double.NEGATIVE_INFINITY
                var lo = Double.POSITIVE_INFINITY
                for (j in start until end) {
                    hi = max(hi, d.c[j].high)
                    lo = min(lo, d.c[j].low)
                }
                for (p in listOfNotNull(entry, slP, tp1P, tp2P)) {
                    hi = max(hi, p)
                    lo = min(lo, p)
                }
                if (zl != null && zh != null) {
                    hi = max(hi, zh)
                    lo = min(lo, zl)
                }
                for (lv in ents) {
                    for (p in listOf(lv.entry, lv.sl, lv.tp1)) {
                        hi = max(hi, p)
                        lo = min(lo, p)
                    }
                }
                val pad = (hi - lo) * 0.05
                hi += pad
                lo -= pad
                if (hi <= lo) return@Canvas

                fun y(p: Double): Float = (h - ((p - lo) / (hi - lo)) * h).toFloat()
                fun x(j: Int): Float = (j - start + 0.5f) * cw

                // Prix du profil : POC et limites de la zone de valeur
                val vp = if (showVp) vpData else null
                var pocP = 0.0
                var vahP = 0.0
                var valP = 0.0
                if (vp != null) {
                    pocP = vp.lo + (vp.poc + 0.5) * vp.step
                    vahP = vp.lo + (vp.vaHigh + 1) * vp.step
                    valP = vp.lo + vp.vaLow * vp.step
                }

                // Prix des étiquettes colorées : les graduations trop proches seront masquées
                val specials = ArrayList<Double>()
                specials.add(price)
                if (entry != null) specials.add(entry)
                if (slP != null) specials.add(slP)
                if (tp1P != null) specials.add(tp1P)
                if (tp2P != null) specials.add(tp2P)
                for (lv in ents) {
                    specials.add(lv.entry)
                    specials.add(lv.sl)
                    specials.add(lv.tp1)
                    specials.add(lv.tp2)
                }
                for (lv in srShown) specials.add(lv.price)
                if (vp != null) {
                    specials.add(pocP)
                    specials.add(vahP)
                    specials.add(valP)
                }

                // Étiquette de prix dans la marge de droite
                fun label(p: Double, textColor: Color, fill: Color) {
                    val yy = y(p)
                    if (yy < 0f || yy > h) return
                    val layout = tm.measure(fmtPrice(p, dec), TextStyle(color = textColor, fontSize = 10.sp))
                    val hh = layout.size.height.toFloat()
                    drawRect(
                        fill,
                        topLeft = Offset(plotW, yy - hh / 2f),
                        size = Size(labelW, hh)
                    )
                    drawText(layout, topLeft = Offset(plotW + 4f, yy - hh / 2f))
                }

                // Zone de l'entrée en attente ouverte depuis la liste
                if (zl != null && zh != null) {
                    val zc = if (buy == true) green else red
                    drawRect(
                        zc.copy(alpha = 0.20f),
                        topLeft = Offset(0f, y(zh)),
                        size = Size(plotW, max(y(zl) - y(zh), 2f))
                    )
                }

                // Zones : Premium / Discount et zone SMC
                if (showZones) {
                    val mid = (d.pdHigh + d.pdLow) / 2.0
                    drawRect(
                        Color(0xFFEF5350).copy(alpha = 0.07f),
                        topLeft = Offset(0f, y(d.pdHigh)),
                        size = Size(plotW, y(mid) - y(d.pdHigh))
                    )
                    drawRect(
                        Color(0xFF26A69A).copy(alpha = 0.07f),
                        topLeft = Offset(0f, y(mid)),
                        size = Size(plotW, y(d.pdLow) - y(mid))
                    )
                    drawLine(
                        Color.Gray,
                        Offset(0f, y(mid)),
                        Offset(plotW, y(mid)),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                    for (z in d.zones) {
                        val zc = if (z.buy) Color(0xFF26A69A) else Color(0xFFEF5350)
                        drawRect(
                            zc.copy(alpha = 0.20f),
                            topLeft = Offset(0f, y(z.high)),
                            size = Size(plotW, max(y(z.low) - y(z.high), 2f))
                        )
                    }
                }

                // Entrées en attente : zone, entrée limite, stop et cibles
                if (ents.isNotEmpty()) {
                    val dsh = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    for (lv in ents) {
                        val zc = if (lv.buy) green else red
                        drawRect(
                            zc.copy(alpha = 0.18f),
                            topLeft = Offset(0f, y(lv.zoneHigh)),
                            size = Size(plotW, max(y(lv.zoneLow) - y(lv.zoneHigh), 2f))
                        )
                        drawLine(entryBlue, Offset(0f, y(lv.entry)), Offset(plotW, y(lv.entry)), strokeWidth = 2.5f, pathEffect = dsh)
                        drawLine(red.copy(alpha = 0.8f), Offset(0f, y(lv.sl)), Offset(plotW, y(lv.sl)), strokeWidth = 1.5f, pathEffect = dsh)
                        drawLine(green.copy(alpha = 0.8f), Offset(0f, y(lv.tp1)), Offset(plotW, y(lv.tp1)), strokeWidth = 1.5f, pathEffect = dsh)
                        if (lv.tp2 in lo..hi) {
                            drawLine(tealTp2.copy(alpha = 0.6f), Offset(0f, y(lv.tp2)), Offset(plotW, y(lv.tp2)), strokeWidth = 1.5f, pathEffect = dsh)
                        }
                    }
                }

                // Profil de volume à plage fixe : barres ancrées sur le bord droit du graphique
                if (vp != null) {
                    var maxW = 0.0
                    for (wv in vp.weights) if (wv > maxW) maxW = wv
                    val barMax = plotW * 0.32f
                    if (maxW > 0.0) {
                        for (b in vp.weights.indices) {
                            val pLo = vp.lo + b * vp.step
                            val pHi = pLo + vp.step
                            val yTop = y(pHi)
                            val yBot = y(pLo)
                            if (yBot < 0f || yTop > h) continue
                            val len = (vp.weights[b] / maxW * barMax).toFloat()
                            val col = when {
                                b == vp.poc -> pocColor
                                b in vp.vaLow..vp.vaHigh -> vaColor
                                else -> Color(0xFF78909C)
                            }
                            drawRect(
                                col.copy(alpha = if (b == vp.poc) 0.55f else 0.35f),
                                topLeft = Offset(plotW - len, yTop),
                                size = Size(len, max(yBot - yTop - 1f, 1f))
                            )
                        }
                    }
                    val vpDash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    drawLine(pocColor, Offset(0f, y(pocP)), Offset(plotW, y(pocP)), strokeWidth = 2f, pathEffect = vpDash)
                    drawLine(vaColor, Offset(0f, y(vahP)), Offset(plotW, y(vahP)), strokeWidth = 1.5f, pathEffect = vpDash)
                    drawLine(vaColor, Offset(0f, y(valP)), Offset(plotW, y(valP)), strokeWidth = 1.5f, pathEffect = vpDash)
                    // Début de la plage fixe
                    if (vp.fromIdx in start until end) {
                        val sx = x(vp.fromIdx)
                        drawLine(
                            Color(0xFF90A4AE),
                            Offset(sx, 0f),
                            Offset(sx, h),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
                        )
                    }
                }

                // Liquidité : hauts/bas égaux et niveaux non touchés, avec fond lisible
                if (showLiq) {
                    for (ln in d.liq) {
                        if (ln.price < lo || ln.price > hi) continue
                        if (ln.fromIdx >= end) continue
                        val x0 = if (ln.fromIdx < start) 0f else x(ln.fromIdx)
                        val lc = if (ln.high) Color(0xFFFF8A65) else Color(0xFF4DD0E1)
                        drawLine(
                            lc,
                            Offset(x0, y(ln.price)),
                            Offset(plotW, y(ln.price)),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 7f))
                        )
                        val lay = tm.measure(ln.label, TextStyle(color = lc, fontSize = 9.sp))
                        val ty = y(ln.price) - lay.size.height - 2f
                        drawRect(
                            Color(0xFF0E1117).copy(alpha = 0.8f),
                            topLeft = Offset(x0 + 2f, ty),
                            size = Size(lay.size.width.toFloat() + 4f, lay.size.height.toFloat())
                        )
                        drawText(lay, topLeft = Offset(x0 + 4f, ty))
                    }
                }

                // Quadrillage léger
                for (q in 1..4) {
                    val p = lo + (hi - lo) * q / 5.0
                    drawLine(
                        Color(0xFF37474F).copy(alpha = 0.5f),
                        Offset(0f, y(p)),
                        Offset(plotW, y(p)),
                        strokeWidth = 1f
                    )
                }

                // Supports et résistances : lignes épaisses, d'autant plus que le niveau a été respecté
                for (lv in srShown) {
                    if (lv.price < lo || lv.price > hi) continue
                    val sc = if (lv.price < price) supportColor else resistColor
                    val sw = min(14f, 4f + lv.touches * 2f)
                    drawLine(
                        sc.copy(alpha = 0.85f),
                        Offset(0f, y(lv.price)),
                        Offset(plotW, y(lv.price)),
                        strokeWidth = sw
                    )
                }

                fun series(arr: DoubleArray, color: Color, sw: Float) {
                    val p = Path()
                    var started = false
                    for (j in start until end) {
                        val v = arr[j]
                        if (v.isNaN()) {
                            started = false
                            continue
                        }
                        val px = x(j)
                        val py = y(v)
                        if (!started) {
                            p.moveTo(px, py)
                            started = true
                        } else {
                            p.lineTo(px, py)
                        }
                    }
                    drawPath(p, color, style = Stroke(width = sw))
                }

                if (showBb) {
                    series(d.bbUp, Color(0xFF4FC3F7), 2f)
                    series(d.bbMid, Color(0xFF90A4AE), 1.5f)
                    series(d.bbLow, Color(0xFF4FC3F7), 2f)
                }
                if (showMa) {
                    series(d.ma5h, Color(0xFFFFB74D), 2f)
                    series(d.ma5l, Color(0xFFFFB74D), 2f)
                    series(d.ma10h, Color(0xFFFFF176), 1.5f)
                    series(d.ma10l, Color(0xFFFFF176), 1.5f)
                }
                if (showEma) series(d.ema50, Color(0xFFBA68C8), 2.5f)

                // Bougies
                for (j in start until end) {
                    val k = d.c[j]
                    val col = if (k.close >= k.open) green else red
                    val cx = x(j)
                    drawLine(col, Offset(cx, y(k.high)), Offset(cx, y(k.low)), strokeWidth = 1.5f)
                    val top = y(max(k.open, k.close))
                    val bot = y(min(k.open, k.close))
                    val bw = max(cw * 0.7f, 1f)
                    drawRect(
                        col,
                        topLeft = Offset(cx - bw / 2f, top),
                        size = Size(bw, max(bot - top, 1f))
                    )
                }

                // Zones Supply / Demand : bordures de couleur (vert = demande, rouge = offre, orange pointillé = déjà testée)
                if (showSd) {
                    val testedDash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    for (bx in d.sdBoxes) {
                        if (bx.startIdx >= end) continue
                        if (bx.high < lo || bx.low > hi) continue
                        val x0 = if (bx.startIdx < start) 0f else x(bx.startIdx)
                        val top = y(bx.high)
                        val hh = max(y(bx.low) - top, 2f)
                        val bc = if (bx.tested) testedColor else if (bx.buy) green else red
                        drawRect(
                            bc.copy(alpha = 0.07f),
                            topLeft = Offset(x0, top),
                            size = Size(plotW - x0, hh)
                        )
                        drawRect(
                            bc,
                            topLeft = Offset(x0, top),
                            size = Size(plotW - x0, hh),
                            style = Stroke(width = if (bx.tested) 2f else 4f, pathEffect = if (bx.tested) testedDash else null)
                        )
                        val txt = (if (bx.buy) "Demande" else "Offre") + (if (bx.tested) " testée" else "")
                        val lay = tm.measure(txt, TextStyle(color = bc, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                        val ty = top + 3f
                        drawRect(
                            Color(0xFF0E1117).copy(alpha = 0.8f),
                            topLeft = Offset(x0 + 4f, ty),
                            size = Size(lay.size.width.toFloat() + 4f, lay.size.height.toFloat())
                        )
                        drawText(lay, topLeft = Offset(x0 + 6f, ty))
                    }
                }

                // Lignes des doubles sommets / creux
                if (showDouble) {
                    for (pl in d.patLines) {
                        if (pl.i2 < start || pl.i1 >= end) continue
                        drawLine(
                            if (pl.buy) green else red,
                            Offset(x(pl.i1), y(pl.p1)),
                            Offset(x(pl.i2), y(pl.p2)),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        )
                    }
                }

                // Marques BBMA, sweeps et patterns, empilées sous (haussier) ou au-dessus (baissier) de la bougie
                fun kindOn(kind: Char): Boolean = when (kind) {
                    'E' -> showE
                    'M' -> showM
                    'R' -> showR
                    '1', '2' -> showCs
                    '3' -> showCsa
                    'S' -> showLiq
                    'G' -> showEng
                    'P' -> showPin
                    'D' -> showDoji
                    'I' -> showInside
                    'T' -> showStar
                    'W' -> showDouble
                    else -> false
                }
                val slots = HashMap<Int, Int>()
                for (mk in d.marks) {
                    if (mk.idx < start || mk.idx >= end) continue
                    if (!kindOn(mk.kind)) continue
                    val key = mk.idx * 2 + (if (mk.buy) 1 else 0)
                    val slot = slots[key] ?: 0
                    slots[key] = slot + 1
                    val cx = x(mk.idx)
                    val cd = d.c[mk.idx]
                    val yy = if (mk.buy) y(cd.low) + 12f + slot * 22f else y(cd.high) - 12f - slot * 22f
                    if (mk.kind == '1' || mk.kind == '2' || mk.kind == '3') {
                        val dc = when (mk.kind) {
                            '1' -> Color(0xFFE040FB)
                            '2' -> Color(0xFFFFA726)
                            else -> Color(0xFFB0BEC5)
                        }
                        val rad = when (mk.kind) {
                            '1' -> 7f
                            '2' -> 5.5f
                            else -> 4f
                        }
                        drawCircle(dc, radius = rad, center = Offset(cx, yy))
                    } else {
                        val tc = when {
                            mk.kind == 'S' -> Color(0xFFFF80AB)
                            mk.kind == 'D' || mk.kind == 'I' -> neutralGray
                            mk.buy -> green
                            else -> red
                        }
                        val lay = tm.measure(
                            markText(mk.kind, mk.buy),
                            TextStyle(color = tc, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        )
                        drawText(
                            lay,
                            topLeft = Offset(cx - lay.size.width / 2f, yy - lay.size.height / 2f)
                        )
                    }
                }

                // Niveaux du signal
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                fun hline(p: Double, color: Color) {
                    val yy = y(p)
                    drawLine(color, Offset(0f, yy), Offset(plotW, yy), strokeWidth = 2.5f, pathEffect = dash)
                }
                if (entry != null) hline(entry, Color.White)
                if (slP != null) hline(slP, red)
                if (tp1P != null) hline(tp1P, green)
                if (tp2P != null) hline(tp2P, tealTp2)

                // Flèche sur la bougie du signal
                if (buy != null) {
                    val idx = d.c.indexOfFirst { it.time == spec.time }
                    if (idx in start until end) {
                        val cx = x(idx)
                        val s = max(cw * 1.8f, 22f)
                        val path = Path()
                        if (buy) {
                            val base = y(d.c[idx].low) + 8f
                            path.moveTo(cx, base)
                            path.lineTo(cx - s / 2f, base + s)
                            path.lineTo(cx + s / 2f, base + s)
                        } else {
                            val base = y(d.c[idx].high) - 8f
                            path.moveTo(cx, base)
                            path.lineTo(cx - s / 2f, base - s)
                            path.lineTo(cx + s / 2f, base - s)
                        }
                        path.close()
                        drawPath(path, sideColor)
                    }
                }

                // Prix actuel : point sur la dernière bougie
                if (end == n) {
                    drawCircle(Color.White, radius = 6f, center = Offset(x(n - 1), y(price)))
                }

                // Marge de droite : fond, graduations (sans chevaucher les étiquettes) et étiquettes
                drawRect(Color(0xFF0E1117), topLeft = Offset(plotW, 0f), size = Size(labelW, h))
                for (q in 1..4) {
                    val p = lo + (hi - lo) * q / 5.0
                    if (specials.any { abs(y(it) - y(p)) < 16f }) continue
                    val layout = tm.measure(fmtPrice(p, dec), TextStyle(color = Color(0xFF90A4AE), fontSize = 10.sp))
                    drawText(layout, topLeft = Offset(plotW + 4f, y(p) - layout.size.height / 2f))
                }
                for (lv in srShown) {
                    label(lv.price, Color.Black, if (lv.price < price) supportColor else resistColor)
                }
                if (vp != null) {
                    label(vahP, Color.White, Color(0xFF1565C0))
                    label(valP, Color.White, Color(0xFF1565C0))
                    label(pocP, Color.Black, pocColor)
                }
                for (lv in ents) {
                    label(lv.tp2, Color.Black, tealTp2)
                    label(lv.tp1, Color.White, Color(0xFF1B5E20))
                    label(lv.sl, Color.White, Color(0xFFB71C1C))
                    label(lv.entry, Color.Black, entryBlue)
                }
                if (tp2P != null) label(tp2P, Color.Black, tealTp2)
                if (tp1P != null) label(tp1P, Color.Black, green)
                if (slP != null) label(slP, Color.White, red)
                if (entry != null) label(entry, Color.Black, Color.White)
                label(price, Color.White, Color(0xFF546E7A))
            }

            val dec = spec.decimals
            val entryV = spec.entry
            val slV = spec.sl
            val tp1V = spec.tp1
            val tp2V = spec.tp2
            if (entryV != null && slV != null && tp1V != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Entrée " + fmtPrice(entryV, dec), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("SL " + fmtPrice(slV, dec), fontSize = 13.sp, color = red, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("TP1 " + fmtPrice(tp1V, dec), fontSize = 13.sp, color = green, fontWeight = FontWeight.SemiBold)
                    if (tp2V != null) {
                        Text("TP2 " + fmtPrice(tp2V, dec), fontSize = 13.sp, color = tealTp2, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Détail des entrées en attente de ce marché
            if (showEntries) {
                if (shownEntries.isEmpty()) {
                    Text(
                        "Aucune entrée proche (2 ATR au plus) pour ce marché. " +
                            "Active « Lointaines » pour voir les plus éloignées.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (lv in shownEntries) {
                    val col = if (lv.buy) green else red
                    Text(
                        (if (lv.buy) "Achat limite " else "Vente limite ") + fmtPrice(lv.entry, dec) +
                            " · SL " + fmtPrice(lv.sl, dec) +
                            " · TP1 " + fmtPrice(lv.tp1, dec) +
                            " · TP2 " + fmtPrice(lv.tp2, dec),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = col
                    )
                    Text(
                        "Zone " + lv.kind + " · à " + String.format(Locale.US, "%.1f", lv.distAtr) +
                            " ATR du prix · formée il y a " + lv.ago + " bougies",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        lv.trendLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (lv.trend >= 1) green else if (lv.trend == 0) neutralGray else Color(0xFFFFB74D)
                    )
                }
            }

            // Détail du profil de volume
            if (showVp) {
                val vp = vpData
                if (vp == null) {
                    Text(
                        "Pas assez de bougies pour le profil.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Profil sur les " + vpRange + " dernières bougies · POC " +
                            fmtPrice(vp.lo + (vp.poc + 0.5) * vp.step, dec) +
                            " · zone de valeur " + fmtPrice(vp.lo + vp.vaLow * vp.step, dec) +
                            " à " + fmtPrice(vp.lo + (vp.vaHigh + 1) * vp.step, dec),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = pocColor
                    )
                }
            }

            val bbmaLine = d.bbmaLine
            if (bbmaLine != null) {
                Text(bbmaLine, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }

            var legend = ""
            if (showEntries) legend += "Entrées : zones colorées, ligne bleue = entrée limite, rouge = SL, verte = TP1 · "
            if (showBb) legend += "Bollinger (bleu) · "
            if (showMa) legend += "MA5 (orange) et MA10 (jaune) · "
            if (showEma) legend += "EMA50 (violet) · "
            if (showZones) legend += "Zones : fond vert = discount, fond rouge = premium, bloc coloré = zone SMC · "
            if (showSd) legend += "Supply/Demand : bordure verte = demande, rouge = offre, orange pointillé = zone déjà testée · "
            if (showSr) legend += "S/R : lignes épaisses (plus épaisse = niveau plus souvent respecté), vert clair = support, orange = résistance · "
            if (showVp) {
                legend += "Profil (plage fixe) : barres = temps passé à chaque prix, jaune = POC, bleu = zone de valeur 70 %. " +
                    "Deriv ne fournit pas le volume réel · "
            }
            if (showE || showM || showR) {
                legend += "Lettres : "
                if (showE) legend += "E = Extrême  "
                if (showM) legend += "M = MHV  "
                if (showR) legend += "R = Re-entry  "
                legend += "(vert sous la bougie = haussier, rouge au-dessus = baissier) · "
            }
            if (showCs) legend += "Points : CSM (magenta, gros), CSAK (orange) · "
            if (showCsa) legend += "CSA (gris, petit) · "
            if (showLiq) legend += "Liquidité : EQH/EQL = hauts/bas égaux, Liq. haute/basse = niveaux non touchés, S = sweep · "
            if (showEng || showPin || showDoji || showInside || showStar || showDouble) {
                legend += "Patterns : "
                if (showEng) legend += "EN = Engulfing  "
                if (showPin) legend += "PB = Pin bar (marteau / étoile filante)  "
                if (showDoji) legend += "DJ = Doji  "
                if (showInside) legend += "IB = Inside bar  "
                if (showStar) legend += "ST = Étoile du matin/soir  "
                if (showDouble) legend += "DC = Double creux, DS = Double sommet  "
                legend += "(vert sous la bougie = haussier, rouge au-dessus = baissier, gris = neutre) · "
            }
            if (legend.isNotEmpty()) {
                Text(legend.removeSuffix(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val steps = listOf(30, 50, 80, 120, 200)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val i = steps.indexOfLast { it < visible }
                    if (i >= 0) visible = steps[i]
                }) { Text("Zoom +") }
                OutlinedButton(onClick = {
                    val nx = steps.firstOrNull { it > visible }
                    if (nx != null) visible = nx
                }) { Text("Zoom −") }
            }
            Text(
                "Fais glisser le graphique vers la droite pour remonter dans le temps. " +
                    "Le point blanc marque le prix actuel" +
                    (if (buy != null && spec.time != 0L) ", la flèche la bougie du signal." else ".") +
                    " Les marques et les patterns ne concernent que les bougies terminées. " +
                    "Les patterns sont informatifs : ils ne changent pas le score des signaux. " +
                    "Si c'est trop chargé, fais Zoom + ou active un bouton à la fois.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}
