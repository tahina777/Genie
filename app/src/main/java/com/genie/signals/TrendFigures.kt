package com.genie.signals

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Un segment du graphique, en indices de bougies et en prix. `guide` = trait fin pointillé (zigzag de la figure). */
class FigLine(val i1: Int, val p1: Double, val i2: Int, val p2: Double, val guide: Boolean)

/** Une figure chartiste détectée : nom, sens attendu (null = neutre), état et lignes à tracer. */
class TrendFigure(
    val name: String,
    val buy: Boolean?,
    val status: String,
    val lines: List<FigLine>,
    val score: Double
)

private class Pv(val i: Int, val p: Double)

private class Ln(val i0: Int, val p0: Double, val slope: Double) {
    fun at(i: Int): Double = p0 + slope * (i - i0)
}

/**
 * Détection des figures chartistes par lignes de tendance : triangles, biseaux, rectangle, drapeaux, fanions,
 * épaule-tête-épaule et triple sommet / creux. Les formes arrondies (soucoupe, tasse, diamant...) ne sont pas détectées.
 * Affichage seulement : ça ne change pas le score des signaux.
 */
object TrendFigures {

    fun build(c: List<Candle>): List<TrendFigure> {
        val n = c.size
        if (n < 60) return emptyList()
        val atr = Ind.atr(c)
        if (atr <= 0.0) return emptyList()

        val out = ArrayList<TrendFigure>()
        fun add(f: TrendFigure?) {
            if (f == null) return
            val same = out.firstOrNull { it.name == f.name }
            if (same == null) {
                out.add(f)
            } else if (f.score > same.score) {
                out.remove(same)
                out.add(f)
            }
        }
        // deux échelles de sommets/creux : petits mouvements (3 bougies) et grands mouvements (5 bougies)
        for (k in intArrayOf(3, 5)) {
            val hs = pivots(c, k, true)
            val ls = pivots(c, k, false)
            add(lineFigure(c, atr, hs, ls))
            add(headShoulders(c, atr, hs, true))
            add(headShoulders(c, atr, ls, false))
            add(triple(c, atr, hs, true))
            add(triple(c, atr, ls, false))
        }
        // une seule figure à la fois : la plus récente et la mieux formée
        return out.sortedByDescending { it.score }.take(1)
    }

    private fun pivots(c: List<Candle>, k: Int, highs: Boolean): List<Pv> {
        val out = ArrayList<Pv>()
        for (i in k until c.size - k) {
            var ok = true
            for (j in 1..k) {
                if (highs) {
                    if (c[i].high <= c[i - j].high || c[i].high < c[i + j].high) { ok = false; break }
                } else {
                    if (c[i].low >= c[i - j].low || c[i].low > c[i + j].low) { ok = false; break }
                }
            }
            if (ok) out.add(Pv(i, if (highs) c[i].high else c[i].low))
        }
        return out
    }

    /** Droite passant par le premier et le dernier point ; les points du milieu doivent rester proches. */
    private fun fit(pts: List<Pv>, atr: Double): Ln? {
        val a = pts.first()
        val b = pts.last()
        if (b.i <= a.i) return null
        val ln = Ln(a.i, a.p, (b.p - a.p) / (b.i - a.i))
        for (q in pts) {
            if (abs(q.p - ln.at(q.i)) > 0.35 * atr) return null
        }
        return ln
    }

    /** Creux (si `top`) ou sommet entre deux indices : sert à tracer la ligne de cou. */
    private fun between(c: List<Candle>, from: Int, to: Int, top: Boolean): Pv {
        var bi = from
        for (j in from..to) {
            if (top) {
                if (c[j].low < c[bi].low) bi = j
            } else {
                if (c[j].high > c[bi].high) bi = j
            }
        }
        return Pv(bi, if (top) c[bi].low else c[bi].high)
    }

    // ---------- triangles, biseaux, rectangle, drapeaux, fanions ----------

