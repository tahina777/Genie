// ⚠️ Mets ici le MÊME package que tes autres fichiers du dossier signals/
package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * QmSetups.kt : détection des setups QMR, QMC, QMM, QM2P (fiche Bystra).
 *
 * Utilisation (un bouton = un type) :
 *   val bougies = tesBougies.map { QmCandle(it.time, it.open, it.high, it.low, it.close) }
 *   val signaux = QmSetups.detect(bougies, QmType.QMR)   // bouton QMR
 *   // QmType.QMC, QmType.QMM, QmType.QM2P pour les autres boutons
 *
 * Chaque QmSetup donne de quoi dessiner : indices des points (shoulder, base, head, break),
 * zone (zoneLow..zoneHigh), entrée, stop, TP1, TP2 et statut.
 *
 * ⚠️ Les règles sont DÉDUITES des schémas de la fiche, pas d'un cours.
 *    Tous les réglages sont dans QmConfig pour pouvoir les ajuster.
 */

data class QmCandle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

enum class QmType { QMR, QMC, QMM, QM2P }
enum class QmDirection { BUY, SELL }
enum class QmStatus { PENDING, TRIGGERED, COMPLETED, INVALID }

data class QmConfig(
    val fractal: Int = 3,                 // bougies de chaque côté pour valider un pivot
    val atrPeriod: Int = 14,
    val minLegAtr: Double = 1.0,          // taille mini de la jambe A -> B (en ATR)
    val maxBarsAfterB: Int = 80,          // délai max entre B et la cassure
    val headMinExcessAtr: Double = 0.05,  // la tête doit dépasser l'épaule d'au moins ça
    val impulseBodyAtr: Double = 1.0,     // corps mini d'une bougie impulsive (ou présence d'un FVG)
    val slBufferAtr: Double = 0.5,        // marge du stop au-delà de la tête
    val minRR: Double = 2.0,              // ratio mini entrée -> TP2 / risque
    val trendLookback: Int = 100,         // fenêtre pour savoir si la tête prend une liquidité externe
    val trendMaPeriod: Int = 50,          // moyenne mobile pour le sens de la tendance (QMC)
    val baseRangeAtr: Double = 0.6,       // une bougie "de base" a une amplitude <= ça (en ATR)
    val baseMinCandles: Int = 2,          // nb mini de bougies de base autour de l'épaule (QMM)
    val twoPTolAtr: Double = 0.5,         // écart max entre les 2 points de la tête (QM2P)
    val includeResolved: Boolean = false  // inclure les setups déjà terminés ou invalidés
)

data class QmSetup(
    val types: Set<QmType>,
    val direction: QmDirection,
    val shoulderIdx: Int,   // A : épaule, c'est son niveau qui sert de zone d'entrée
    val baseIdx: Int,       // B : pivot opposé après l'épaule
    val headIdx: Int,       // C : tête (extrême qui prend la liquidité)
    val head2Idx: Int?,     // second point de la tête (QM2P seulement)
    val breakIdx: Int,      // D : bougie dont la clôture casse B (le CHoCH)
    val zoneHigh: Double,
    val zoneLow: Double,
    val entry: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val status: QmStatus,
    val triggerIdx: Int?
)

object QmSetups {

    fun detect(
        candles: List<QmCandle>,
        type: QmType? = null,
        cfg: QmConfig = QmConfig()
    ): List<QmSetup> {
        if (candles.size < cfg.fractal * 2 + cfg.atrPeriod + 5) return emptyList()

        val sells = detectSell(candles, cfg)

        // Les achats sont les ventes vues dans un miroir (prix * -1, high <-> low)
        val mirrored = candles.map { QmCandle(it.time, -it.open, -it.low, -it.high, -it.close) }
        val buys = detectSell(mirrored, cfg).map { unmirror(it) }

        var all = sells + buys
        if (!cfg.includeResolved) {
            all = all.filter { it.status == QmStatus.PENDING || it.status == QmStatus.TRIGGERED }
        }
        if (type != null) all = all.filter { type in it.types }
        return all.sortedByDescending { it.breakIdx }
    }

    // ---------------------------------------------------------------- détection (côté vente)

