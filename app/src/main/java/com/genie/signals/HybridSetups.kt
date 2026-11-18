package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * HybridSetups.kt : setups Hybrid 1 et Hybrid 2 (fiche Bystra).
 * Dépend de QmSetups.kt (QmCandle, QmDirection, QmStatus, QmSetups) et de SnrSetups.kt (même package).
 *
 * Utilisation (un bouton = un type) :
 *   val bougies = tesBougies.map { QmCandle(it.time, it.open, it.high, it.low, it.close) }
 *   val signaux = HybridSetups.detect(bougies, HybridType.HYBRID1)   // HYBRID2 pour l'autre bouton
 *
 * Hybrid 1 (déduit du schéma « Hybrid 1 Formula ») :
 *   1. Ligne de tendance qui touche au moins 3 sommets (1, 2, 3) dans le sens de la tendance.
 *   2. Le prix casse la ligne en clôture après le point 3.
 *   3. Retest sur un setup RBR / DBD / SNRC1 / SNRC2 / QMR : c'est l'entrée.
 *   4. Zone de danger au départ de la tendance : le prix ne doit pas l'avoir touchée depuis la cassure.
 *   (achat sur tendance baissière cassée vers le haut ; vente = miroir)
 *
 * Hybrid 2 (déduit du schéma « Hybrid 2 formula » et des 4 points du texte) :
 *   1. Tendance précédente = ligne montante (vente) qui touche au moins 2 creux.
 *   2. Une seconde ligne, descendante, part du sommet 1 et passe par le sommet 2 ;
 *      une bougie de retournement (englobante ou pin bar) se forme au point 2.
 *   3. Le prix casse la ligne montante : la seconde ligne casse la tendance précédente.
 *   4. Entrée au point 3 : retour du prix sur la ligne descendante.
 *   5. Zone de danger au bas de la tendance : le prix ne doit pas l'avoir atteinte.
 *   (achat = miroir)
 *
 * ⚠️ Règles DÉDUITES des schémas, pas d'un cours (le texte de l'étape 1 de chaque setup n'était pas fourni).
 *    Le « plus grand timeframe » de l'étape 3 d'Hybrid 2 se vérifie à la main : Genie analyse chaque timeframe séparément.
 *    Tous les réglages sont dans HybridConfig.
 */

enum class HybridType { HYBRID1, HYBRID2 }

/** Droite du graphique entre deux points (indice de bougie, prix). */
data class HLine(val i1: Int, val p1: Double, val i2: Int, val p2: Double) {
    fun at(i: Int): Double = p1 + (p2 - p1) / (i2 - i1) * (i - i1)
}

data class HybridConfig(
    val fractal: Int = 3,                 // bougies de chaque côté pour valider un sommet / creux
    val atrPeriod: Int = 14,
    val lookback: Int = 220,              // nombre de bougies analysées en arrière
    val minSpan: Int = 12,                // Hybrid 1 : écart mini entre le point 1 et le point 3
    val maxSpan: Int = 120,               // Hybrid 1 : écart maxi
    val minDropAtr: Double = 1.5,         // Hybrid 1 : la ligne doit descendre (ou monter) d'au moins ça
    val touchTolAtr: Double = 0.3,        // tolérance pour qu'un point touche la ligne
    val breakBufferAtr: Double = 0.1,     // la clôture doit dépasser la ligne d'au moins ça
    val maxBarsToBreak: Int = 40,         // délai max entre le point 3 (ou 2) et la cassure
    val maxBarsSetupAfter: Int = 30,      // Hybrid 1 : délai max entre la cassure de la ligne et le setup de retest
    val nearLineAtr: Double = 3.0,        // Hybrid 1 : le setup doit être à cette distance max de la ligne
    val dangerAtr: Double = 0.5,          // épaisseur de la zone de danger
    val slBufferAtr: Double = 0.5,        // Hybrid 2 : marge du stop au-delà du dernier sommet
    val minRR: Double = 1.5,              // Hybrid 2 : ratio mini entrée -> zone de danger / risque
    val maxAwayAtr: Double = 3.0,         // Hybrid 2 : le prix doit être à cette distance max de la ligne d'entrée
    val requireReversal: Boolean = true   // Hybrid 2 : exige une bougie de retournement au point 2
)

