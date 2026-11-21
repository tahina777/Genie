package com.genie.signals

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * PatSetups.kt : Blindspot 1, Blindspot 2, CK1 et CK2 (fiche Bystra).
 * Dépend de QmSetups.kt (QmCandle, QmDirection, QmStatus) et de HybridSetups.kt (HLine), même package.
 *
 * Décrits en achat ; la vente est le miroir (prix * -1, high <-> low).
 *
 *  Blindspot 1 : « Failed Engulfing » sur un niveau (bougie baissière englobante dont le sommet est dépassé), puis
 *                « Significant break » (grosse bougie haussière qui clôture au-dessus), puis retest du niveau = entrée.
 *  Blindspot 2 : comme Blindspot 1, avec en plus une ligne de tendance 1-2 dont le point 3 tombe sur l'entrée.
 *  CK1         : niveau L (creux), puis 2 sommets 1 et 2 (ligne plate ou descendante), puis LL sous le niveau, puis
 *                grosse sortie au-dessus du sommet 1 ; retour sur la zone de L où la ligne 1-2 passe (point 3) = entrée.
 *  CK2         : triangle symétrique (ligne descendante par les sommets 1-2, ligne montante par les creux 1-2) après une
 *                baisse ; entrée au point 3, là où les 2 lignes se croisent.
 *
 * ⚠️ Règles DÉDUITES des images de la fiche (pas de texte). TP1 = 1,5 R et TP2 = 3 R sont des choix de Genie.
 *    La « Confirmation » (bougie englobante sur le timeframe supérieur) n'est pas vérifiée : à contrôler à la main.
 */

enum class PatType { BS1, BS2, CK1, CK2 }

fun patName(t: PatType): String = when (t) {
    PatType.BS1 -> "Blindspot 1"
    PatType.BS2 -> "Blindspot 2"
    PatType.CK1 -> "CK1"
    PatType.CK2 -> "CK2"
}

data class PatConfig(
    val atrPeriod: Int = 14,
    val fractal: Int = 3,
    val lookback: Int = 200,
    val bodyAtr: Double = 1.2,          // corps mini de la cassure significative (Blindspot)
    val maxBarsToBreak: Int = 12,       // délai max entre le Failed Engulfing et la cassure
    val maxBarsToRetest: Int = 60,
    val zoneAtr: Double = 0.2,          // demi-épaisseur de la zone d'entrée (Blindspot)
    val slAtr: Double = 1.0
)

data class PatSetup(
    val type: PatType,
    val direction: QmDirection,
    val refIdx: Int,        // bougie de départ du dessin
    val keyIdx: Int,        // bougie clé (cassure significative, sortie du pattern, dernier point du triangle)
    val zoneLow: Double,
    val zoneHigh: Double,
    val entry: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val status: QmStatus,
    val retestIdx: Int?,
    val lines: List<HLine>
)

object PatSetups {

    fun detect(
        candles: List<QmCandle>,
        type: PatType? = null,
        cfg: PatConfig = PatConfig()
    ): List<PatSetup> {
        if (candles.size < 60) return emptyList()
        val mirrored = candles.map { QmCandle(it.time, -it.open, -it.low, -it.high, -it.close) }
        val all = ArrayList<PatSetup>()
        all.addAll(detectAll(candles, cfg))
        all.addAll(detectAll(mirrored, cfg).map { unmirror(it) })
        return all
            .filter { type == null || it.type == type }
            .sortedByDescending { it.keyIdx }
    }

    private fun detectAll(c: List<QmCandle>, cfg: PatConfig): List<PatSetup> {
        val atr = atrArr(c, cfg.atrPeriod)
        val out = ArrayList<PatSetup>()
        out.addAll(detectBlind(c, atr, cfg))
        out.addAll(detectCk1(c, atr, cfg))
        out.addAll(detectCk2(c, atr, cfg))
        return out
    }

