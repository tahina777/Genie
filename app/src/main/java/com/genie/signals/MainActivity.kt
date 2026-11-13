package com.genie.signals

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainVm : ViewModel() {
    val tf = MutableStateFlow(Tf.H1)
    val filter = MutableStateFlow(0) // 0 = tous, 1 = forex, 2 = synthétiques
    val signals = MutableStateFlow<List<Signal>>(emptyList())
    val loading = MutableStateFlow(false)
    val status = MutableStateFlow("Appuie sur « Scanner » pour analyser le marché.")

    val btLoading = MutableStateFlow(false)
    val btResult = MutableStateFlow<BtResult?>(null)
    val btLabel = MutableStateFlow("")
    val btStatus = MutableStateFlow("")

    val entries = MutableStateFlow<List<PendingEntry>>(emptyList())
    val entLoading = MutableStateFlow(false)
    val entStatus = MutableStateFlow("")

    val journal = MutableStateFlow<List<JEntry>>(emptyList())
    val jLoading = MutableStateFlow(false)

    private fun marketList(): List<Market> = when (filter.value) {
        1 -> ALL_MARKETS.filter { !it.synthetic }
        2 -> ALL_MARKETS.filter { it.synthetic }
        else -> ALL_MARKETS
    }

    private fun busy(): Boolean = loading.value || btLoading.value || entLoading.value

    fun scan(ctx: Context) {
        if (busy()) return
        val app = ctx.applicationContext
        viewModelScope.launch {
            loading.value = true
            status.value = "Analyse en cours…"
            val list = marketList()
            try {
                val res = Scanner.scan(list, tf.value)
                signals.value = res.signals
                Journal.add(app, res.signals)
                var msg = res.analysed.toString() + " marchés analysés · " + res.signals.size + " signal(s)"
                if (res.failed > 0) msg += " · " + res.failed + " indisponible(s)"
                if (res.signals.isEmpty()) msg += "\nAucun setup valide pour le moment : c'est normal, patiente."
                status.value = msg
            } catch (e: Exception) {
                status.value = "Erreur : vérifie ta connexion internet."
            }
            loading.value = false
        }
    }

    fun backtest() {
        if (busy()) return
        viewModelScope.launch {
            btLoading.value = true
            btStatus.value = "Backtest en cours… (environ 1 minute)"
            val list = marketList()
            try {
                val res = Backtest.run(list, tf.value)
                btResult.value = res
                btLabel.value = tf.value.label
                btStatus.value = if (res.total.trades == 0) "Aucun trade trouvé sur l'historique." else ""
            } catch (e: Exception) {
                btStatus.value = "Erreur : vérifie ta connexion internet."
            }
            btLoading.value = false
        }
    }

    fun findEntries() {
        if (busy()) return
        viewModelScope.launch {
            entLoading.value = true
            entStatus.value = "Recherche des entrées en cours…"
            val list = marketList()
            try {
                val res = Entries.scan(list, tf.value)
                entries.value = res
                entStatus.value = if (res.isEmpty()) {
                    "Aucune entrée en attente pour le moment dans ce timeframe."
                } else {
                    res.size.toString() + " entrée(s) en attente (" + tf.value.label + ")"
                }
            } catch (e: Exception) {
                entStatus.value = "Erreur : vérifie ta connexion internet."
            }
            entLoading.value = false
        }
    }

    fun loadJournal(ctx: Context) {
        journal.value = Journal.all(ctx.applicationContext).sortedByDescending { it.time }
    }

    fun refreshJournal(ctx: Context) {
        if (jLoading.value) return
        val app = ctx.applicationContext
        viewModelScope.launch {
            jLoading.value = true
            try {
                Journal.refresh(app)
            } catch (e: Exception) {
                // on garde l'ancien état
            }
            journal.value = Journal.all(app).sortedByDescending { it.time }
            jLoading.value = false
        }
    }

    fun clearJournal(ctx: Context) {
        Journal.clear(ctx.applicationContext)
        journal.value = emptyList()
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    background = Color(0xFF0E1117),
                    surface = Color(0xFF171B24)
                )
            ) {
                GenieApp(vm)
            }
        }
    }
}