data class HybridSetup(
    val type: HybridType,
    val direction: QmDirection,
    val via: String,          // Hybrid 1 : setup de retest (SNRC1/2, QMR...) ; Hybrid 2 : bougie de retournement
    val line1: HLine,         // Hybrid 1 : ligne de tendance cassée ; Hybrid 2 : ligne descendante (vente) d'entrée
    val line2: HLine?,        // Hybrid 2 : ligne montante de la tendance précédente
    val refIdx: Int,          // bougie de cassure
    val dangerLow: Double,
    val dangerHigh: Double,
    val zoneLow: Double,
    val zoneHigh: Double,
    val entry: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val status: QmStatus
)

object HybridSetups {

    fun detect(
        candles: List<QmCandle>,
        type: HybridType? = null,
        cfg: HybridConfig = HybridConfig()
    ): List<HybridSetup> {
        if (candles.size < 80) return emptyList()
        // Les setups inverses sont les setups vus dans un miroir (prix * -1, high <-> low)
        val mirrored = candles.map { QmCandle(it.time, -it.open, -it.low, -it.high, -it.close) }

        val all = ArrayList<HybridSetup>()
        if (type == null || type == HybridType.HYBRID1) {
            all.addAll(best(detectH1(candles, cfg)))
            all.addAll(best(detectH1(mirrored, cfg).map { unmirror(it) }))
        }
        if (type == null || type == HybridType.HYBRID2) {
            all.addAll(best(detectH2(candles, cfg)))
            all.addAll(best(detectH2(mirrored, cfg).map { unmirror(it) }))
        }
        return all.sortedByDescending { it.refIdx }
    }

    /** Un seul setup par type et par sens : le plus récent. */
    private fun best(list: List<HybridSetup>): List<HybridSetup> {
        val b = list.maxByOrNull { it.refIdx } ?: return emptyList()
        return listOf(b)
    }

    private fun unmirror(s: HybridSetup) = s.copy(
        direction = if (s.direction == QmDirection.BUY) QmDirection.SELL else QmDirection.BUY,
        line1 = HLine(s.line1.i1, -s.line1.p1, s.line1.i2, -s.line1.p2),
        line2 = s.line2?.let { HLine(it.i1, -it.p1, it.i2, -it.p2) },
        dangerLow = -s.dangerHigh,
        dangerHigh = -s.dangerLow,
        zoneLow = -s.zoneHigh,
        zoneHigh = -s.zoneLow,
        entry = -s.entry,
        stopLoss = -s.stopLoss,
        tp1 = -s.tp1,
        tp2 = -s.tp2
    )

    // ---------------------------------------------------------------- outils

    private class Piv(val idx: Int, val price: Double, val isHigh: Boolean)