    private fun unmirror(s: PatSetup) = s.copy(
        direction = QmDirection.SELL,
        zoneLow = -s.zoneHigh,
        zoneHigh = -s.zoneLow,
        entry = -s.entry,
        stopLoss = -s.stopLoss,
        tp1 = -s.tp1,
        tp2 = -s.tp2,
        lines = s.lines.map { HLine(it.i1, -it.p1, it.i2, -it.p2) }
    )

    // ---------------------------------------------------------------- Blindspot 1 et 2 (côté achat)

    private fun detectBlind(c: List<QmCandle>, atr: DoubleArray, cfg: PatConfig): List<PatSetup> {
        val n = c.size
        val out = ArrayList<PatSetup>()
        for (f in max(1, n - cfg.lookback) until n - 2) {
            val cf = c[f]
            val pv = c[f - 1]
            // Failed Engulfing : bougie baissière qui englobe la précédente (haussière)
            if (!(cf.close < cf.open && pv.close > pv.open && cf.open >= pv.close && cf.close <= pv.open)) continue
            val a = atr[f]
            val level = cf.open

            // Significant break : grosse bougie haussière qui clôture au-dessus du sommet de l'englobante
            var s = -1
            val lastS = min(n - 1, f + cfg.maxBarsToBreak)
            for (i in f + 1..lastS) {
                if (c[i].close < cf.close - 0.5 * a) break // l'englobante a fonctionné : ce n'est pas un échec
                val body = c[i].close - c[i].open
                if (body >= cfg.bodyAtr * atr[i] && c[i].close > cf.high + 0.2 * atr[i]) { s = i; break }
            }
            if (s < 0) continue

            val entry = level + cfg.zoneAtr * a
            val sl = level - cfg.slAtr * a
            val risk = entry - sl
            if (risk <= 0.0) continue

            // Retest du niveau depuis le haut
            var r = -1
            var dead = false
            var maxHigh = c[s].high
            for (i in s + 1 until min(n, s + cfg.maxBarsToRetest + 1)) {
                if (c[i].close < level - 0.5 * atr[i]) { dead = true; break }
                if (c[i].low <= entry && maxHigh - level >= atr[i]) { r = i; break }
                maxHigh = max(maxHigh, c[i].high)
            }
            if (dead) continue
            if (r < 0 && s + cfg.maxBarsToRetest < n - 1) continue
            if (r >= 0 && r < n - 40) continue

            val tp1 = entry + 1.5 * risk
            val tp2 = entry + 3.0 * risk
            val status = statusOf(c, r, sl, tp1, s + 1) ?: continue

            // Blindspot 2 : ligne de tendance montante par 2 creux avant la cassure, point 3 sur l'entrée
            var type = PatType.BS1
            val lines = ArrayList<HLine>()
            val lowsIdx = pivotIdx(c, cfg.fractal, false, max(0, s - 80)).filter { it < s - 1 }
            if (lowsIdx.size >= 2) {
                val q1 = lowsIdx[lowsIdx.size - 2]
                val q2 = lowsIdx[lowsIdx.size - 1]
                if (c[q2].low > c[q1].low && lineHits(q1, c[q1].low, q2, c[q2].low, entry, a, r, n - 1, true)) {
                    type = PatType.BS2
                    lines.add(HLine(q1, c[q1].low, q2, c[q2].low))
                }
            }

            out.add(
                PatSetup(
                    type, QmDirection.BUY, f, s, level - cfg.zoneAtr * a, entry, entry, sl, tp1, tp2, 3.0,
                    status, if (r >= 0) r else null, lines
                )
            )
        }
        return out.distinctBy { it.keyIdx }
    }

    // ---------------------------------------------------------------- CK1 (côté achat)

