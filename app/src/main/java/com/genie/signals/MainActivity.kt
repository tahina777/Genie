package com.genie.signals

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainVm : ViewModel() {
    val tf = MutableStateFlow(Tf.M15)
    val filter = MutableStateFlow(0) // 0 = tous, 1 = forex, 2 = synthétiques
    val signals = MutableStateFlow<List<Signal>>(emptyList())
    val loading = MutableStateFlow(false)
    val status = MutableStateFlow("Appuie sur « Scanner » pour analyser le marché.")

    fun scan() {
        if (loading.value) return
        viewModelScope.launch {
            loading.value = true
            status.value = "Analyse en cours…"
            val list = when (filter.value) {
                1 -> ALL_MARKETS.filter { !it.synthetic }
                2 -> ALL_MARKETS.filter { it.synthetic }
                else -> ALL_MARKETS
            }
            try {
                val res = Scanner.scan(list, tf.value)
                signals.value = res.signals
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenieApp(vm: MainVm) {
    val ctx = LocalContext.current
    val tf by vm.tf.collectAsState()
    val filter by vm.filter.collectAsState()
    val signals by vm.signals.collectAsState()
    val loading by vm.loading.collectAsState()
    val status by vm.status.collectAsState()
    var alerts by remember { mutableStateOf(Alerts.isOn(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Alerts.enable(ctx)
            alerts = true
        }
    }

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
                Tf.values().forEach { t ->
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
                onClick = { vm.scan() },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (loading) "Analyse en cours…" else "Scanner le marché")
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Alertes en arrière-plan (M15, score ≥ 70)",
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

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(signals) { s -> SignalCard(s) }
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
        }
    }
}

@Composable
fun SignalCard(s: Signal) {
    val col = if (s.dir == Direction.BUY) Color(0xFF26A69A) else Color(0xFFEF5350)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
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
            LinearProgressIndicator(
                progress = { s.score / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Score " + s.score + "/100", fontSize = 12.sp)
            s.reasons.forEach { r -> Text("• " + r, fontSize = 13.sp) }
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