    private fun lineFigure(c: List<Candle>, atr: Double, hs: List<Pv>, ls: List<Pv>): TrendFigure? {
        val n = c.size
        for (nh in intArrayOf(3, 2)) {
            for (nl in intArrayOf(3, 2)) {
                if (hs.size < nh || ls.size < nl) continue
                val h = hs.takeLast(nh)
                val l = ls.takeLast(nl)
                val u = fit(h, atr) ?: continue
                val lo = fit(l, atr) ?: continue

                val start = min(h.first().i, l.first().i)
                val endP = max(h.last().i, l.last().i)
                val span = endP - start
                if (span < 8 || span > 80) continue
                if (n - 1 - endP > 25) continue

                val gs = u.at(start) - lo.at(start)
                val ge = u.at(endP) - lo.at(endP)
                if (gs < atr || ge <= 0.0) continue

                val du = u.slope * span
                val dl = lo.slope * span
                val tol = 0.5 * atr
                val flatU = abs(du) <= tol
                val flatL = abs(dl) <= tol
                val conv = ge < 0.75 * gs
                val broad = ge > 1.3 * gs

                // mouvement qui précède la figure
                val m = min(start, max(10, span))
                val mv = if (m > 0) c[start].close - c[start - m].close else 0.0
                val impUp = mv >= 3 * atr
                val impDn = mv <= -3 * atr
                val prior: Boolean? = if (mv >= atr) true else if (mv <= -atr) false else null

                var name: String? = null
                var buy: Boolean? = null
                if (span <= 30 && impUp && !conv && !broad && du < -0.3 * atr && dl < -0.3 * atr) {
                    name = "Drapeau haussier"; buy = true
                } else if (span <= 30 && impDn && !conv && !broad && du > 0.3 * atr && dl > 0.3 * atr) {
                    name = "Drapeau baissier"; buy = false
                } else if (span <= 30 && conv && (impUp || impDn) && du <= tol && dl >= -tol) {
                    name = if (impUp) "Fanion haussier" else "Fanion baissier"; buy = impUp
                } else if (flatU && flatL && !conv && !broad && gs >= 1.5 * atr) {
                    name = "Rectangle"; buy = prior
                } else if (flatU && dl > tol && conv) {
                    name = "Triangle ascendant"; buy = true
                } else if (flatL && du < -tol && conv) {
                    name = "Triangle descendant"; buy = false
                } else if (du < -tol && dl > tol && conv) {
                    name = "Triangle symétrique"; buy = prior
                } else if (du > tol && dl > tol && conv) {
                    name = "Biseau montant"; buy = false
                } else if (du < -tol && dl < -tol && conv) {
                    name = "Biseau descendant"; buy = true
                } else if (du > tol && dl < -tol && broad) {
                    name = "Élargissement"; buy = null
                }
                if (name == null) continue

                val uL = u.at(n - 1)
                val lL = lo.at(n - 1)
                if (uL <= lL) continue // sommet du triangle dépassé : figure terminée
                val cl = c[n - 1].close
                if (cl > uL + 1.5 * atr || cl < lL - 1.5 * atr) continue // cassure ancienne
                val status = if (cl > uL + 0.1 * atr) "cassure haussière"
                else if (cl < lL - 0.1 * atr) "cassure baissière"
                else "en formation"

                val i1 = h.first().i
                val i2 = l.first().i
                return TrendFigure(
                    name, buy, status,
                    listOf(
                        FigLine(i1, u.at(i1), n - 1, uL, false),
                        FigLine(i2, lo.at(i2), n - 1, lL, false)
                    ),
                    10.0 * (nh + nl) - 0.5 * (n - 1 - endP) - 0.05 * span
                )
            }
        }
        return null
    }

    // ---------- épaule-tête-épaule ----------