    private fun detectCk1(c: List<QmCandle>, atr: DoubleArray, cfg: PatConfig): List<PatSetup> {
        val n = c.size
        val out = ArrayList<PatSetup>()
        val from = max(0, n - cfg.lookback)
        val lows = pivotIdx(c, cfg.fractal, false, from)
        val highs = pivotIdx(c, cfg.fractal, true, from)

        for (l in lows) {
            val z = c[l].low
            val a = atr[l]
            val hs = highs.filter { it > l && it <= l + 60 }
            for (k in 0 until hs.size - 1) {
                val i1 = hs[k]
                val i2 = hs[k + 1]
                val p1 = c[i1].high
                val p2 = c[i2].high
                if (i2 - i1 < 3 || p1 < z + 0.5 * a || p2 > p1 + 0.3 * a) continue

                // LL : creux sous le niveau après le sommet 2
                var m = -1
                for (j in lows) {
                    if (j > i2 && j <= i2 + 40 && c[j].low < z - 0.3 * a) { m = j; break }
                }
                if (m < 0) continue

                // Grosse sortie : clôture au-dessus du sommet 1
                var u = -1
                for (i in m + 1 until min(n, m + 51)) {
                    if (c[i].close > p1) { u = i; break }
                }
                if (u < 0) continue

                val au = atr[u]
                val entry = z + 0.4 * au
                val sl = z - au
                val risk = entry - sl
                if (risk <= 0.0) continue

                var r = -1
                var dead = false
                var maxHigh = c[u].high
                for (i in u + 1 until min(n, u + cfg.maxBarsToRetest + 1)) {
                    if (c[i].close < z - 0.5 * atr[i]) { dead = true; break }
                    if (c[i].low <= entry && maxHigh - z >= 2.0 * atr[i]) { r = i; break }
                    maxHigh = max(maxHigh, c[i].high)
                }
                if (dead) continue
                if (r < 0 && u + cfg.maxBarsToRetest < n - 1) continue
                if (r >= 0 && r < n - 40) continue

                // La ligne 1-2 doit passer sur la zone au moment de l'entrée (point 3)
                if (!lineHits(i1, p1, i2, p2, entry, au, r, n - 1, false)) continue

                val tp1 = entry + 1.5 * risk
                val tp2 = entry + 3.0 * risk
                val status = statusOf(c, r, sl, tp1, u + 1) ?: continue
                out.add(
                    PatSetup(
                        PatType.CK1, QmDirection.BUY, l, u, z, entry, entry, sl, tp1, tp2, 3.0,
                        status, if (r >= 0) r else null, listOf(HLine(i1, p1, i2, p2))
                    )
                )
                break
            }
        }
        return out.distinctBy { it.keyIdx }
    }

    // ---------------------------------------------------------------- CK2 (côté achat)

