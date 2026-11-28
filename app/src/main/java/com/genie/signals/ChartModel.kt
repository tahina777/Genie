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

/** Zone SMC détectée par le moteur. */
internal class ChartZone(val low: Double, val high: Double, val buy: Boolean)

internal class ChartData(
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
    val patLines: List<PatLine>,
    val qm: List<QmSetup>,
    val snr: List<SnrSetup>,
    val hyb: List<HybridSetup>,
    val base: List<BaseSetup>,
    val pat: List<PatSetup>,
    val re: List<ReSetup>,
    val rePend: BbmaPending?,
    val smcc: List<SmcConcept>
)

internal fun fmtPrice(v: Double, d: Int): String =
    String.format(Locale.US, "%." + d + "f", v)

/** Texte affiché sur la bougie pour les marques en lettres. */
internal fun markText(kind: Char, buy: Boolean): String = when (kind) {
    'G' -> "EN"
    'P' -> "PB"
    'D' -> "DJ"
    'I' -> "IB"
    'T' -> "ST"
    'W' -> if (buy) "DC" else "DS"
    'N' -> "RMA"
    'R' -> "RMB"
    'Z' -> "ZZL"
    else -> kind.toString()
}

internal fun buildData(c: List<Candle>, htf: List<Candle>?): ChartData {
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

    var qm: List<QmSetup> = emptyList()
    var snr: List<SnrSetup> = emptyList()
    var hyb: List<HybridSetup> = emptyList()
    var base: List<BaseSetup> = emptyList()
    var pat: List<PatSetup> = emptyList()
    try {
        val qcs = c.map { QmCandle(it.time, it.open, it.high, it.low, it.close) }
        val lastClose = c.last().close
        // Seulement les setups actifs dont l'entrée est à 5 ATR du prix au plus
        qm = QmSetups.detect(qcs).filter { abs(it.entry - lastClose) <= 5.0 * atr }
        snr = SnrSetups.detect(qcs).filter { abs(it.entry - lastClose) <= 5.0 * atr }
        hyb = HybridSetups.detect(qcs).filter { abs(it.entry - lastClose) <= 5.0 * atr }
        base = BaseSetups.detect(qcs).filter { abs(it.entry - lastClose) <= 5.0 * atr }
        pat = PatSetups.detect(qcs).filter { abs(it.entry - lastClose) <= 5.0 * atr }
    } catch (e: Exception) {
        // setups QM / SNRC indisponibles
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
        patLines = patLines,
        qm = qm,
        snr = snr,
        hyb = hyb,
        base = base,
        pat = pat,
        re = try { buildReSetup(c, marks, atr) } catch (e: Exception) { emptyList() },
        rePend = try { BbmaCalc(c.dropLast(1)).pending() } catch (e: Exception) { null },
        smcc = try { SmcConcepts.detect(c) } catch (e: Exception) { emptyList() }
    )
}
