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
import kotlin.math.max
import kotlin.math.min

/** Ce qu'il faut pour dessiner un graphique : celui d'un signal, d'une ligne du journal ou d'un marché libre. */
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
    val setup: String
)

fun chartSpecOf(s: Signal) = ChartSpec(
    s.market.name, s.market.code, s.tf.label, s.tf.seconds, s.dir == Direction.BUY,
    s.entry, s.sl, s.tp1, s.tp2, s.time, s.market.decimals, s.setup
)

fun chartSpecOf(e: JEntry) = ChartSpec(
    e.market, e.code, e.tf, e.tfSeconds, e.buy,
    e.entry, e.sl, e.tp1, null, e.time, e.decimals, e.setup
)

/** Graphique libre d'un marché, sans signal. */
fun plainChartSpec(m: Market, tf: Tf) = ChartSpec(
    m.name, m.code, tf.label, tf.seconds, null,
    null, null, null, null, 0L, m.decimals, "Graphique libre"
)

private class ChartZone(val low: Double, val high: Double, val buy: Boolean)

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
    val marks: String?
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

    return ChartData(
        c,
        DoubleArray(n) { bb.up[it] },
        DoubleArray(n) { bb.mid[it] },
        DoubleArray(n) { bb.low[it] },
        DoubleArray(n) { w5h[it] },
        DoubleArray(n) { w5l[it] },
        DoubleArray(n) { w10h[it] },
        DoubleArray(n) { w10l[it] },
        DoubleArray(n) { e50[it] },
        zones,
        chartLevels(c, atr),
        pdHigh,
        pdLow,
        marksLine(c)
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
                FilterChip(selected = showBb, onClick = { showBb = !showBb }, label = { Text("Bollinger") })
                FilterChip(selected = showMa, onClick = { showMa = !showMa }, label = { Text("MA5/10") })
                FilterChip(selected = showEma, onClick = { showEma = !showEma }, label = { Text("EMA50") })
                FilterChip(selected = showZones, onClick = { showZones = !showZones }, label = { Text("Zones") })
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

                // Flèche sur la bougie du signal (plus grande)
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

            val marks = d.marks
            if (marks != null) {
                Text(marks, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }

            var legend = ""
            if (showBb) legend += "Bollinger (bleu) · "
            if (showMa) legend += "MA5 (orange) et MA10 (jaune) · "
            if (showEma) legend += "EMA50 (violet) · "
            if (showZones) legend += "Zones : vert = demande/discount, rouge = offre/premium, pointillés gris = niveaux · "
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
                    (if (buy != null) ", la flèche la bougie du signal." else "."),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}