private fun numText(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else v.toString()

private fun parseNum(s: String): Double = s.replace(',', '.').toDoubleOrNull() ?: 0.0

/** Timeframes proposés pour le scan et le backtest. */
private val SCAN_FRAMES = listOf(Tf.H1, Tf.H4, Tf.D1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenieApp(vm: MainVm) {
    val ctx = LocalContext.current
    val tf by vm.tf.collectAsState()
    val filter by vm.filter.collectAsState()
    val signals by vm.signals.collectAsState()
    val loading by vm.loading.collectAsState()
    val status by vm.status.collectAsState()
    val btLoading by vm.btLoading.collectAsState()
    val btResult by vm.btResult.collectAsState()
    val btLabel by vm.btLabel.collectAsState()
    val btStatus by vm.btStatus.collectAsState()
    val entries by vm.entries.collectAsState()
    val entLoading by vm.entLoading.collectAsState()
    val entStatus by vm.entStatus.collectAsState()
    val journal by vm.journal.collectAsState()
    val jLoading by vm.jLoading.collectAsState()
    var alerts by remember { mutableStateOf(Alerts.isOn(ctx)) }
    var screen by remember { mutableStateOf(0) }
    var chart by remember { mutableStateOf<ChartSpec?>(null) }
    var showFar by remember { mutableStateOf(false) }
    var capText by remember {
        mutableStateOf(LotCalc.capital(ctx).let { if (it > 0) numText(it) else "" })
    }
    var riskText by remember { mutableStateOf(numText(LotCalc.riskPct(ctx))) }
    val capital = parseNum(capText)
    val riskPct = parseNum(riskText)
    val busy = loading || btLoading || entLoading

    LaunchedEffect(Unit) { vm.loadJournal(ctx) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Alerts.enable(ctx)
            alerts = true
        }
    }

    val spec = chart
    if (spec != null) {
        BackHandler { chart = null }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text("Graphique") }) }
        ) { pad ->
            Column(modifier = Modifier.padding(pad)) {
                ChartScreen(spec = spec, onBack = { chart = null })
            }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(title = { Text("Genie · Signaux SMC + BBMA") }) }
        ) { pad ->
            Column(
                modifier = Modifier
                    .padding(pad)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Scanner", "Journal").forEachIndexed { idx, label ->
                        FilterChip(
                            selected = idx == screen,
                            onClick = {
                                screen = idx
                                if (idx == 1) vm.loadJournal(ctx)
                            },
                            label = { Text(label) }
                        )
                    }
                }

                if (screen == 0) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SCAN_FRAMES.forEach { t ->
                            FilterChip(
                                selected = t == tf,
                                onClick = { vm.tf.value = t },
                                label = { Text(t.label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Tous", "Forex", "Synthétiques").forEachIndexed { idx, label ->
                            FilterChip(
                                selected = idx == filter,
                                onClick = { vm.filter.value = idx },
                                label = { Text(label) }
                            )
                        }
                    }
                    Button(
                        onClick = { vm.scan(ctx) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (loading) "Analyse en cours…" else "Scanner le marché")
                    }
                    OutlinedButton(
                        onClick = { vm.findEntries() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (entLoading) "Recherche des entrées…" else "Entrées en attente")
                    }
                    OutlinedButton(
                        onClick = { vm.backtest() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (btLoading) "Backtest en cours…" else "Lancer le backtest")
                    }
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entStatus.isNotEmpty()) {
                        Text(entStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (btStatus.isNotEmpty()) {
                        Text(btStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = capText,
                            onValueChange = {
                                capText = it
                                LotCalc.save(ctx, parseNum(it), riskPct)
                            },
                            label = { Text("Capital ($)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = riskText,
                            onValueChange = {
                                riskText = it
                                LotCalc.save(ctx, capital, parseNum(it))
                            },
                            label = { Text("Risque (%)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Alertes en arrière-plan (H1, H4, D1 et combos BBMA, score ≥ 70)",
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = alerts,
                            onCheckedChange = { on ->
                                if (!on) {
                                    Alerts.disable(ctx)
                                    alerts = false
                                } else if (Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    Alerts.enable(ctx)
                                    alerts = true
                                }
                            }
                        )
                    }

                    Text(
                        "Graphique d'un marché (" + tf.label + ")",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val shown = when (filter) {
                            1 -> ALL_MARKETS.filter { !it.synthetic }
                            2 -> ALL_MARKETS.filter { it.synthetic }
                            else -> ALL_MARKETS
                        }
                        shown.forEach { m ->
                            FilterChip(
                                selected = false,
                                onClick = { chart = plainChartSpec(m, tf) },
                                label = { Text(m.name) }
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val bt = btResult
                        if (bt != null && bt.total.trades > 0) {
                            item { BtCard(bt, btLabel) }
                        }
                        if (entries.isNotEmpty()) {
                            val near = entries.filter { it.level.distAtr <= 2.0 }
                            val shownEntries = if (showFar) entries else near
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "Entrées en attente : ordres limites à surveiller, pas des signaux validés. " +
                                            near.size + " proche(s) (2 ATR au plus) sur " + entries.size + ".",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    FilterChip(
                                        selected = showFar,
                                        onClick = { showFar = !showFar },
                                        label = { Text("Inclure les entrées lointaines (jusqu'à 6 ATR)") }
                                    )
                                }
                            }
                            items(shownEntries) { p ->
                                EntryCard(
                                    p,
                                    LotCalc.describe(p.toSignal(), capital, riskPct),
                                    onClick = { chart = chartSpecOf(p) }
                                )
                            }
                        }
                        items(signals) { s ->
                            SignalCard(
                                s,
                                LotCalc.describe(s, capital, riskPct),
                                onClick = { chart = chartSpecOf(s) }
                            )
                        }
                        item {
                            Text(
                                "Genie fournit des signaux indicatifs issus d'une analyse automatique. " +
                                    "Ce n'est pas un conseil financier et aucun gain n'est garanti. " +
                                    "Teste d'abord en compte démo et ne risque jamais plus de 1 % de ton capital par trade.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                } else {
                    JournalScreen(
                        entries = journal,
                        loading = jLoading,
                        onRefresh = { vm.refreshJournal(ctx) },
                        onClear = { vm.clearJournal(ctx) },
                        onOpen = { e -> chart = chartSpecOf(e) }
                    )
                }
            }
        }
    }
}

@Composable
fun ColumnScope.JournalScreen(
    entries: List<JEntry>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onOpen: (JEntry) -> Unit
) {
    val st = Journal.stats(entries)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRefresh, enabled = !loading, modifier = Modifier.weight(1f)) {
            Text(if (loading) "Mise à jour…" else "Actualiser les résultats")
        }
        OutlinedButton(onClick = onClear, enabled = !loading && entries.isNotEmpty()) {
            Text("Effacer")
        }
    }
    if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Résultats réels des signaux", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                st.closed.toString() + " terminés · " + num(st.winRate, 0) + " % gagnants",
                fontSize = 13.sp
            )
            Text(
                "R total " + fmtR(st.totalR) + " · R moyen " + num(st.avgR, 2),
                fontSize = 13.sp
            )
            Text(st.open.toString() + " en cours", fontSize = 13.sp)
            Text(
                "Signaux de score 65 et plus. TP1 = +1,5 R, SL = −1 R. " +
                    "Spread et commissions non inclus. Touche une ligne pour voir son graphique.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (entries.isEmpty()) {
            item {
                Text(
                    "Aucun signal enregistré pour l'instant. Lance un scan ou active les alertes.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(entries) { e -> JournalRow(e, onClick = { onOpen(e) }) }
    }
}

private fun statusLook(e: JEntry): Pair<String, Color> {
    val green = Color(0xFF26A69A)
    val red = Color(0xFFEF5350)
    return when (e.status) {
        "tp" -> Pair("TP1 " + fmtR(e.r), green)
        "sl" -> Pair("SL " + fmtR(e.r), red)
        "expired" -> Pair("Clôturé " + fmtR(e.r), if (e.r >= 0) green else red)
        "unknown" -> Pair("Non vérifiable", Color.Gray)
        else -> Pair("En cours", Color(0xFFFFB74D))
    }
}

@Composable
private fun JournalRow(e: JEntry, onClick: () -> Unit) {
    val look = statusLook(e)
    val date = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(e.time * 1000))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.market + " · " + e.tf + " · " + (if (e.buy) "ACHAT" else "VENTE"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(look.first, color = look.second, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Text(
                e.setup + " · score " + e.score,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Entrée " + e.fmt(e.entry) + " · SL " + e.fmt(e.sl) + " · TP1 " + e.fmt(e.tp1),
                fontSize = 12.sp
            )
            Text(date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun num(x: Double, d: Int): String =
    String.format(Locale.US, "%." + d + "f", x)

private fun fmtR(x: Double): String = (if (x > 0) "+" else "") + num(x, 1) + " R"

@Composable
fun BtCard(r: BtResult, tfLabel: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backtest · " + tfLabel, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            var info = r.markets.toString() + " marchés testés"
            if (r.failed > 0) info += " · " + r.failed + " indisponible(s)"
            Text(info, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            BtRow(r.total)

            if (r.bySetup.isNotEmpty()) {
                BtTitle("Par setup")
                r.bySetup.forEach { BtRow(it) }
            }
            if (r.byScore.isNotEmpty()) {
                BtTitle("Par score")
                r.byScore.forEach { BtRow(it) }
            }
            if (r.byMarket.isNotEmpty()) {
                BtTitle("Par marché")
                r.byMarket.forEach { BtRow(it) }
            }

            Text(
                "Résultats passés : ils ne garantissent pas les résultats futurs. " +
                    "Spread et commissions non inclus. R = montant risqué par trade. " +
                    "PF = profit factor, DD = drawdown maximum.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BtTitle(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun BtRow(g: BtGroup) {
    val col = if (g.totalR >= 0) Color(0xFF26A69A) else Color(0xFFEF5350)
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(
                g.name,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(fmtR(g.totalR), color = col, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(
            g.trades.toString() + " trades · " + num(g.winRate, 0) + " % gagnants · R moy " +
                num(g.avgR, 2) + " · PF " + num(g.profitFactor, 2) + " · DD " + num(g.maxDd, 1) + " R",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EntryCard(p: PendingEntry, lot: String, onClick: () -> Unit) {
    val lv = p.level
    val m = p.market
    val col = if (lv.buy) Color(0xFF26A69A) else Color(0xFFEF5350)
    val trendColor = when {
        lv.trend >= 1 -> Color(0xFF26A69A)
        lv.trend == 0 -> Color(0xFF90A4AE)
        else -> Color(0xFFFFB74D)
    }
    fun f(v: Double): String = String.format(Locale.US, "%." + m.decimals + "f", v)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (lv.buy) "ACHAT LIMITE" else "VENTE LIMITE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(col, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Text(
                p.tf.label + " · " + lv.kind + " · formée il y a " + lv.ago + " bougies",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                lv.trendLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = trendColor
            )
            LevelRow("Entrée limite", f(lv.entry), Color(0xFF29B6F6))
            LevelRow("Stop Loss", f(lv.sl), Color(0xFFEF5350))
            LevelRow("Take Profit 1", f(lv.tp1), Color(0xFF26A69A))
            LevelRow("Take Profit 2", f(lv.tp2), Color(0xFF26A69A))
            Text(
                "Prix actuel " + f(p.price) + " · à " + f(lv.dist) + " (" + num(lv.distAtr, 1) + " ATR)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (lot.isNotEmpty()) {
                Text(
                    lot,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "Touche la carte pour voir le graphique",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun SignalCard(s: Signal, lot: String, onClick: () -> Unit) {
    val col = if (s.dir == Direction.BUY) Color(0xFF26A69A) else Color(0xFFEF5350)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.market.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (s.dir == Direction.BUY) "ACHAT" else "VENTE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(col, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Text(
                s.tf.label + " · " + s.setup,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LevelRow("Entrée", s.fmt(s.entry), Color.White)
            LevelRow("Stop Loss", s.fmt(s.sl), Color(0xFFEF5350))
            LevelRow("Take Profit 1", s.fmt(s.tp1), Color(0xFF26A69A))
            LevelRow("Take Profit 2", s.fmt(s.tp2), Color(0xFF26A69A))
            if (lot.isNotEmpty()) {
                Text(
                    lot,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = { s.score / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Score " + s.score + "/100", fontSize = 12.sp)
            s.reasons.forEach { r -> Text("• " + r, fontSize = 13.sp) }
            Text(
                "Touche la carte pour ouvrir le graphique",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LevelRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}
