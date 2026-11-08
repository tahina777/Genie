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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Ce qu'il faut pour dessiner le graphique d'un signal ou d'une ligne du journal. */
class ChartSpec(
    val name: String,
    val code: String,
    val tfLabel: String,
    val tfSeconds: Int,
    val buy: Boolean,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
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
    val pdLow: Double
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
        pdLow
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
    val sideColor = if (spec.buy) green else red

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
            Text(
                if (spec.buy) "ACHAT" else "VENTE",
                color = sideColor,
                fontWeight = FontWeight.Bold
            )
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
                    .height(360.dp)
                    .clipToBounds()
                    .pointerInput(d) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            val cw = size.width.toFloat() / visible
                            val maxShift = (d.c.size - visible).coerceAtLeast(0).toFloat()
                            shift = (shift + dragAmount / cw).coerceIn(0f, maxShift)
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val n = d.c.size
                val vis = min(visible, n)
                val end = n - shift.toInt().coerceIn(0, max(0, n - vis))
                val start = max(0, end - vis)
                val cw = w / vis

                var hi = Double.NEGATIVE_INFINITY
                var lo = Double.POSITIVE_INFINITY
                for (j in start until end) {
                    hi = max(hi, d.c[j].high)
                    lo = min(lo, d.c[j].low)
                }
                for (p in listOf(spec.entry, spec.sl, spec.tp1)) {
                    hi = max(hi, p)
                    lo = min(lo, p)
                }
                val t2 = spec.tp2
                if (t2 != null) {
                    hi = max(hi, t2)
                    lo = min(lo, t2)
                }
                val pad = (hi - lo) * 0.05
                hi += pad
                lo -= pad
                if (hi <= lo) return@Canvas

                fun y(p: Double): Float = (h - ((p - lo) / (hi - lo)) * h).toFloat()
                fun x(j: Int): Float = (j - start + 0.5f) * cw

                // Zones : Premium / Discount, Supply & Demand, SMC, niveaux
                if (showZones) {
                    val mid = (d.pdHigh + d.pdLow) / 2.0
                    drawRect(
                        Color(0xFFEF5350).copy(alpha = 0.07f),
                        topLeft = Offset(0f, y(d.pdHigh)),
                        size = Size(w, y(mid) - y(d.pdHigh))
                    )
                    drawRect(
                        Color(0xFF26A69A).copy(alpha = 0.07f),
                        topLeft = Offset(0f, y(mid)),
                        size = Size(w, y(d.pdLow) - y(mid))
                    )
                    drawLine(
                        Color.Gray,
                        Offset(0f, y(mid)),
                        Offset(w, y(mid)),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                    for (z in d.zones) {
                        val zc = if (z.buy) Color(0xFF26A69A) else Color(0xFFEF5350)
                        drawRect(
                            zc.copy(alpha = 0.20f),
                            topLeft = Offset(0f, y(z.high)),
                            size = Size(w, max(y(z.low) - y(z.high), 2f))
                        )
                    }
                    for (lv in d.levels) {
                        if (lv in lo..hi) {
                            drawLine(
                                Color(0xFF9E9E9E),
                                Offset(0f, y(lv)),
                                Offset(w, y(lv)),
                                strokeWidth = 1.5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
                            )
                        }
                    }
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
                    drawLine(color, Offset(0f, yy), Offset(w, yy), strokeWidth = 2.5f, pathEffect = dash)
                }
                hline(spec.entry, Color.White)
                hline(spec.sl, red)
                hline(spec.tp1, green)
                if (t2 != null) hline(t2, Color(0xFF80CBC4))

                // Flèche sur la bougie du signal
                val idx = d.c.indexOfFirst { it.time == spec.time }
                if (idx in start until end) {
                    val cx = x(idx)
                    val s = max(cw * 1.2f, 14f)
                    val path = Path()
                    if (spec.buy) {
                        val base = y(d.c[idx].low) + 6f
                        path.moveTo(cx, base)
                        path.lineTo(cx - s / 2f, base + s)
                        path.lineTo(cx + s / 2f, base + s)
                    } else {
                        val base = y(d.c[idx].high) - 6f
                        path.moveTo(cx, base)
                        path.lineTo(cx - s / 2f, base - s)
                        path.lineTo(cx + s / 2f, base - s)
                    }
                    path.close()
                    drawPath(path, sideColor)
                }
            }

            val dec = spec.decimals
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Entrée " + fmtPrice(spec.entry, dec), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("SL " + fmtPrice(spec.sl, dec), fontSize = 13.sp, color = red, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("TP1 " + fmtPrice(spec.tp1, dec), fontSize = 13.sp, color = green, fontWeight = FontWeight.SemiBold)
                val tp2 = spec.tp2
                if (tp2 != null) {
                    Text("TP2 " + fmtPrice(tp2, dec), fontSize = 13.sp, color = Color(0xFF80CBC4), fontWeight = FontWeight.SemiBold)
                }
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
                    "La flèche marque la bougie du signal.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}
