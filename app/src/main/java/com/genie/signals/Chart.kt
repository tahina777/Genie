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
    'N' -> "RMA"
    'R' -> "RMB"
    'Z' -> "ZZL"
    else -> kind.toString()
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
    var showFib by remember { mutableStateOf(false) }
    var vpRange by remember { mutableStateOf(100) }
    var showE by remember { mutableStateOf(false) }
    var showM by remember { mutableStateOf(false) }
    var showR by remember { mutableStateOf(false) }
    var showRma by remember { mutableStateOf(false) }
    var showZzl by remember { mutableStateOf(false) }
    var showCs by remember { mutableStateOf(false) }
    var showCsa by remember { mutableStateOf(false) }
    var showEng by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var showDoji by remember { mutableStateOf(false) }
    var showInside by remember { mutableStateOf(false) }
    var showStar by remember { mutableStateOf(false) }
    var showDouble by remember { mutableStateOf(false) }
    var showFig by remember { mutableStateOf(false) }
    var showSw by remember { mutableStateOf(false) }
    var showQmr by remember { mutableStateOf(false) }
    var showQmc by remember { mutableStateOf(false) }
    var showQmm by remember { mutableStateOf(false) }
    var showQm2p by remember { mutableStateOf(false) }
    var showSnr1 by remember { mutableStateOf(false) }
    var showSnr2 by remember { mutableStateOf(false) }
    var showSnr3 by remember { mutableStateOf(false) }
    var showHy1 by remember { mutableStateOf(false) }
    var showHy2 by remember { mutableStateOf(false) }
    var showRbr by remember { mutableStateOf(false) }
    var showDbd by remember { mutableStateOf(false) }
    var showRbd by remember { mutableStateOf(false) }
    var showDbr by remember { mutableStateOf(false) }
    var showBs1 by remember { mutableStateOf(false) }
    var showBs2 by remember { mutableStateOf(false) }
    var showCk1 by remember { mutableStateOf(false) }
    var showCk2 by remember { mutableStateOf(false) }
    var showFvg by remember { mutableStateOf(false) }
    var chipTab by remember { mutableStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }
    var showBpr by remember { mutableStateOf(false) }
    var showIdm by remember { mutableStateOf(false) }
    var showPoi by remember { mutableStateOf(false) }
    var showCrt by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(80) }
    var shift by remember { mutableStateOf(0f) }
    val tm = rememberTextMeasurer()

    // Structure de marché D1 / H4 / H1 (chargée à part : le graphique s'affiche sans l'attendre)
    var structs by remember(spec) { mutableStateOf<List<StructureState>>(emptyList()) }
    LaunchedEffect(spec) {
        val res = ArrayList<StructureState>()
        for (t in listOf(Tf.D1, Tf.H4, Tf.H1)) {
            try {
                val cs = Deriv.candles(spec.code, t.seconds, 200)
                res.add(MarketStructure.analyze(t.label, cs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // on saute ce timeframe
            }
        }
        structs = res
    }

    // BBMA multi timeframe : D1, H4, H1, M15, M5 et combos (REE, RME, REM, RRE, RMM)
    var bbmaTfs by remember(spec) { mutableStateOf<List<String>>(emptyList()) }
    var bbmaCombos by remember(spec) { mutableStateOf<List<String>>(emptyList()) }
    var smcTfs by remember(spec) { mutableStateOf<List<String>>(emptyList()) }
    var smcCombos by remember(spec) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(spec) {
        val closedMap = HashMap<Tf, List<Candle>>()
        val texts = ArrayList<String>()
        for (t in listOf(Tf.D1, Tf.H4, Tf.H1, Tf.M15, Tf.M5)) {
            try {
                val raw = Deriv.candles(spec.code, t.seconds, 300)
                val closed = raw.dropLast(1)
                closedMap[t] = closed
                texts.add(bbmaTfText(t, closed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // on saute ce timeframe
            }
        }
        val combos = ArrayList<String>()
        for (tr in Combos.TRIPLES) {
            try {
                if (tr.any { closedMap[it] == null }) continue
                val mt = Combos.match(tr.map { closedMap[it]!! }, tr) ?: continue
                combos.add(
                    "Combo " + mt.code + " " + tr.joinToString(" · ") { it.label } +
                        (if (mt.dir == Direction.BUY) " achat" else " vente")
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // série indisponible
            }
        }
        bbmaTfs = texts
        bbmaCombos = combos
        try {
            val st = ArrayList<String>()
            for (t in listOf(Tf.H4, Tf.H1, Tf.M15, Tf.M5)) {
                val cl = closedMap[t] ?: continue
                st.add(SmcCombos.tfText(t, cl))
            }
            val sc = ArrayList<String>()
            for (tr in SmcCombos.TRIPLES) {
                if (tr.any { closedMap[it] == null }) continue
                val mt = SmcCombos.match(tr.map { closedMap[it]!! }, tr) ?: continue
                sc.add(
                    "Combo SMC " + tr.joinToString(" \u00b7 ") { it.label } +
                        (if (mt.dir == Direction.BUY) " achat" else " vente")
                )
            }
            smcTfs = st
            smcCombos = sc
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // SMC multi timeframe indisponible
        }
    }

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

        if (structs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (st in structs) {
                    val sc = if (st.trend > 0) green else if (st.trend < 0) red else neutralGray
                    val arrow = if (st.trend > 0) "▲" else if (st.trend < 0) "▼" else "■"
                    Text(st.tfLabel + " " + arrow, color = sc, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            val al = MarketStructure.alignment(structs)
            if (showInfo) Text(
                (if (al > 0) "Structure alignée haussière sur D1, H4 et H1"
                else if (al < 0) "Structure alignée baissière sur D1, H4 et H1"
                else "Structure non alignée") +
                    " · " + structs.joinToString(" · ") { it.tfLabel + " : " + it.detail },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (bbmaTfs.isNotEmpty()) {
            if (showInfo) Text(
                "BBMA · " + bbmaTfs.joinToString(" · "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (cm in bbmaCombos) {
                Text(cm, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (smcTfs.isNotEmpty()) {
            if (showInfo) Text(
                "SMC \u00b7 " + smcTfs.joinToString(" \u00b7 "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (cm in smcCombos) {
                Text(cm, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }

        val d = data
        val err = error
        if (d == null) {
            if (err != null) {
                Text(err, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            // Onglets : un seul groupe de boutons à la fois (touche à nouveau l'onglet pour le fermer)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showInfo, onClick = { showInfo = !showInfo }, label = { Text("Infos") })
                val tabNames = listOf("Base", "Zones", "BBMA", "Patterns", "Setups", "SMC")
                for ((k, nm) in tabNames.withIndex()) {
                    FilterChip(
                        selected = chipTab == k,
                        onClick = { chipTab = if (chipTab == k) -1 else k },
                        label = { Text(nm) }
                    )
                }
            }
            // Rangée 1 : entrées, indicateurs, liquidité
            if (chipTab == 0) Row(
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
            if (chipTab == 1) Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showZones, onClick = { showZones = !showZones }, label = { Text("Zones") })
                FilterChip(selected = showSd, onClick = { showSd = !showSd }, label = { Text("Supply/Demand") })
                FilterChip(selected = showSr, onClick = { showSr = !showSr }, label = { Text("S/R") })
                FilterChip(selected = showFib, onClick = { showFib = !showFib }, label = { Text("Fibonacci") })
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
            if (chipTab == 2) Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showE, onClick = { showE = !showE }, label = { Text("Extrême") })
                FilterChip(selected = showM, onClick = { showM = !showM }, label = { Text("MHV") })
                FilterChip(selected = showRma, onClick = { showRma = !showRma }, label = { Text("Re-entry MA") })
                FilterChip(selected = showR, onClick = { showR = !showR }, label = { Text("Re-entry Mid") })
                FilterChip(selected = showZzl, onClick = { showZzl = !showZzl }, label = { Text("ZZL") })
                FilterChip(selected = showCs, onClick = { showCs = !showCs }, label = { Text("CSM/CSAK") })
                FilterChip(selected = showCsa, onClick = { showCsa = !showCsa }, label = { Text("CSA") })
            }
            // Rangée 4 : patterns
            if (chipTab == 3) Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showEng, onClick = { showEng = !showEng }, label = { Text("Engulfing") })
                FilterChip(selected = showPin, onClick = { showPin = !showPin }, label = { Text("Pin bar") })
                FilterChip(selected = showDoji, onClick = { showDoji = !showDoji }, label = { Text("Doji") })
                FilterChip(selected = showInside, onClick = { showInside = !showInside }, label = { Text("Inside bar") })
                FilterChip(selected = showStar, onClick = { showStar = !showStar }, label = { Text("Étoile") })
                FilterChip(selected = showDouble, onClick = { showDouble = !showDouble }, label = { Text("Double top/bottom") })
                FilterChip(selected = showFig, onClick = { showFig = !showFig }, label = { Text("Figures") })
                FilterChip(selected = showSw, onClick = { showSw = !showSw }, label = { Text("HH/HL/LH/LL") })
            }
            // Rangée 5 : setups de la fiche (QM et SNRC)
            if (chipTab == 4) Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showQmr, onClick = { showQmr = !showQmr }, label = { Text("QMR") })
                FilterChip(selected = showQmc, onClick = { showQmc = !showQmc }, label = { Text("QMC") })
                FilterChip(selected = showQmm, onClick = { showQmm = !showQmm }, label = { Text("QMM") })
                FilterChip(selected = showQm2p, onClick = { showQm2p = !showQm2p }, label = { Text("QM2P") })
                FilterChip(selected = showSnr1, onClick = { showSnr1 = !showSnr1 }, label = { Text("SNRC1") })
                FilterChip(selected = showSnr2, onClick = { showSnr2 = !showSnr2 }, label = { Text("SNRC2") })
                FilterChip(selected = showSnr3, onClick = { showSnr3 = !showSnr3 }, label = { Text("SNRC3") })
                FilterChip(selected = showHy1, onClick = { showHy1 = !showHy1 }, label = { Text("Hybrid 1") })
                FilterChip(selected = showHy2, onClick = { showHy2 = !showHy2 }, label = { Text("Hybrid 2") })
                FilterChip(selected = showRbr, onClick = { showRbr = !showRbr }, label = { Text("RBR") })
                FilterChip(selected = showDbd, onClick = { showDbd = !showDbd }, label = { Text("DBD") })
                FilterChip(selected = showRbd, onClick = { showRbd = !showRbd }, label = { Text("RBD") })
                FilterChip(selected = showDbr, onClick = { showDbr = !showDbr }, label = { Text("DBR") })
                FilterChip(selected = showBs1, onClick = { showBs1 = !showBs1 }, label = { Text("Blindspot 1") })
                FilterChip(selected = showBs2, onClick = { showBs2 = !showBs2 }, label = { Text("Blindspot 2") })
                FilterChip(selected = showCk1, onClick = { showCk1 = !showCk1 }, label = { Text("CK1") })
                FilterChip(selected = showCk2, onClick = { showCk2 = !showCk2 }, label = { Text("CK2") })
            }
            // Rangée 6 : concepts SMC
            if (chipTab == 5) Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = showFvg, onClick = { showFvg = !showFvg }, label = { Text("FVG") })
                FilterChip(selected = showBpr, onClick = { showBpr = !showBpr }, label = { Text("BPR") })
                FilterChip(selected = showIdm, onClick = { showIdm = !showIdm }, label = { Text("Inducement") })
                FilterChip(selected = showPoi, onClick = { showPoi = !showPoi }, label = { Text("POI") })
                FilterChip(selected = showCrt, onClick = { showCrt = !showCrt }, label = { Text("CRT") })
            }

            // Entrées affichées : proches (2 ATR au plus) sauf si « Lointaines » est actif
            val shownEntries: List<EntryLevel> =
                if (showEntries) d.entries.filter { showFar || it.distAtr <= 2.0 } else emptyList()

            // Setups QM et SNRC : seulement ceux dont le bouton est actif (3 plus récents par famille)
            val anySetup = showQmr || showQmc || showQmm || showQm2p || showSnr1 || showSnr2 || showSnr3 ||
                showHy1 || showHy2 || showRbr || showDbd || showRbd || showDbr ||
                showBs1 || showBs2 || showCk1 || showCk2
            val qmShown: List<QmSetup> = d.qm.filter { q ->
                (showQmr && QmType.QMR in q.types) || (showQmc && QmType.QMC in q.types) ||
                    (showQmm && QmType.QMM in q.types) || (showQm2p && QmType.QM2P in q.types)
            }.take(3)
            val snrShown: List<SnrSetup> = d.snr.filter { sn ->
                (showSnr1 && sn.type == SnrType.SNRC1) || (showSnr2 && sn.type == SnrType.SNRC2) ||
                    (showSnr3 && sn.type == SnrType.SNRC3)
            }.take(3)
            val hybShown: List<HybridSetup> = d.hyb.filter { hy ->
                (showHy1 && hy.type == HybridType.HYBRID1) || (showHy2 && hy.type == HybridType.HYBRID2)
            }.take(3)
            val baseShown: List<BaseSetup> = d.base.filter { bs ->
                (showRbr && bs.type == BaseType.RBR) || (showDbd && bs.type == BaseType.DBD) ||
                    (showRbd && bs.type == BaseType.RBD) || (showDbr && bs.type == BaseType.DBR)
            }.take(4)
            val patShown: List<PatSetup> = d.pat.filter { pt ->
                (showBs1 && pt.type == PatType.BS1) || (showBs2 && pt.type == PatType.BS2) ||
                    (showCk1 && pt.type == PatType.CK1) || (showCk2 && pt.type == PatType.CK2)
            }.take(4)
            val smcShown: List<SmcConcept> = d.smcc.filter { sc ->
                (sc.kind == "FVG" && showFvg) || (sc.kind == "BPR" && showBpr) || (sc.kind == "IDM" && showIdm) ||
                    (sc.kind == "POI" && showPoi) || (sc.kind == "CRT" && showCrt)
            }
            val reShown: List<ReSetup> = d.re.filter { rs ->
                (rs.kind == 'N' && showRma) || (rs.kind == 'R' && showR) || (rs.kind == 'Z' && showZzl)
            }

            // Profil de volume à plage fixe : calculé sur les dernières bougies, il ne bouge pas avec le défilement
            val vpData = remember(d, vpRange) { buildVp(d.c, vpRange, 40) }

            // Figures chartistes : lignes de tendance, triangles, biseaux, épaule-tête-épaule...
            val figs = remember(d) { TrendFigures.build(d.c) }

            // Structure de marché : sommets et creux étiquetés H/L, HH, HL, LH, LL
            val swings = remember(d) { buildSwings(d.c) }

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
                var ihi = start
                var ilo = start
                for (j in start until end) {
                    if (d.c[j].high > hi) ihi = j
                    if (d.c[j].low < lo) ilo = j
                    hi = max(hi, d.c[j].high)
                    lo = min(lo, d.c[j].low)
                }

                // Fibonacci : retracement du plus grand mouvement visible (extrême haut et extrême bas)
                val swHi = hi
                val swLo = lo
                val fibRatios = doubleArrayOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)
                val fibNames = arrayOf("0", "23.6", "38.2", "50", "61.8", "78.6", "100")
                val fibDown = ilo > ihi // mouvement baissier : du haut vers le bas, 0 % au bas
                val fibOn = showFib && swHi > swLo && ihi != ilo
                fun fibPrice(r: Double): Double =
                    if (fibDown) swLo + r * (swHi - swLo) else swHi - r * (swHi - swLo)
                val fibColor = Color(0xFFEC407A)
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
                for (q in qmShown) {
                    for (p in listOf(q.entry, q.stopLoss, q.tp1)) {
                        hi = max(hi, p)
                        lo = min(lo, p)
                    }
                }
                for (sn in snrShown) {
                    for (p in listOf(sn.entry, sn.stopLoss, sn.tp1)) {
                        hi = max(hi, p)
                        lo = min(lo, p)
                    }
                }
                for (hy in hybShown) {
                    for (p in listOf(hy.entry, hy.stopLoss, hy.tp1)) {
                        hi = max(hi, p)
                        lo = min(lo, p)
                    }
                }
                for (bs in baseShown) {
                    for (p in listOf(bs.entry, bs.stopLoss, bs.tp1)) {
                        hi = max(hi, p)
                        lo = min(lo, p)
                    }
                }
                for (pt in patShown) {
                    for (p in listOf(pt.entry, pt.stopLoss, pt.tp1)) {
                        hi = max(hi, p)
                        lo = min(lo, p)
                    }
                }
                for (rs in reShown) {
                    for (p in listOf(rs.entry, rs.sl, rs.tp1)) {
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
                for (q in qmShown) {
                    specials.add(q.entry)
                    specials.add(q.stopLoss)
                    specials.add(q.tp1)
                }
                for (sn in snrShown) {
                    specials.add(sn.entry)
                    specials.add(sn.stopLoss)
                    specials.add(sn.tp1)
                }
                for (hy in hybShown) {
                    specials.add(hy.entry)
                    specials.add(hy.stopLoss)
                    specials.add(hy.tp1)
                }
                for (bs in baseShown) {
                    specials.add(bs.entry)
                    specials.add(bs.stopLoss)
                    specials.add(bs.tp1)
                }
                for (rs in reShown) {
                    specials.add(rs.entry)
                    specials.add(rs.sl)
                    specials.add(rs.tp1)
                }
                for (pt in patShown) {
                    specials.add(pt.entry)
                    specials.add(pt.stopLoss)
                    specials.add(pt.tp1)
                }
                if (fibOn) for (r in fibRatios) specials.add(fibPrice(r))
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

                // Fibonacci : zone dorée 50 - 61.8 %, ligne du mouvement et niveaux
                if (fibOn) {
                    val x0 = max(0f, x(min(ihi, ilo)))
                    val gTop = y(max(fibPrice(0.5), fibPrice(0.618)))
                    val gBot = y(min(fibPrice(0.5), fibPrice(0.618)))
                    drawRect(
                        fibColor.copy(alpha = 0.12f),
                        topLeft = Offset(x0, gTop),
                        size = Size(plotW - x0, max(gBot - gTop, 2f))
                    )
                    drawLine(
                        fibColor.copy(alpha = 0.6f),
                        Offset(x(ihi), y(swHi)),
                        Offset(x(ilo), y(swLo)),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    )
                    for (k in fibRatios.indices) {
                        val r = fibRatios[k]
                        val yy = y(fibPrice(r))
                        val key = r == 0.5 || r == 0.618
                        drawLine(
                            fibColor.copy(alpha = if (key) 0.95f else 0.7f),
                            Offset(x0, yy),
                            Offset(plotW, yy),
                            strokeWidth = if (key) 2.5f else 1.5f
                        )
                        val lay = tm.measure(
                            fibNames[k] + " %",
                            TextStyle(color = fibColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        )
                        drawText(lay, topLeft = Offset(plotW - lay.size.width - 4f, yy - lay.size.height - 1f))
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

                // Setups QM (QMR, QMC, QMM, QM2P) et SNRC1/2/3 : zone, structure, entrée, stop et cibles
                val setDash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                fun setupTag(txt: String, col: Color, px: Float, py: Float) {
                    val lay = tm.measure(txt, TextStyle(color = col, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    drawRect(
                        Color(0xFF0E1117).copy(alpha = 0.85f),
                        topLeft = Offset(px, py),
                        size = Size(lay.size.width.toFloat() + 6f, lay.size.height.toFloat())
                    )
                    drawText(lay, topLeft = Offset(px + 3f, py))
                }
                fun setupLines(x0: Float, e: Double, sl: Double, t1: Double, t2: Double) {
                    drawLine(entryBlue, Offset(x0, y(e)), Offset(plotW, y(e)), strokeWidth = 2.5f, pathEffect = setDash)
                    drawLine(red.copy(alpha = 0.85f), Offset(x0, y(sl)), Offset(plotW, y(sl)), strokeWidth = 1.5f, pathEffect = setDash)
                    drawLine(green.copy(alpha = 0.85f), Offset(x0, y(t1)), Offset(plotW, y(t1)), strokeWidth = 1.5f, pathEffect = setDash)
                    if (t2 in lo..hi) {
                        drawLine(tealTp2.copy(alpha = 0.6f), Offset(x0, y(t2)), Offset(plotW, y(t2)), strokeWidth = 1.5f, pathEffect = setDash)
                    }
                }
                for (q in qmShown) {
                    if (q.breakIdx >= end) continue
                    val sellQ = q.direction == QmDirection.SELL
                    val qcol = if (sellQ) red else green
                    val xs = if (q.shoulderIdx < start) 0f else x(q.shoulderIdx)
                    val xb = if (q.breakIdx < start) 0f else x(q.breakIdx)
                    drawRect(
                        qcol.copy(alpha = 0.18f),
                        topLeft = Offset(xs, y(q.zoneHigh)),
                        size = Size(plotW - xs, max(y(q.zoneLow) - y(q.zoneHigh), 2f))
                    )
                    val shP = if (sellQ) q.zoneHigh else q.zoneLow
                    val hdP = if (sellQ) d.c[q.headIdx].high else d.c[q.headIdx].low
                    val qpath = Path()
                    qpath.moveTo(x(q.shoulderIdx), y(shP))
                    qpath.lineTo(x(q.baseIdx), y(q.tp1))
                    qpath.lineTo(x(q.headIdx), y(hdP))
                    qpath.lineTo(x(q.breakIdx), y(d.c[q.breakIdx].close))
                    drawPath(qpath, qcol, style = Stroke(width = 2.5f))
                    drawCircle(qcol, radius = 5f, center = Offset(x(q.shoulderIdx), y(shP)))
                    drawCircle(qcol, radius = 5f, center = Offset(x(q.headIdx), y(hdP)))
                    val h2 = q.head2Idx
                    if (h2 != null) {
                        drawCircle(qcol, radius = 5f, center = Offset(x(h2), y(if (sellQ) d.c[h2].high else d.c[h2].low)))
                    }
                    setupLines(xb, q.entry, q.stopLoss, q.tp1, q.tp2)
                    val nm = q.types.sortedBy { it.ordinal }.joinToString("+") { it.name }
                    setupTag(
                        nm + (if (sellQ) " vente" else " achat"),
                        qcol,
                        xs + 4f,
                        if (sellQ) y(q.zoneHigh) - 16f else y(q.zoneLow) + 3f
                    )
                }
                for (sn in snrShown) {
                    if (sn.breakIdx >= end) continue
                    val sellS = sn.direction == QmDirection.SELL
                    val scol = if (sellS) red else green
                    val xp = if (sn.pivotIdx < start) 0f else x(sn.pivotIdx)
                    val xb = if (sn.breakIdx < start) 0f else x(sn.breakIdx)
                    drawRect(
                        scol.copy(alpha = 0.18f),
                        topLeft = Offset(xp, y(sn.zoneHigh)),
                        size = Size(plotW - xp, max(y(sn.zoneLow) - y(sn.zoneHigh), 2f))
                    )
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(xp, y(sn.level)), Offset(plotW, y(sn.level)), strokeWidth = 3f)
                    drawCircle(scol, radius = 5f, center = Offset(x(sn.breakIdx), y(d.c[sn.breakIdx].close)))
                    val rt = sn.retestIdx
                    if (rt != null) drawCircle(entryBlue, radius = 6f, center = Offset(x(rt), y(sn.level)))
                    setupLines(xb, sn.entry, sn.stopLoss, sn.tp1, sn.tp2)
                    setupTag(
                        sn.type.name + (if (sellS) " vente" else " achat"),
                        scol,
                        xp + 4f,
                        if (sellS) y(sn.zoneHigh) - 16f else y(sn.zoneLow) + 3f
                    )
                }

                // Hybrid 1 et Hybrid 2 : lignes de tendance, zone de danger, zone d'entrée, stop et cibles
                for (hy in hybShown) {
                    if (hy.refIdx >= end) continue
                    val sellH = hy.direction == QmDirection.SELL
                    val hcol = if (sellH) red else green
                    val dangerCol = Color(0xFFFF4081)
                    if (hy.dangerHigh >= lo && hy.dangerLow <= hi) {
                        val dTop = y(hy.dangerHigh)
                        val dH = max(y(hy.dangerLow) - dTop, 2f)
                        drawRect(dangerCol.copy(alpha = 0.10f), topLeft = Offset(0f, dTop), size = Size(plotW, dH))
                        drawRect(dangerCol, topLeft = Offset(0f, dTop), size = Size(plotW, dH), style = Stroke(width = 2f))
                        setupTag("Danger Zone", dangerCol, 4f, dTop + 2f)
                    }
                    fun trendLine(l: HLine, col: Color) {
                        if (l.i1 >= end) return
                        val ie = n - 1
                        drawLine(col, Offset(x(l.i1), y(l.p1)), Offset(x(ie), y(l.at(ie))), strokeWidth = 3f)
                    }
                    trendLine(hy.line1, Color(0xFFFFD54F))
                    val ln2 = hy.line2
                    if (ln2 != null) trendLine(ln2, Color(0xFF90A4AE))
                    val xz = if (hy.refIdx < start) 0f else x(hy.refIdx)
                    drawRect(
                        hcol.copy(alpha = 0.18f),
                        topLeft = Offset(xz, y(hy.zoneHigh)),
                        size = Size(plotW - xz, max(y(hy.zoneLow) - y(hy.zoneHigh), 2f))
                    )
                    setupLines(xz, hy.entry, hy.stopLoss, hy.tp1, hy.tp2)
                    setupTag(
                        (if (hy.type == HybridType.HYBRID1) "Hybrid 1" else "Hybrid 2") +
                            (if (sellH) " vente" else " achat") + (if (hy.via.isEmpty()) "" else " · " + hy.via),
                        hcol,
                        xz + 4f,
                        if (sellH) y(hy.zoneHigh) - 16f else y(hy.zoneLow) + 3f
                    )
                }

                // RBR, DBD, RBD, DBR : base (zone d'entrée), entrée, stop et cibles
                for (bs in baseShown) {
                    if (bs.legOutIdx >= end) continue
                    val sellB = bs.direction == QmDirection.SELL
                    val bcol = if (sellB) red else green
                    val xb0 = if (bs.baseStart < start) 0f else x(bs.baseStart)
                    val xl = if (bs.legOutIdx < start) 0f else x(bs.legOutIdx)
                    val zTop = y(bs.zoneHigh)
                    val zH = max(y(bs.zoneLow) - zTop, 2f)
                    drawRect(bcol.copy(alpha = 0.18f), topLeft = Offset(xb0, zTop), size = Size(plotW - xb0, zH))
                    drawRect(
                        bcol,
                        topLeft = Offset(xb0, zTop),
                        size = Size(max(x(bs.baseEnd) - xb0 + cw, 4f), zH),
                        style = Stroke(width = 2.5f)
                    )
                    val rt = bs.retestIdx
                    if (rt != null) drawCircle(entryBlue, radius = 6f, center = Offset(x(rt), y(bs.entry)))
                    setupLines(xl, bs.entry, bs.stopLoss, bs.tp1, bs.tp2)
                    setupTag(
                        bs.type.name + (if (sellB) " vente" else " achat"),
                        bcol,
                        xb0 + 4f,
                        if (sellB) zTop - 16f else y(bs.zoneLow) + 3f
                    )
                }

                // Re-entry MA / Mid BB / ZZL : zone de la mèche, entrée, stop et cibles
                for (rs in reShown) {
                    if (rs.idx >= end) continue
                    val rcol = if (rs.buy) green else red
                    val xr0 = if (rs.idx - 1 < start) 0f else x(rs.idx - 1)
                    val rTop = y(rs.zoneHigh)
                    val rH = max(y(rs.zoneLow) - rTop, 2f)
                    drawRect(rcol.copy(alpha = 0.18f), topLeft = Offset(xr0, rTop), size = Size(cw * 3f, rH))
                    drawRect(rcol, topLeft = Offset(xr0, rTop), size = Size(cw * 3f, rH), style = Stroke(width = 2.5f))
                    setupLines(if (rs.idx < start) 0f else x(rs.idx), rs.entry, rs.sl, rs.tp1, rs.tp2)
                    setupTag(
                        (if (rs.kind == 'Z') "ZZL" else if (rs.kind == 'N') "Re-entry MA" else "Re-entry Mid") +
                            (if (rs.buy) " achat" else " vente") + " · touché",
                        rcol,
                        xr0 + 4f,
                        if (rs.buy) y(rs.zoneLow) + 3f else rTop - 16f
                    )
                }

                // Concepts SMC : FVG, BPR, Inducement, POI, CRT (zone ou ligne jusqu'au bord droit)
                for (sc in smcShown) {
                    val scol = if (sc.buy) green else red
                    val xs = x(max(start, min(sc.fromIdx, end - 1)))
                    val sTop = y(sc.high)
                    val sH = max(y(sc.low) - sTop, 2f)
                    drawRect(scol.copy(alpha = 0.14f), topLeft = Offset(xs, sTop), size = Size(plotW - xs, sH))
                    drawRect(scol.copy(alpha = 0.7f), topLeft = Offset(xs, sTop), size = Size(plotW - xs, sH), style = Stroke(width = 1.5f))
                    setupTag(sc.label, scol, xs + 4f, if (sc.buy) sTop + sH + 2f else sTop - 16f)
                }

                // Re-entry en attente : bande du niveau vers lequel le prix revient (pas encore touché)
                val rpd = d.rePend
                if (rpd != null && ((rpd.kind == 1 && showRma) || (rpd.kind == 2 && showR) || (rpd.kind == 3 && showZzl))) {
                    val pcol = if (rpd.dir == Direction.BUY) green else red
                    val xp0 = x(max(start, end - 12))
                    val pTop = y(rpd.high)
                    val pH = max(y(rpd.low) - pTop, 3f)
                    drawRect(pcol.copy(alpha = 0.15f), topLeft = Offset(xp0, pTop), size = Size(plotW - xp0, pH))
                    drawRect(pcol, topLeft = Offset(xp0, pTop), size = Size(plotW - xp0, pH), style = Stroke(width = 2f))
                    setupTag(
                        bbmaPendingName(rpd.kind) + (if (rpd.dir == Direction.BUY) " achat" else " vente") + " en attente",
                        pcol,
                        xp0 + 4f,
                        if (rpd.dir == Direction.BUY) y(rpd.low) + 3f else pTop - 16f
                    )
                }

                // Blindspot 1/2, CK1, CK2 : zone d'entrée, lignes de tendance, entrée, stop et cibles
                for (pt in patShown) {
                    if (pt.keyIdx >= end) continue
                    val sellP = pt.direction == QmDirection.SELL
                    val pcol = if (sellP) red else green
                    val xr = if (pt.refIdx < start) 0f else x(pt.refIdx)
                    val xk = if (pt.keyIdx < start) 0f else x(pt.keyIdx)
                    val zTop = y(pt.zoneHigh)
                    drawRect(
                        pcol.copy(alpha = 0.18f),
                        topLeft = Offset(xr, zTop),
                        size = Size(plotW - xr, max(y(pt.zoneLow) - zTop, 2f))
                    )
                    for (ln in pt.lines) {
                        if (ln.i1 >= end) continue
                        val ie = n - 1
                        drawLine(
                            Color(0xFFFFD54F),
                            Offset(x(ln.i1), y(ln.p1)),
                            Offset(x(ie), y(ln.at(ie))),
                            strokeWidth = 3f
                        )
                    }
                    val rt = pt.retestIdx
                    if (rt != null) drawCircle(entryBlue, radius = 6f, center = Offset(x(rt), y(pt.entry)))
                    setupLines(xk, pt.entry, pt.stopLoss, pt.tp1, pt.tp2)
                    setupTag(
                        patName(pt.type) + (if (sellP) " vente" else " achat"),
                        pcol,
                        xr + 4f,
                        if (sellP) zTop - 16f else y(pt.zoneLow) + 3f
                    )
                }

                // Structure de marché : étiquettes HH / HL / LH / LL
                if (showSw) {
                    for (sw in swings) {
                        if (sw.i < start || sw.i >= end) continue
                        val up = sw.label == "HH" || sw.label == "HL"
                        val dn = sw.label == "LH" || sw.label == "LL"
                        val col = if (up) green else if (dn) red else neutralGray
                        val lay = tm.measure(sw.label, TextStyle(color = col, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                        val px = x(sw.i) - lay.size.width / 2f
                        val py = if (sw.high) y(sw.price) - lay.size.height - 4f else y(sw.price) + 4f
                        drawCircle(col, radius = 3f, center = Offset(x(sw.i), y(sw.price)))
                        drawText(lay, topLeft = Offset(px, py))
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

                // Figures chartistes : lignes de tendance prolongées jusqu'à la dernière bougie
                if (showFig) {
                    for (f in figs) {
                        val fc = if (f.buy == true) green else if (f.buy == false) red else Color(0xFF90A4AE)
                        for (ln in f.lines) {
                            if (ln.i1 >= end) continue
                            drawLine(
                                fc.copy(alpha = if (ln.guide) 0.55f else 0.95f),
                                Offset(x(ln.i1), y(ln.p1)),
                                Offset(x(ln.i2), y(ln.p2)),
                                strokeWidth = if (ln.guide) 1.5f else 3f,
                                pathEffect = if (ln.guide) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null
                            )
                        }
                        val lay = tm.measure(
                            f.name + " · " + f.status,
                            TextStyle(color = fc, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        )
                        // étiquette fixe en haut à gauche, loin des marques des bougies
                        val lx = 4f
                        val ly = 4f
                        drawRect(
                            Color(0xFF0E1117).copy(alpha = 0.8f),
                            topLeft = Offset(lx, ly),
                            size = Size(lay.size.width.toFloat() + 4f, lay.size.height.toFloat())
                        )
                        drawText(lay, topLeft = Offset(lx + 2f, ly))
                    }
                }

                // Marques BBMA, sweeps et patterns, empilées sous (haussier) ou au-dessus (baissier) de la bougie
                fun kindOn(kind: Char): Boolean = when (kind) {
                    'E' -> showE
                    'M' -> showM
                    'R' -> showR
                    'N' -> showRma
                    'Z' -> showZzl
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
                if (fibOn) {
                    for (r in fibRatios) label(fibPrice(r), Color.White, Color(0xFFAD1457))
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
                for (q in qmShown) {
                    label(q.tp1, Color.White, Color(0xFF1B5E20))
                    label(q.stopLoss, Color.White, Color(0xFFB71C1C))
                    label(q.entry, Color.Black, entryBlue)
                }
                for (sn in snrShown) {
                    label(sn.tp1, Color.White, Color(0xFF1B5E20))
                    label(sn.stopLoss, Color.White, Color(0xFFB71C1C))
                    label(sn.entry, Color.Black, entryBlue)
                }
                for (hy in hybShown) {
                    label(hy.tp1, Color.White, Color(0xFF1B5E20))
                    label(hy.stopLoss, Color.White, Color(0xFFB71C1C))
                    label(hy.entry, Color.Black, entryBlue)
                }
                for (bs in baseShown) {
                    label(bs.tp1, Color.White, Color(0xFF1B5E20))
                    label(bs.stopLoss, Color.White, Color(0xFFB71C1C))
                    label(bs.entry, Color.Black, entryBlue)
                }
                for (pt in patShown) {
                    label(pt.tp1, Color.White, Color(0xFF1B5E20))
                    label(pt.stopLoss, Color.White, Color(0xFFB71C1C))
                    label(pt.entry, Color.Black, entryBlue)
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

            // Détail des setups QM et SNRC
            if (anySetup) {
                if (qmShown.isEmpty() && snrShown.isEmpty() && hybShown.isEmpty() && baseShown.isEmpty() && patShown.isEmpty()) {
                    Text(
                        "Aucun setup actif et proche (5 ATR au plus) pour les boutons choisis sur ce marché.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (q in qmShown) {
                    val sellQ = q.direction == QmDirection.SELL
                    val nm = q.types.sortedBy { it.ordinal }.joinToString("+") { it.name }
                    Text(
                        nm + (if (sellQ) " vente" else " achat") + " · Entrée " + fmtPrice(q.entry, dec) +
                            " · SL " + fmtPrice(q.stopLoss, dec) + " · TP1 " + fmtPrice(q.tp1, dec) +
                            " · TP2 " + fmtPrice(q.tp2, dec) + " · R/R " + String.format(Locale.US, "%.1f", q.rr),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sellQ) red else green
                    )
                    Text(
                        if (q.status == QmStatus.TRIGGERED) "Entrée déjà touchée" else "En attente du retour sur la zone",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (sn in snrShown) {
                    val sellS = sn.direction == QmDirection.SELL
                    Text(
                        sn.type.name + (if (sellS) " vente" else " achat") + " · Niveau " + fmtPrice(sn.entry, dec) +
                            " · SL " + fmtPrice(sn.stopLoss, dec) + " · TP1 " + fmtPrice(sn.tp1, dec) +
                            " · TP2 " + fmtPrice(sn.tp2, dec) + " · R/R " + String.format(Locale.US, "%.1f", sn.rr),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sellS) red else green
                    )
                    Text(
                        (if (sn.type == SnrType.SNRC1) (if (sellS) "DBD" else "RBR") + " au niveau · "
                        else if (sn.type == SnrType.SNRC2) (if (sellS) "RBD" else "DBR") + " avant la cassure · "
                        else (if (sellS) "Failed Bullish Engulfing + Bearish Engulfing" else "Failed Bearish Engulfing + Bullish Engulfing") + " · ") +
                            (if (sn.status == QmStatus.TRIGGERED) "niveau déjà testé" else "en attente du test du niveau") +
                            " · confirmation HTF à vérifier à la main",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (hy in hybShown) {
                    val sellH = hy.direction == QmDirection.SELL
                    val h1 = hy.type == HybridType.HYBRID1
                    Text(
                        (if (h1) "Hybrid 1" else "Hybrid 2") + (if (sellH) " vente" else " achat") +
                            " · Entrée " + fmtPrice(hy.entry, dec) +
                            " · SL " + fmtPrice(hy.stopLoss, dec) + " · TP1 " + fmtPrice(hy.tp1, dec) +
                            " · TP2 " + fmtPrice(hy.tp2, dec) + " · R/R " + String.format(Locale.US, "%.1f", hy.rr),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sellH) red else green
                    )
                    Text(
                        (if (h1) "Ligne de tendance cassée puis retest sur " + hy.via
                        else "Ligne montante cassée, retour sur la ligne descendante" +
                            (if (hy.via.isEmpty()) "" else " · retournement : " + hy.via)) +
                            " · " + (if (hy.status == QmStatus.TRIGGERED) "entrée déjà touchée" else "en attente de l'entrée"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (bs in baseShown) {
                    val sellB = bs.direction == QmDirection.SELL
                    Text(
                        bs.type.name + (if (sellB) " vente" else " achat") + " · Entrée " + fmtPrice(bs.entry, dec) +
                            " · SL " + fmtPrice(bs.stopLoss, dec) + " · TP1 " + fmtPrice(bs.tp1, dec) +
                            " · TP2 " + fmtPrice(bs.tp2, dec),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sellB) red else green
                    )
                    Text(
                        (if (sellB) "Zone d'offre " else "Zone de demande ") +
                            fmtPrice(bs.zoneLow, dec) + " – " + fmtPrice(bs.zoneHigh, dec) + " · " +
                            (if (bs.status == QmStatus.TRIGGERED) "premier retest en cours" else "zone fraîche, en attente du retour du prix"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (pt in patShown) {
                    val sellP = pt.direction == QmDirection.SELL
                    Text(
                        patName(pt.type) + (if (sellP) " vente" else " achat") + " · Entrée " + fmtPrice(pt.entry, dec) +
                            " · SL " + fmtPrice(pt.stopLoss, dec) + " · TP1 " + fmtPrice(pt.tp1, dec) +
                            " · TP2 " + fmtPrice(pt.tp2, dec),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sellP) red else green
                    )
                    Text(
                        (if (pt.status == QmStatus.TRIGGERED) "entrée déjà touchée" else "en attente de l'entrée") +
                            " · confirmation HTF (bougie englobante) à vérifier à la main",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            if (showFig) {
                legend += "Figures : lignes de tendance tracées sur les derniers sommets et creux (vert = haussier, rouge = baissier, gris = neutre), " +
                    "prolongées jusqu'à la dernière bougie pour voir la cassure · "
            }
            if (showFib) {
                legend += "Fibonacci : retracement du plus grand mouvement visible (0 % = extrême le plus récent, 100 % = départ du mouvement), " +
                    "zone rose = 50 à 61,8 %. Il change si tu zoomes ou fais défiler · "
            }
            if (showVp) {
                legend += "Profil (plage fixe) : barres = temps passé à chaque prix, jaune = POC, bleu = zone de valeur 70 %. " +
                    "Deriv ne fournit pas le volume réel · "
            }
            if (showE || showM || showR || showRma || showZzl) {
                legend += "Lettres : "
                if (showE) legend += "E = Extrême  "
                if (showM) legend += "M = MHV  "
                if (showRma) legend += "RMA = Re-entry MA5/10  "
                if (showR) legend += "RMB = Re-entry Mid BB  "
                if (showZzl) legend += "ZZL = Zone Zero Loss  "
                legend += "(vert sous la bougie = haussier, rouge au-dessus = baissier) · "
            }
            if (showCs) legend += "Points : CSM (magenta, gros), CSAK (orange) · "
            if (showCsa) legend += "CSA (gris, petit) · "
            if (showLiq) legend += "Liquidité : EQH/EQL = hauts/bas égaux, Liq. haute/basse = niveaux non touchés, S = sweep · "
            if (showSw) {
                legend += "Structure : HH = sommet plus haut, HL = creux plus haut (haussier), LH = sommet plus bas, LL = creux plus bas (baissier), H/L = premier point · "
            }
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
            if (anySetup) {
                legend += "Setups : zone colorée = entrée, trait épais = structure (épaule, base, tête, cassure), " +
                    "bleu = entrée, rouge = SL, vert = TP1, turquoise = TP2. Setups actifs seulement · "
            }
            if (showHy1 || showHy2) {
                legend += "Hybrid : ligne jaune = ligne de tendance, ligne grise = tendance précédente (Hybrid 2), " +
                    "rose = Danger Zone (le prix ne doit pas l'avoir touchée) · "
            }
            if (showRbr || showDbd || showRbd || showDbr) {
                legend += "RBR / DBD / RBD / DBR : rectangle = zone de la base (zone d'entrée), contour épais = bougies de la base. " +
                    "Seulement les zones fraîches, jamais retestées · "
            }
            if (showBs1 || showBs2 || showCk1 || showCk2) {
                legend += "Blindspot / CK : rectangle = zone d'entrée, ligne jaune = ligne de tendance (points 1-2, le point 3 est l'entrée) · "
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
