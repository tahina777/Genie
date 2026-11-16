// ⚠️ Mets ici le MÊME package que QmSetups.kt et tes autres fichiers de signals/
package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * SnrSetups.kt : setups de continuation SNRC1, SNRC2, SNRC3 (fiche Bystra).
 * Dépend de QmSetups.kt pour QmCandle, QmDirection et QmStatus (même package).
 *
 * Utilisation (un bouton = un type) :
 *   val bougies = tesBougies.map { QmCandle(it.time, it.open, it.high, it.low, it.close) }
 *   val signaux = SnrSetups.detect(bougies, SnrType.SNRC1)   // SNRC2, SNRC3 pour les autres boutons
 *
 * Principe (déduit des schémas, pas d'un cours) :
 *   1. Un niveau SNR "malaisien" = la plus haute clôture autour d'un sommet (les mèches sont ignorées).
 *   2. Une bougie impulsive clôture au-dessus du niveau, dans le sens de la tendance.
 *   3. Le prix s'éloigne puis revient tester le niveau : c'est l'entrée (achat ; vente = miroir).
 *
 * Les 3 variantes (un setup n'a qu'un seul type, le plus précis gagne) :
 *   SNRC3 : une bougie de rejet (pin bar ou englobante) confirme le test du niveau.
 *   SNRC2 : une base (petites bougies, RBR/DBD) précède la cassure.
 *   SNRC1 : cassure puis test simple, sans base ni bougie de confirmation.
 *
 * Un setup encore en attente de test ne peut pas être SNRC3, car la bougie de confirmation n'existe pas encore.
 */

enum class SnrType { SNRC1, SNRC2, SNRC3 }

data class SnrConfig(
    val fractal: Int = 3,                 // bougies de chaque côté pour valider un sommet
    val atrPeriod: Int = 14,
    val trendMaPeriod: Int = 50,          // tendance : clôture au-dessus de la MA, et MA montante
    val maxBarsToBreak: Int = 60,         // délai max entre le sommet et la cassure
    val maxBarsToRetest: Int = 40,        // délai max entre la cassure et le test
    val minSwingAtr: Double = 1.0,        // le prix doit être redescendu d'au moins ça sous le niveau avant la cassure
    val breakBufferAtr: Double = 0.1,     // la clôture doit dépasser le niveau d'au moins ça
    val breakBodyAtr: Double = 0.8,       // corps mini de la bougie de cassure
    val minAwayAtr: Double = 1.0,         // le prix doit s'éloigner d'au moins ça avant le test
    val touchTolAtr: Double = 0.1,        // tolérance pour considérer le niveau touché
    val failCloseAtr: Double = 0.25,      // clôture sous le niveau d'au moins ça = test raté
    val invalidCloseAtr: Double = 0.5,    // clôture sous le niveau avant le test = fausse cassure
    val slAtr: Double = 1.0,              // stop sous le niveau
    val minRR: Double = 1.5,              // ratio mini entrée -> TP1 / risque
    val tp2R: Double = 3.0,               // TP2 en multiples du risque
    val baseWindow: Int = 6,              // bougies regardées avant la cassure pour trouver la base
    val baseRangeAtr: Double = 0.6,       // une bougie "de base" a une amplitude <= ça (en ATR)
    val baseMinCandles: Int = 2,
    val zoneAtr: Double = 0.15,           // demi-épaisseur de la zone autour du niveau
    val includeResolved: Boolean = false  // inclure les setups terminés ou invalidés
)

data class SnrSetup(
    val type: SnrType,
    val direction: QmDirection,
    val pivotIdx: Int,       // sommet (ou creux) d'origine du niveau
    val breakIdx: Int,       // bougie de cassure
    val retestIdx: Int?,     // bougie qui teste le niveau (null = en attente)
    val confirmIdx: Int?,    // bougie de rejet (SNRC3 seulement)
    val level: Double,
    val zoneHigh: Double,
    val zoneLow: Double,
    val entry: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val status: QmStatus
)

object SnrSetups {

    fun detect(
        candles: List<QmCandle>,
        type: SnrType? = null,
        cfg: SnrConfig = SnrConfig()
    ): List<SnrSetup> {
        if (candles.size < cfg.trendMaPeriod + cfg.fractal * 2 + 5) return emptyList()

        val buys = detectBuy(candles, cfg)

        // Les ventes sont les achats vus dans un miroir (prix * -1, high <-> low)
        val mirrored = candles.map { QmCandle(it.time, -it.open, -it.low, -it.high, -it.close) }
        val sells = detectBuy(mirrored, cfg).map { unmirror(it) }

        var all = buys + sells
        if (!cfg.includeResolved) {
            all = all.filter { it.status == QmStatus.PENDING || it.status == QmStatus.TRIGGERED }
        }
        if (type != null) all = all.filter { it.type == type }
        return all.sortedByDescending { it.breakIdx }
    }

    // ---------------------------------------------------------------- détection (côté achat)

    private fun detectBuy(c: List<QmCandle>, cfg: SnrConfig): List<SnrSetup> {
        val atr = atr(c, cfg.atrPeriod)
        val sma = sma(c, cfg.trendMaPeriod)
        val n = cfg.fractal
        val out = ArrayList<SnrSetup>()

        for (p in pivotHighs(c, n)) {
            // 1. Niveau SNR malaisien : plus haute clôture autour du sommet
            var level = Double.NEGATIVE_INFINITY
            for (j in max(0, p - n)..min(c.lastIndex, p + n)) level = max(level, c[j].close)

            // 2. Première clôture au-dessus du niveau = cassure
            var breakIdx = -1
            val lastScan = min(c.lastIndex, p + n + cfg.maxBarsToBreak)
            for (i in p + 1..lastScan) {
                if (c[i].close > level + cfg.breakBufferAtr * atr[i]) { breakIdx = i; break }
            }
            if (breakIdx < 0) continue

            // Un vrai creux s'est formé sous le niveau avant la cassure
            var lowest = Double.POSITIVE_INFINITY
            for (i in p..breakIdx) lowest = min(lowest, c[i].low)
            if (level - lowest < cfg.minSwingAtr * atr[breakIdx]) continue

            // Bougie de cassure impulsive et haussière
            val bk = c[breakIdx]
            if (bk.close <= bk.open || bk.close - bk.open < cfg.breakBodyAtr * atr[breakIdx]) continue

            // Tendance : clôture au-dessus de la MA et MA montante
            val ma = sma[breakIdx]
            val maRef = sma[max(0, breakIdx - 10)]
            if (ma.isNaN() || maRef.isNaN() || bk.close <= ma || ma <= maRef) continue

            // Base (RBR) juste avant la cassure
            var small = 0
            for (j in max(0, breakIdx - cfg.baseWindow) until breakIdx) {
                val nearLevel = c[j].close >= level - atr[j]
                if (nearLevel && c[j].high - c[j].low <= cfg.baseRangeAtr * atr[j]) small++
            }
            val hasBase = small >= cfg.baseMinCandles

            // 3. Éloignement puis test du niveau
            var maxHigh = bk.high
            var retestIdx = -1
            var invalid = false
            val lastRetest = min(c.lastIndex, breakIdx + cfg.maxBarsToRetest)
            for (j in breakIdx + 1..lastRetest) {
                val touched = c[j].low <= level + cfg.touchTolAtr * atr[j]
                if (touched && maxHigh - level >= cfg.minAwayAtr * atr[j]) {
                    if (c[j].close < level - cfg.failCloseAtr * atr[j]) invalid = true else retestIdx = j
                    break
                }
                if (c[j].close < level - cfg.invalidCloseAtr * atr[j]) { invalid = true; break }
                maxHigh = max(maxHigh, c[j].high)
            }
            val expired = retestIdx < 0 && !invalid && breakIdx + cfg.maxBarsToRetest < c.lastIndex

            // Niveaux de trade
            val entry = level
            val sl = level - cfg.slAtr * atr[breakIdx]
            val risk = entry - sl
            if (risk <= 0.0) continue
            val tp1 = maxHigh
            val rr = (tp1 - entry) / risk
            if (rr < cfg.minRR) continue
            val tp2 = max(tp1, entry + cfg.tp2R * risk)
            val zone = cfg.zoneAtr * atr[breakIdx]

            // Bougie de rejet sur le test (ou la suivante)
            var confirmIdx: Int? = null
            if (retestIdx >= 0) {
                if (bullConfirm(c, retestIdx, level)) {
                    confirmIdx = retestIdx
                } else {
                    val nx = retestIdx + 1
                    if (nx <= c.lastIndex &&
                        c[nx].low >= level - cfg.failCloseAtr * atr[nx] &&
                        bullConfirm(c, nx, level)
                    ) confirmIdx = nx
                }
            }

            val type = when {
                confirmIdx != null -> SnrType.SNRC3
                hasBase -> SnrType.SNRC2
                else -> SnrType.SNRC1
            }

            // Statut
            var status = when {
                invalid || expired -> QmStatus.INVALID
                retestIdx < 0 -> QmStatus.PENDING
                else -> QmStatus.TRIGGERED
            }
            if (retestIdx >= 0 && !invalid) {
                if (c[retestIdx].low <= sl) {
                    status = QmStatus.INVALID
                } else {
                    for (k in retestIdx + 1..c.lastIndex) {
                        if (c[k].low <= sl) { status = QmStatus.INVALID; break }
                        if (c[k].high >= tp1) { status = QmStatus.COMPLETED; break }
                    }
                }
            }

            out += SnrSetup(
                type = type,
                direction = QmDirection.BUY,
                pivotIdx = p,
                breakIdx = breakIdx,
                retestIdx = if (retestIdx >= 0) retestIdx else null,
                confirmIdx = confirmIdx,
                level = level,
                zoneHigh = level + zone,
                zoneLow = level - zone,
                entry = entry,
                stopLoss = sl,
                tp1 = tp1,
                tp2 = tp2,
                rr = rr,
                status = status
            )
        }
        return out.distinctBy { it.breakIdx }
    }

    private fun unmirror(s: SnrSetup) = s.copy(
        direction = QmDirection.SELL,
        level = -s.level,
        zoneHigh = -s.zoneLow,
        zoneLow = -s.zoneHigh,
        entry = -s.entry,
        stopLoss = -s.stopLoss,
        tp1 = -s.tp1,
        tp2 = -s.tp2
    )

    // ---------------------------------------------------------------- outils

    /** Rejet haussier sur le niveau : pin bar (grande mèche basse) ou bougie englobante. */
    private fun bullConfirm(c: List<QmCandle>, j: Int, level: Double): Boolean {
        val cd = c[j]
        if (cd.close <= cd.open || cd.close <= level) return false
        val range = cd.high - cd.low
        if (range <= 0.0) return false
        val lowerWick = min(cd.open, cd.close) - cd.low
        val pin = lowerWick >= 0.5 * range
        var engulf = false
        if (j >= 1) {
            val pv = c[j - 1]
            engulf = pv.close < pv.open && cd.close >= pv.open && cd.open <= pv.close
        }
        return pin || engulf
    }

    /** Indices des sommets (fractals) confirmés. */
    private fun pivotHighs(c: List<QmCandle>, n: Int): List<Int> {
        val out = ArrayList<Int>()
        for (i in n until c.size - n) {
            var ok = true
            for (j in 1..n) {
                if (c[i].high < c[i - j].high || c[i].high < c[i + j].high) { ok = false; break }
            }
            if (ok) out += i
        }
        return out
    }

    private fun atr(c: List<QmCandle>, period: Int): DoubleArray {
        val out = DoubleArray(c.size)
        var prev = 0.0
        for (i in c.indices) {
            val tr = if (i == 0) {
                c[i].high - c[i].low
            } else {
                max(
                    c[i].high - c[i].low,
                    max(abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close))
                )
            }
            prev = if (i < period) (prev * i + tr) / (i + 1) else (prev * (period - 1) + tr) / period
            out[i] = prev
        }
        return out
    }

    private fun sma(c: List<QmCandle>, period: Int): DoubleArray {
        val out = DoubleArray(c.size) { Double.NaN }
        var sum = 0.0
        for (i in c.indices) {
            sum += c[i].close
            if (i >= period) sum -= c[i - period].close
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }
}