    private fun detectCk2(c: List<QmCandle>, atr: DoubleArray, cfg: PatConfig): List<PatSetup> {
        val n = c.size
        val from = max(0, n - 130)
        val highs = pivotIdx(c, cfg.fractal, true, from)
        val lows = pivotIdx(c, cfg.fractal, false, from)
        if (highs.size < 2 || lows.size < 2) return emptyList()
        val a1 = highs[highs.size - 2]
        val a2 = highs[highs.size - 1]
        val b1 = lows[lows.size - 2]
        val b2 = lows[lows.size - 1]
        val a = atr[n - 1]
        val ah1 = c[a1].high
        val ah2 = c[a2].high
        val bl1 = c[b1].low
        val bl2 = c[b2].low
        if (ah2 >= ah1 - 0.2 * a || bl2 <= bl1 + 0.2 * a) return emptyList()

        val first = min(a1, b1)
        val last = max(a2, b2)
        if (last - first < 12 || last - first > 120) return emptyList()

        // Les deux lignes convergent : le croisement (point 3) est proche
        val slopeU = (ah2 - ah1) / (a2 - a1)
        val slopeL = (bl2 - bl1) / (b2 - b1)
        val tStar = (bl1 - ah1 + slopeU * a1 - slopeL * b1) / (slopeU - slopeL)
        if (tStar < last || tStar > n - 1 + 25) return emptyList()
        val gapFirst = (ah1 + slopeU * (first - a1)) - (bl1 + slopeL * (first - b1))
        if (gapFirst < 1.5 * a) return emptyList()

        // Mouvement qui précède le triangle : une baisse (achat)
        val m = min(first, 25)
        if (m < 5) return emptyList()
        if (c[first].close - c[first - m].close > -2.0 * a) return emptyList()

        val lastClose = c[n - 1].close
        val lowerNow = bl1 + slopeL * (n - 1 - b1)
        val upperNow = ah1 + slopeU * (n - 1 - a1)
        if (lastClose < lowerNow - a || lastClose > upperNow + a) return emptyList()

        val apex = ah1 + slopeU * (tStar - a1)
        val entry = apex
        val sl = apex - 1.5 * a
        val risk = entry - sl
        val tp1 = entry + 1.5 * risk
        val tp2 = entry + 3.0 * risk

        var r = -1
        val startT = max(last + 1, floor(tStar).toInt() - 3)
        for (i in startT until n) {
            val lowI = bl1 + slopeL * (i - b1)
            if (c[i].close < lowI - a) return emptyList() // cassure du triangle vers le bas
            if (c[i].low <= entry + 0.1 * a) { r = i; break }
        }
        if (r >= 0 && r < n - 40) return emptyList()
        val status = statusOf(c, r, sl, tp1, last + 1) ?: return emptyList()

        return listOf(
            PatSetup(
                PatType.CK2, QmDirection.BUY, first, last, apex - 0.25 * a, apex + 0.25 * a, entry, sl, tp1, tp2, 3.0,
                status, if (r >= 0) r else null,
                listOf(HLine(a1, ah1, a2, ah2), HLine(b1, bl1, b2, bl2))
            )
        )
    }

    // ---------------------------------------------------------------- outils

    /**
     * En attente (r < 0), entrée touchée, ou null si le setup est terminé ou périmé : stop ou TP1 déjà atteint,
     * y compris sans retest (le prix est parti sans revenir sur l'entrée).
     */
    private fun statusOf(c: List<QmCandle>, r: Int, sl: Double, tp1: Double, from: Int): QmStatus? {
        if (r < 0) {
            for (k in max(0, from) until c.size) {
                if (c[k].low <= sl || c[k].high >= tp1) return null
            }
            return QmStatus.PENDING
        }
        for (k in r until c.size) {
            if (c[k].low <= sl) return null
            if (c[k].high >= tp1) return null
        }
        return QmStatus.TRIGGERED
    }

    /**
     * La ligne passant par (i1, p1) et (i2, p2) passe près de l'entrée au moment du retest
     * (ou, si l'entrée n'est pas encore touchée, elle l'atteindra bientôt).
     * ascending = ligne montante (sous le prix) ; sinon ligne descendante ou plate (au-dessus du prix).
     */
    private fun lineHits(
        i1: Int, p1: Double, i2: Int, p2: Double,
        entry: Double, a: Double, trigger: Int, lastIdx: Int, ascending: Boolean
    ): Boolean {
        if (i2 <= i1) return false
        val slope = (p2 - p1) / (i2 - i1)
        if (ascending && slope <= 0.0) return false
        if (!ascending && slope > 0.0) return false
        val ref = if (trigger >= 0) trigger else lastIdx
        val diff = (p2 + slope * (ref - i2)) - entry
        return if (trigger >= 0) {
            abs(diff) <= a
        } else if (ascending) {
            diff >= -3.0 * a && diff <= a
        } else {
            diff >= -a && diff <= 3.0 * a
        }
    }

    private fun pivotIdx(c: List<QmCandle>, k: Int, highs: Boolean, from: Int): List<Int> {
        val out = ArrayList<Int>()
        for (i in max(k, from) until c.size - k) {
            var ok = true
            for (j in 1..k) {
                if (highs) {
                    if (c[i].high <= c[i - j].high || c[i].high < c[i + j].high) { ok = false; break }
                } else {
                    if (c[i].low >= c[i - j].low || c[i].low > c[i + j].low) { ok = false; break }
                }
            }
            if (ok) out.add(i)
        }
        return out
    }

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
}