    private fun detectSell(c: List<QmCandle>, cfg: QmConfig): List<QmSetup> {
        val atr = atr(c, cfg.atrPeriod)
        val sma = sma(c, cfg.trendMaPeriod)
        val pivots = pivots(c, cfg.fractal)
        val out = ArrayList<QmSetup>()

        for (k in 0 until pivots.size - 1) {
            val a = pivots[k]        // épaule : pivot haut
            val b = pivots[k + 1]    // pivot bas qui suit
            if (!a.isHigh || b.isHigh) continue
            if (a.price - b.price < cfg.minLegAtr * atr[b.idx]) continue

            // Recherche de la tête puis de la cassure de B en clôture
            var headIdx = -1
            var headHigh = Double.NEGATIVE_INFINITY
            var breakIdx = -1
            val last = min(c.lastIndex, b.idx + cfg.maxBarsAfterB)
            for (i in b.idx + 1..last) {
                if (c[i].high > headHigh) { headHigh = c[i].high; headIdx = i }
                if (c[i].close < b.price) {
                    if (headIdx >= 0 && headHigh > a.price + cfg.headMinExcessAtr * atr[headIdx]) {
                        breakIdx = i
                    }
                    break
                }
            }
            if (breakIdx < 0 || headIdx >= breakIdx) continue

            // Mouvement impulsif entre la tête et la cassure : grosse bougie ou FVG
            var impulse = false
            for (i in headIdx + 1..breakIdx) {
                if (abs(c[i].close - c[i].open) >= cfg.impulseBodyAtr * atr[i]) impulse = true
            }
            for (j in headIdx + 1 until breakIdx) {
                if (c[j - 1].low > c[j + 1].high) impulse = true   // FVG baissier
            }
            if (!impulse) continue

            // Zone d'entrée = épaule (du corps à la mèche), entrée au bord proche
            val shoulder = c[a.idx]
            val zoneLow = max(shoulder.open, shoulder.close)
            val zoneHigh = shoulder.high
            val entry = zoneLow
            val sl = headHigh + cfg.slBufferAtr * atr[headIdx]
            val tp1 = b.price
            var tp2 = Double.POSITIVE_INFINITY
            for (i in headIdx..breakIdx) tp2 = min(tp2, c[i].low)
            val risk = sl - entry
            if (risk <= 0.0) continue
            val rr = (entry - tp2) / risk
            if (rr < cfg.minRR) continue

            // Contexte : prise de liquidité externe = retournement (QMR), sinon continuation (QMC)
            val from = max(0, headIdx - cfg.trendLookback)
            var lookbackHigh = Double.NEGATIVE_INFINITY
            for (j in from until headIdx) lookbackHigh = max(lookbackHigh, c[j].high)
            val external = headHigh >= lookbackHigh
            val ma = sma[breakIdx]
            val trendDown = !ma.isNaN() && c[breakIdx].close < ma

            val types = mutableSetOf<QmType>()
            var head2: Int? = null
            if (external) {
                types += QmType.QMR

                // QMM : une base (petites bougies) autour de l'épaule
                var small = 0
                for (j in max(0, a.idx - 2)..min(c.lastIndex, a.idx + 2)) {
                    if (c[j].high - c[j].low <= cfg.baseRangeAtr * atr[j]) small++
                }
                if (small >= cfg.baseMinCandles) types += QmType.QMM

                // QM2P : la tête est faite de 2 sommets proches, tous deux au-dessus de l'épaule
                val headPivots = pivots.filter {
                    it.isHigh && it.idx > b.idx && it.idx < breakIdx && it.price > a.price
                }
                if (headPivots.size >= 2) {
                    val sorted = headPivots.sortedByDescending { it.price }
                    val p1 = sorted[0]
                    val p2 = sorted[1]
                    if (abs(p1.price - p2.price) <= cfg.twoPTolAtr * atr[p1.idx]) {
                        types += QmType.QM2P
                        head2 = if (p1.idx == headIdx) p2.idx else p1.idx
                    }
                }
            } else if (trendDown) {
                types += QmType.QMC
            } else {
                continue   // contexte ambigu : on ignore
            }

            // Statut après la cassure
            var status = QmStatus.PENDING
            var trigger: Int? = null
            loop@ for (i in breakIdx + 1..c.lastIndex) {
                val cd = c[i]
                if (trigger == null) {
                    if (cd.close > headHigh) { status = QmStatus.INVALID; break@loop }
                    if (cd.high >= entry) {
                        trigger = i
                        status = QmStatus.TRIGGERED
                        if (cd.high >= sl) { status = QmStatus.INVALID; break@loop }
                    }
                } else {
                    if (cd.high >= sl) { status = QmStatus.INVALID; break@loop }
                    if (cd.low <= tp1) { status = QmStatus.COMPLETED; break@loop }
                }
            }

            out += QmSetup(
                types = types,
                direction = QmDirection.SELL,
                shoulderIdx = a.idx,
                baseIdx = b.idx,
                headIdx = headIdx,
                head2Idx = head2,
                breakIdx = breakIdx,
                zoneHigh = zoneHigh,
                zoneLow = zoneLow,
                entry = entry,
                stopLoss = sl,
                tp1 = tp1,
                tp2 = tp2,
                rr = rr,
                status = status,
                triggerIdx = trigger
            )
        }
        return out
    }

    private fun unmirror(s: QmSetup) = s.copy(
        direction = QmDirection.BUY,
        zoneHigh = -s.zoneLow,
        zoneLow = -s.zoneHigh,
        entry = -s.entry,
        stopLoss = -s.stopLoss,
        tp1 = -s.tp1,
        tp2 = -s.tp2
    )

    // ---------------------------------------------------------------- outils

    private data class Pivot(val idx: Int, val price: Double, val isHigh: Boolean)

    /** Pivots (fractals) alternés haut/bas. Le dernier pivot confirmé est à `n` bougies de la fin. */
    private fun pivots(c: List<QmCandle>, n: Int): List<Pivot> {
        val raw = ArrayList<Pivot>()
        for (i in n until c.size - n) {
            var isH = true
            var isL = true
            for (j in 1..n) {
                if (c[i].high < c[i - j].high || c[i].high < c[i + j].high) isH = false
                if (c[i].low > c[i - j].low || c[i].low > c[i + j].low) isL = false
            }
            if (isH && isL) continue
            if (isH) raw += Pivot(i, c[i].high, true)
            if (isL) raw += Pivot(i, c[i].low, false)
        }
        val out = ArrayList<Pivot>()
        for (p in raw) {
            val last = out.lastOrNull()
            if (last == null || last.isHigh != p.isHigh) {
                out += p
            } else if ((p.isHigh && p.price > last.price) || (!p.isHigh && p.price < last.price)) {
                out[out.lastIndex] = p
            }
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
