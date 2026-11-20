package com.genie.signals

/** Structure de marché d'une unité de temps : trend = 1 haussier, -1 baissier, 0 neutre. */
class StructureState(
    val tfLabel: String,
    val trend: Int,
    val detail: String
)

/**
 * Structure de marché par sommets et creux (fractals de 3 bougies de chaque côté), sur bougies clôturées.
 *  - Haussière : dernier sommet > sommet précédent ET dernier creux > creux précédent (HH + HL).
 *  - Baissière : dernier sommet < sommet précédent ET dernier creux < creux précédent (LH + LL).
 *  - Sinon neutre (structure mixte). Si le prix clôture au-delà du dernier creux (haussier) ou du dernier sommet
 *    (baissier), la structure est signalée comme cassée (CHoCH) et passe en neutre.
 * Affichage seulement : ne change ni les signaux ni les alertes.
 */
object MarketStructure {

    fun analyze(tfLabel: String, raw: List<Candle>): StructureState {
        if (raw.size < 30) return StructureState(tfLabel, 0, "pas assez de données")
        val c = raw.dropLast(1) // la dernière bougie est encore en formation
        val k = 3
        val hi = ArrayList<Int>()
        val lo = ArrayList<Int>()
        for (i in k until c.size - k) {
            var h = true
            var l = true
            for (j in 1..k) {
                if (c[i].high <= c[i - j].high || c[i].high < c[i + j].high) h = false
                if (c[i].low >= c[i - j].low || c[i].low > c[i + j].low) l = false
            }
            if (h) hi.add(i)
            if (l) lo.add(i)
        }
        if (hi.size < 2 || lo.size < 2) return StructureState(tfLabel, 0, "structure pas claire")
        val h2 = c[hi[hi.size - 1]].high
        val h1 = c[hi[hi.size - 2]].high
        val l2 = c[lo[lo.size - 1]].low
        val l1 = c[lo[lo.size - 2]].low
        val last = c[c.size - 1].close

        if (h2 > h1 && l2 > l1) {
            return if (last < l2) StructureState(tfLabel, 0, "CHoCH : creux cassé après HH+HL")
            else StructureState(tfLabel, 1, "haussier (HH + HL)")
        }
        if (h2 < h1 && l2 < l1) {
            return if (last > h2) StructureState(tfLabel, 0, "CHoCH : sommet cassé après LH+LL")
            else StructureState(tfLabel, -1, "baissier (LH + LL)")
        }
        return StructureState(tfLabel, 0, "mixte (range ou transition)")
    }

    /** 1 si tout est haussier, -1 si tout est baissier, 0 sinon. */
    fun alignment(states: List<StructureState>): Int {
        if (states.isEmpty()) return 0
        if (states.all { it.trend == 1 }) return 1
        if (states.all { it.trend == -1 }) return -1
        return 0
    }
}