    private class Under(
        val via: String,
        val breakIdx: Int,
        val zoneLow: Double,
        val zoneHigh: Double,
        val entry: Double,
        val sl: Double,
        val tp1: Double,
        val tp2: Double,
        val rr: Double,
        val status: QmStatus
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

    private fun pivots(c: List<QmCandle>, k: Int, from: Int): List<Piv> {
        val out = ArrayList<Piv>()
        for (i in max(k, from) until c.size - k) {
            var isHigh = true
            var isLow = true
            for (j in 1..k) {
                if (c[i].high <= c[i - j].high || c[i].high < c[i + j].high) isHigh = false
                if (c[i].low >= c[i - j].low || c[i].low > c[i + j].low) isLow = false
            }
            if (isHigh) out.add(Piv(i, c[i].high, true))
            if (isLow) out.add(Piv(i, c[i].low, false))
        }
        return out
    }

    // ---------------------------------------------------------------- Hybrid 1 (côté achat)

    private fun detectH1(c: List<QmCandle>, cfg: HybridConfig): List<HybridSetup> {
        val n = c.size
        val atr = atrArr(c, cfg.atrPeriod)
        val from = max(0, n - cfg.lookback)
        val highs = pivots(c, cfg.fractal, from).filter { it.isHigh }
        if (highs.size < 3) return emptyList()

        // Setups de retest possibles : SNRC1/2/3 et QMR dans le sens de l'achat
        val under = ArrayList<Under>()
        for (s in SnrSetups.detect(c)) {
            if (s.direction != QmDirection.BUY) continue
            under.add(Under(s.type.name, s.breakIdx, s.zoneLow, s.zoneHigh, s.entry, s.stopLoss, s.tp1, s.tp2, s.rr, s.status))
        }
        for (s in QmSetups.detect(c)) {
            if (s.direction != QmDirection.BUY || QmType.QMR !in s.types) continue
            under.add(Under("QMR", s.breakIdx, s.zoneLow, s.zoneHigh, s.entry, s.stopLoss, s.tp1, s.tp2, s.rr, s.status))
        }
        if (under.isEmpty()) return emptyList()

        val out = ArrayList<HybridSetup>()
        for (ai in 0 until highs.size - 2) {
            val a = highs[ai]
            for (ci in ai + 2 until highs.size) {
                val p3 = highs[ci]
                val span = p3.idx - a.idx
                if (span < cfg.minSpan || span > cfg.maxSpan) continue
                if (a.price - p3.price < cfg.minDropAtr * atr[p3.idx]) continue
                val tl = HLine(a.idx, a.price, p3.idx, p3.price)

                // point 2 : au moins un sommet intermédiaire sur la ligne, aucun sommet au-dessus
                var touches = 0
                var ok = true
                for (m in highs) {
                    if (m.idx <= a.idx || m.idx >= p3.idx) continue
                    val dev = m.price - tl.at(m.idx)
                    val tol = cfg.touchTolAtr * atr[m.idx]
                    if (dev > tol) { ok = false; break }
                    if (abs(dev) <= tol) touches++
                }
                if (!ok || touches < 1) continue

                // cassure de la ligne en clôture après le point 3
                var brk = -1
                var i = p3.idx + 1
                while (i < n && i - p3.idx <= cfg.maxBarsToBreak) {
                    if (c[i].close > tl.at(i) + cfg.breakBufferAtr * atr[i]) { brk = i; break }
                    i++
                }
                if (brk < 0) continue

                // zone de danger : en haut de la jambe baissière, elle ne doit pas avoir été touchée depuis la cassure
                var top = Double.NEGATIVE_INFINITY
                for (j in a.idx..brk) top = max(top, c[j].high)
                val dzLow = top - cfg.dangerAtr * atr[brk]
                val dzHigh = top + 0.25 * atr[brk]
                var maxAfter = Double.NEGATIVE_INFINITY
                for (j in brk until n) maxAfter = max(maxAfter, c[j].high)
                if (maxAfter >= dzLow) continue

                // retest sur un setup proche de la ligne
                for (u in under) {
                    if (u.breakIdx < brk - 3 || u.breakIdx > brk + cfg.maxBarsSetupAfter) continue
                    val ref = min(u.breakIdx, n - 1)
                    val mid = (u.zoneLow + u.zoneHigh) / 2.0
                    if (abs(mid - tl.at(ref)) > cfg.nearLineAtr * atr[ref]) continue
                    if (u.entry - u.sl <= 0.0 || dzLow <= u.entry) continue
                    out.add(
                        HybridSetup(
                            HybridType.HYBRID1, QmDirection.BUY, u.via, tl, null, brk,
                            dzLow, dzHigh, u.zoneLow, u.zoneHigh, u.entry, u.sl, u.tp1, u.tp2, u.rr, u.status
                        )
                    )
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Hybrid 2 (côté vente)

    private fun detectH2(c: List<QmCandle>, cfg: HybridConfig): List<HybridSetup> {
        val n = c.size
        val atr = atrArr(c, cfg.atrPeriod)
        val from = max(0, n - cfg.lookback)
        val pv = pivots(c, cfg.fractal, from)
        val highs = pv.filter { it.isHigh }
        val lows = pv.filter { !it.isHigh }
        if (highs.size < 2 || lows.size < 2) return emptyList()
        val last = n - 1
        val aLast = atr[last]

        val out = ArrayList<HybridSetup>()
        for (h1 in highs.indices) {
            val p1 = highs[h1]
            for (h2 in h1 + 1 until highs.size) {
                val p2 = highs[h2]
                val gap = p2.idx - p1.idx
                if (gap < 4 || gap > 60) continue
                if (p1.price - p2.price < 0.3 * atr[p2.idx]) continue
                val dl = HLine(p1.idx, p1.price, p2.idx, p2.price)

                // la ligne descendante doit tenir comme résistance depuis le sommet 1
                var holds = true
                for (i in p1.idx + 1..last) {
                    if (c[i].close > dl.at(i) + cfg.touchTolAtr * atr[i]) { holds = false; break }
                }
                if (!holds) continue

                // tendance précédente : ligne montante par au moins 2 creux, sans creux dessous
                var bestU: HLine? = null
                var bestFirst: Piv? = null
                var bestTouch = 0
                for (la in lows.indices) {
                    val l1 = lows[la]
                    if (l1.idx >= p1.idx) break
                    for (lb in la + 1 until lows.size) {
                        val l2 = lows[lb]
                        if (l2.idx > p2.idx) break
                        if (l2.idx - l1.idx < 8) continue
                        if (l2.price - l1.price < 0.5 * atr[l2.idx]) continue
                        val u = HLine(l1.idx, l1.price, l2.idx, l2.price)
                        if (p1.price - u.at(p1.idx) < atr[p1.idx]) continue
                        var ok = true
                        var touches = 2
                        for (m in lows) {
                            if (m.idx <= l1.idx || m.idx >= l2.idx) continue
                            val dev = m.price - u.at(m.idx)
                            val tol = cfg.touchTolAtr * atr[m.idx]
                            if (dev < -tol) { ok = false; break }
                            if (dev <= tol) touches++
                        }
                        if (!ok) continue
                        if (touches >= bestTouch) {
                            bestU = u
                            bestFirst = l1
                            bestTouch = touches
                        }
                    }
                }
                val u = bestU ?: continue
                val l0 = bestFirst ?: continue

                // la ligne montante est cassée en clôture, autour du sommet 2
                var brk = -1
                var i = max(p1.idx, p2.idx - 2)
                while (i < n && i - p2.idx <= cfg.maxBarsToBreak) {
                    if (c[i].close < u.at(i) - cfg.breakBufferAtr * atr[i]) { brk = i; break }
                    i++
                }
                if (brk < 0 || brk >= last) continue

                // bougie de retournement au point 2 : englobante ou pin bar
                var via = ""
                for (j in max(1, p2.idx - 1)..min(last, p2.idx + 2)) {
                    val cur = c[j]
                    val prev = c[j - 1]
                    val body = abs(cur.close - cur.open)
                    val pBody = abs(prev.close - prev.open)
                    val range = cur.high - cur.low
                    val upper = cur.high - max(cur.open, cur.close)
                    if (cur.close < cur.open && prev.close > prev.open &&
                        cur.open >= prev.close && cur.close <= prev.open && body > pBody
                    ) {
                        via = "Engulfing"
                        break
                    }
                    if (range >= 0.5 * atr[j] && upper >= 2 * body && upper >= 0.6 * range) {
                        via = "Pin bar"
                        break
                    }
                }
                if (cfg.requireReversal && via.isEmpty()) continue

                // zone de danger au bas de la tendance : le prix ne doit pas l'avoir atteinte
                val dzLow = l0.price - 0.25 * atr[l0.idx]
                val dzHigh = l0.price + cfg.dangerAtr * atr[l0.idx]
                var minAfter = Double.POSITIVE_INFINITY
                for (j in p1.idx..last) minAfter = min(minAfter, c[j].low)
                if (minAfter <= dzHigh) continue

                // entrée : retour du prix sur la ligne descendante (point 3)
                val dLast = dl.at(last)
                if (dLast - c[last].close > cfg.maxAwayAtr * aLast) continue
                val zoneLow = dLast - cfg.touchTolAtr * aLast
                val zoneHigh = dLast + 0.15 * aLast
                var maxHigh = c[p2.idx].high
                for (j in p2.idx..last) maxHigh = max(maxHigh, c[j].high)
                val sl = maxHigh + cfg.slBufferAtr * aLast
                val risk = sl - dLast
                if (risk < 0.2 * aLast) continue
                val tp1 = dLast - 1.5 * risk
                val tp2 = max(dLast - 3.0 * risk, dzHigh)
                val rr = (dLast - dzHigh) / risk
                if (rr < cfg.minRR) continue
                val status = if (c[last].high >= zoneLow) QmStatus.TRIGGERED else QmStatus.PENDING

                out.add(
                    HybridSetup(
                        HybridType.HYBRID2, QmDirection.SELL, via, dl, u, brk,
                        dzLow, dzHigh, zoneLow, zoneHigh, dLast, sl, tp1, tp2, rr, status
                    )
                )
            }
        }
        return out
    }
}
