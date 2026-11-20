package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * BaseSetups.kt : les 4 points d'entrée de la fiche Bystra (Entry Point) : RBR, DBD, RBD, DBR.
 * Dépend de QmSetups.kt (QmCandle, QmDirection, QmStatus), même package.
 *
 * Utilisation (un bouton = un type) :
 *   val bougies = tesBougies.map { QmCandle(it.time, it.open, it.high, it.low, it.close) }
 *   val zones = BaseSetups.detect(bougies, BaseType.RBR)
 *
 * Structure commune : jambe d'entrée (bougie forte) -> base (1 à 6 petites bougies) -> jambe de sortie (bougie forte
 * qui sort de la base). La zone d'entrée est la base ; on entre au retest de la zone, dans le sens de la jambe de sortie.
 *   RBR (Rally Base Rally)  : jambe haussière, base, jambe haussière  -> zone de demande, achat
 *   DBD (Drop Base Drop)    : jambe baissière, base, jambe baissière  -> zone d'offre, vente
 *   RBD (Rally Base Drop)   : jambe haussière, base, jambe baissière  -> zone d'offre, vente
 *   DBR (Drop Base Rally)   : jambe baissière, base, jambe haussière  -> zone de demande, achat
 *
 * Seules les zones fraîches (jamais retestées) ou en cours de premier retest sont gardées.
 * ⚠️ Règles déduites des schémas de la fiche. Réglages dans BaseConfig. TP1 = 1,5 R et TP2 = 3 R sont des choix de Genie.
 */

enum class BaseType { RBR, DBD, RBD, DBR }

data class BaseConfig(
    val atrPeriod: Int = 14,
    val lookback: Int = 160,           // nombre de bougies analysées en arrière
    val legAtr: Double = 1.0,          // corps minimum d'une jambe, en ATR
    val baseBodyAtr: Double = 0.6,     // corps maximum d'une bougie de base
    val baseRangeAtr: Double = 1.2,    // amplitude maximum d'une bougie de base
    val baseMaxLen: Int = 6,           // nombre maximum de bougies de base
    val baseMaxTotalAtr: Double = 1.8, // hauteur maximum de toute la base
    val slBufferAtr: Double = 0.25,    // marge du stop au-delà de la base
    val maxAgeBars: Int = 120          // âge maximum de la jambe de sortie
)

data class BaseSetup(
    val type: BaseType,
    val direction: QmDirection,
    val baseStart: Int,
    val baseEnd: Int,
    val legOutIdx: Int,
    val zoneLow: Double,
    val zoneHigh: Double,
    val entry: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val status: QmStatus,
    val retestIdx: Int?
)

object BaseSetups {

    fun detect(
        candles: List<QmCandle>,
        type: BaseType? = null,
        cfg: BaseConfig = BaseConfig()
    ): List<BaseSetup> {
        if (candles.size < 40) return emptyList()
        // Les setups inverses sont les setups vus dans un miroir (prix * -1, high <-> low)
        val mirrored = candles.map { QmCandle(it.time, -it.open, -it.low, -it.high, -it.close) }
        val all = ArrayList<BaseSetup>()
        all.addAll(detectUp(candles, cfg))
        all.addAll(detectUp(mirrored, cfg).map { unmirror(it) })
        return all
            .filter { type == null || it.type == type }
            .sortedByDescending { it.legOutIdx }
    }

    private fun unmirror(s: BaseSetup) = s.copy(
        type = when (s.type) {
            BaseType.RBR -> BaseType.DBD
            BaseType.DBR -> BaseType.RBD
            BaseType.DBD -> BaseType.RBR
            BaseType.RBD -> BaseType.DBR
        },
        direction = if (s.direction == QmDirection.BUY) QmDirection.SELL else QmDirection.BUY,
        zoneLow = -s.zoneHigh,
        zoneHigh = -s.zoneLow,
        entry = -s.entry,
        stopLoss = -s.stopLoss,
        tp1 = -s.tp1,
        tp2 = -s.tp2
    )

    private fun atrArr(c: List<QmCandle>, period: Int): DoubleArray {
        val n = c.size
        val out = DoubleArray(n)
        val tr = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            tr[i] = if (i == 0) {
                c[i].high - c[i].low
            } else {
                max(c[i].high - c[i].low, max(abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close)))
            }
            sum += tr[i]
            if (i >= period) sum -= tr[i - period]
            out[i] = sum / min(i + 1, period)
        }
        return out
    }

    /** Côté achat : jambe de sortie haussière. RBR si la jambe d'entrée est haussière, DBR si elle est baissière. */
    private fun detectUp(c: List<QmCandle>, cfg: BaseConfig): List<BaseSetup> {
        val n = c.size
        val atr = atrArr(c, cfg.atrPeriod)
        val out = ArrayList<BaseSetup>()

        for (b0 in max(1, n - cfg.lookback) until n - 2) {
            val legIn = c[b0 - 1]
            val bodyIn = abs(legIn.close - legIn.open)
            if (bodyIn < cfg.legAtr * atr[b0 - 1]) continue
            val inUp = legIn.close > legIn.open

            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            var len = 0
            var j = b0
            while (j < n - 1 && len < cfg.baseMaxLen) {
                val cj = c[j]
                val a = atr[j]
                if (abs(cj.close - cj.open) > cfg.baseBodyAtr * a) break
                if (cj.high - cj.low > cfg.baseRangeAtr * a) break
                hi = max(hi, cj.high)
                lo = min(lo, cj.low)
                if (hi - lo > cfg.baseMaxTotalAtr * a) break
                len++

                // jambe de sortie : bougie haussière forte juste après la base, qui clôture au-dessus
                val k = j + 1
                val ck = c[k]
                if (ck.close - ck.open >= cfg.legAtr * atr[k] && ck.close > hi) {
                    if (k >= n - cfg.maxAgeBars) {
                        val s = build(c, atr, cfg, if (inUp) BaseType.RBR else BaseType.DBR, b0, j, k, lo, hi)
                        if (s != null) out.add(s)
                    }
                    break
                }
                j++
            }
        }
        return out
    }

    private fun build(
        c: List<QmCandle>, atr: DoubleArray, cfg: BaseConfig, type: BaseType,
        b0: Int, b1: Int, k: Int, lo: Double, hi: Double
    ): BaseSetup? {
        val n = c.size
        // zone fraîche : pas encore retestée, ou premier retest sur les 2 dernières bougies
        var retest = -1
        for (m in k + 1 until n) {
            if (c[m].close < lo) return null // zone cassée
            if (c[m].low <= hi) { retest = m; break }
        }
        if (retest >= 0 && retest < n - 2) return null // déjà utilisée

        val entry = hi
        val sl = lo - cfg.slBufferAtr * atr[k]
        val risk = entry - sl
        if (risk <= 0.0) return null
        return BaseSetup(
            type, QmDirection.BUY, b0, b1, k, lo, hi, entry, sl,
            entry + 1.5 * risk, entry + 3.0 * risk, 3.0,
            if (retest >= 0) QmStatus.TRIGGERED else QmStatus.PENDING,
            if (retest >= 0) retest else null
        )
    }
}
