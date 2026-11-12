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

private class ChartZone(val low: Double, val high: Double, val buy: Boolean)

/**
 * Marque sur une bougie. kind : E = Extrême, M = MHV, R = Re-entry,
 * 1 = CSM, 2 = CSAK, 3 = CSA, S = sweep de liquidité.
 * buy = true : marque haussière, dessinée sous la bougie ; false : au-dessus.
 */
private class Mark(val idx: Int, val kind: Char, val buy: Boolean)

private class LiqLine(val price: Double, val fromIdx: Int, val label: String, val high: Boolean)

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
    val levels: List<Double>,
    val pdHigh: Double,
    val pdLow: Double,
    val bbmaLine: String?,
    val marks: List<Mark>,
    val liq: List<LiqLine>,
    val entries: List<EntryLevel>
)

private fun fmtPrice(v: Double, d: Int): String =
    String.format(Locale.US, "%." + d + "f", v)

/** Points hauts/bas respectés au moins 2 fois, regroupés par proximité. */
private fun chartLevels(c: List<Candle>, atr: Double): List<Double> {
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
    val out = ArrayList<Double>()
    var group = ArrayList<Double>()
    for (p in pts) {
        if (group.isNotEmpty() && p - group.last() > 0.3 * atr) {
            if (group.size >= 2) out.add(group.average())
            group = ArrayList()
        }
        group.add(p)
    }
    if (group.size >= 2) out.add(group.average())
    return out
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

private fun buildData(c: List<Candle>): ChartData {
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
        val sd = SdCalc(c).detect(atr)
        if (sd != null) zones.add(ChartZone(sd.low, sd.high, sd.dir == Direction.BUY))
    } catch (e: Exception) {
        // zone indisponible
    }
    try {
        val smc = SmcCalc(c).detect()
        if (smc != null) zones.add(ChartZone(smc.zone.low, smc.zone.high, smc.dir == Direction.BUY))
    } catch (e: Exception) {
        // zone indisponible
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
    var ents: List<EntryLevel> = emptyList()
    try {
        ents = Entries.compute(c)
    } catch (e: Exception) {
        // entrées indisponibles
    }

    return ChartData(
        c, upA, midA, lowA, m5h, m5l, m10h, m10l, e50A,
        zones, chartLevels(c, atr), pdHigh, pdLow, marksLine(c), marks, liq, ents
    )
}

@Composable
fun ChartScreen(spec: ChartSpec, onBack: () -> Unit) {
    var data by remember(spec) { mutableStateOf<ChartData?>(null) }
    var error by remember(spec) { mutableStateOf<String?>(null) }
    var showBb by remember { mutableStateOf(true) }
    var showMa by remember { mutableStateOf(true) }
    var showEma by remember { mutableStateOf(false) }
    var showZones by remember { mutableStateOf(false) }
    var showE by remember { mutableStateOf(false) }
    var showM by remember { mutableStateOf(false) }
    var showR by remember { mutableStateOf(false) }
    var showCs by remember { mutableStateOf(false) }
    var showLiq by remember { mutableStateOf(false) }
    var showEntries by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(80) }
    var shift by remember { mutableStateOf(0f) }
    val tm = rememberTextMeasurer()

    LaunchedEffect(spec) {
        try {
            val raw = Deriv.candles(spec.code, spec.tfSeconds, 300)
            if (raw.size < 60) {
                error = "Pas assez de bougies pour dessiner le graphique."
            } else {
                data = withContext(Dispatchers.Default) { buildData(raw) }
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
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showEntries, onClick = { showEntries = !showEntries }, label = { Text("Entrées") })
                FilterChip(selected = showBb, onClick = { showBb = !showBb }, label = { Text("Bollinger") })
                FilterChip(selected = showMa, onClick = { showMa = !showMa }, label = { Text("MA5/10") })
                FilterChip(selected = showEma, onClick = { showEma = !showEma }, label = { Text("EMA50") })
                FilterChip(selected = showZones, onClick = { showZones = !showZones }, label = { Text("Zones") })
                FilterChip(selected = showE, onClick = { showE = !showE }, label = { Text("Extrême") })
                FilterChip(selected = showM, onClick = { showM = !showM }, label = { Text("MHV") })
                FilterChip(selected = showR, onClick = { showR = !showR }, label = { Text("Re-entry") })
                FilterChip(selected = showCs, onClick = { showCs = !showCs }, label = { Text("CS") })
                FilterChip(selected = showLiq, onClick = { showLiq = !showLiq }, label = { Text("Liquidité") })
            }

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

                val entry = spec.entry
                val slP = spec.sl
                val tp1P = spec.tp1
                val tp2P = spec.tp2
                val zl = spec.zoneLow
                val zh = spec.zoneHigh
                val ents: List<EntryLevel> = if (showEntries) d.entries else emptyList()

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

                // Zones : Premium / Discount, Supply & Demand, SMC, niveaux
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
                    for (lv in d.levels) {
                        if (lv in lo..hi) {
                            drawLine(
                                Color(0xFF9E9E9E),
                                Offset(0f, y(lv)),
                                Offset(plotW, y(lv)),
                                strokeWidth = 1.5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
                            )
                        }
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

                // Liquidité : hauts/bas égaux et niveaux non touchés
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
                        drawText(lay, topLeft = Offset(x0 + 4f, y(ln.price) - lay.size.height - 2f))
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

                // Marques BBMA et sweeps, empilées sous (haussier) ou au-dessus (baissier) de la bougie
                fun kindOn(kind: Char): Boolean = when (kind) {
                    'E' -> showE
                    'M' -> showM
                    'R' -> showR
                    '1', '2', '3' -> showCs
                    'S' -> showLiq
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
                        val txt = mk.kind.toString()
                        val tc = if (mk.kind == 'S') Color(0xFFFF80AB) else if (mk.buy) green else red
                        val lay = tm.measure(
                            txt,
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
                val lastC = d.c.last()
                if (end == n) {
                    drawCircle(Color.White, radius = 6f, center = Offset(x(n - 1), y(lastC.close)))
                }

                // Marge de droite : fond, échelle des prix et étiquettes
                drawRect(Color(0xFF0E1117), topLeft = Offset(plotW, 0f), size = Size(labelW, h))
                for (q in 1..4) {
                    val p = lo + (hi - lo) * q / 5.0
                    val layout = tm.measure(fmtPrice(p, dec), TextStyle(color = Color(0xFF90A4AE), fontSize = 10.sp))
                    drawText(layout, topLeft = Offset(plotW + 4f, y(p) - layout.size.height / 2f))
                }
                for (lv in ents) {
                    label(lv.tp1, Color.White, Color(0xFF1B5E20))
                    label(lv.sl, Color.White, Color(0xFFB71C1C))
                    label(lv.entry, Color.Black, entryBlue)
                }
                if (tp2P != null) label(tp2P, Color.Black, tealTp2)
                if (tp1P != null) label(tp1P, Color.Black, green)
                if (slP != null) label(slP, Color.White, red)
                if (entry != null) label(entry, Color.Black, Color.White)
                label(lastC.close, Color.White, Color(0xFF546E7A))
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
                if (d.entries.isEmpty()) {
                    Text(
                        "Aucune entrée en attente pour ce marché dans ce timeframe.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (lv in d.entries) {
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
            if (showZones) legend += "Zones : vert = demande/discount, rouge = offre/premium, pointillés gris = niveaux · "
            if (showE || showM || showR) {
                legend += "Lettres : "
                if (showE) legend += "E = Extrême  "
                if (showM) legend += "M = MHV  "
                if (showR) legend += "R = Re-entry  "
                legend += "(vert sous la bougie = haussier, rouge au-dessus = baissier) · "
            }
            if (showCs) legend += "Points CS : CSM (magenta, gros), CSAK (orange), CSA (gris, petit) · "
            if (showLiq) legend += "Liquidité : EQH/EQL = hauts/bas égaux, Liq. haute/basse = niveaux non touchés, S = sweep · "
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
                    " Les marques BBMA et les sweeps ne concernent que les bougies terminées. " +
                    "Si c'est trop chargé, fais Zoom + ou active un bouton à la fois.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}