    private fun headShoulders(c: List<Candle>, atr: Double, pv: List<Pv>, top: Boolean): TrendFigure? {
        val n = c.size
        if (pv.size < 3) return null
        val a = pv[pv.size - 3]
        val b = pv[pv.size - 2]
        val d = pv[pv.size - 1]
        val s = if (top) 1.0 else -1.0
        val shoulder = if (top) max(a.p, d.p) else min(a.p, d.p)
        if ((b.p - shoulder) * s < 0.6 * atr) return null // la tête doit dépasser les épaules
        if (abs(a.p - d.p) > 0.7 * atr) return null // épaules à peu près au même niveau
        if (d.i - a.i > 120 || n - 1 - d.i > 30) return null

        val n1 = between(c, a.i, b.i, top)
        val n2 = between(c, b.i, d.i, top)
        if (n2.i <= n1.i) return null
        val slope = (n2.p - n1.p) / (n2.i - n1.i)
        val neckLast = n2.p + slope * (n - 1 - n2.i)
        val cl = c[n - 1].close
        if ((cl - b.p) * s > 0) return null // prix au-delà de la tête : figure invalidée
        if ((cl - neckLast) * s < -1.5 * atr) return null // cassure ancienne
        val broken = (cl - neckLast) * s < -0.1 * atr
        val status = if (broken) (if (top) "cassure baissière" else "cassure haussière") else "en formation"
        return TrendFigure(
            if (top) "Épaule-tête-épaule" else "Épaule-tête-épaule inversée",
            !top, status,
            listOf(
                FigLine(n1.i, n1.p, n - 1, neckLast, false),
                FigLine(a.i, a.p, n1.i, n1.p, true),
                FigLine(n1.i, n1.p, b.i, b.p, true),
                FigLine(b.i, b.p, n2.i, n2.p, true),
                FigLine(n2.i, n2.p, d.i, d.p, true)
            ),
            40.0 - 0.5 * (n - 1 - d.i) - 0.05 * (d.i - a.i)
        )
    }

    // ---------- triple sommet / triple creux ----------

    private fun triple(c: List<Candle>, atr: Double, pv: List<Pv>, top: Boolean): TrendFigure? {
        val n = c.size
        if (pv.size < 3) return null
        val a = pv[pv.size - 3]
        val b = pv[pv.size - 2]
        val d = pv[pv.size - 1]
        val hiP = max(a.p, max(b.p, d.p))
        val loP = min(a.p, min(b.p, d.p))
        if (hiP - loP > 0.5 * atr) return null
        if (d.i - a.i > 120 || d.i - a.i < 12 || n - 1 - d.i > 30) return null

        val mean = (a.p + b.p + d.p) / 3.0
        val n1 = between(c, a.i, b.i, top)
        val n2 = between(c, b.i, d.i, top)
        val neck = if (top) min(n1.p, n2.p) else max(n1.p, n2.p)
        if (abs(mean - neck) < 0.8 * atr) return null // creux trop peu marqués

        val s = if (top) 1.0 else -1.0
        val cl = c[n - 1].close
        if ((cl - mean) * s > atr) return null // prix au-delà de la résistance / du support : invalidé
        if ((cl - neck) * s < -1.5 * atr) return null // cassure ancienne
        val broken = (cl - neck) * s < -0.1 * atr
        val status = if (broken) (if (top) "cassure baissière" else "cassure haussière") else "en formation"
        val ni = min(n1.i, n2.i)
        return TrendFigure(
            if (top) "Triple sommet" else "Triple creux",
            !top, status,
            listOf(
                FigLine(a.i, mean, n - 1, mean, false),
                FigLine(ni, neck, n - 1, neck, false),
                FigLine(a.i, a.p, n1.i, n1.p, true),
                FigLine(n1.i, n1.p, b.i, b.p, true),
                FigLine(b.i, b.p, n2.i, n2.p, true),
                FigLine(n2.i, n2.p, d.i, d.p, true)
            ),
            40.0 - 0.5 * (n - 1 - d.i) - 0.05 * (d.i - a.i)
        )
    }
}
