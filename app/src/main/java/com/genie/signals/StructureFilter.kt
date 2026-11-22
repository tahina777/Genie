package com.genie.signals

import android.content.Context

/**
 * Suivre la structure : quand l'option est active, Genie n'envoie que les alertes dans le sens de la structure
 * de marché (D1 et H4 de même sens). Structure baissière : alertes de vente seulement. Haussière : achat seulement.
 * D1 et H4 en désaccord : aucune alerte pour ce marché. Tant que la structure n'a pas été calculée, tout passe.
 * La structure est mise à jour à chaque passage du service de zones (à chaque clôture d'heure).
 */
object StructureFilter {
    private const val UNKNOWN = 99

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences("genie", Context.MODE_PRIVATE).getBoolean("follow_structure", true)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("genie", Context.MODE_PRIVATE).edit().putBoolean("follow_structure", on).apply()
    }

    /** Mémorise le sens de la structure du marché : 1 haussier, -1 baissier, 0 pas de sens clair. */
    fun store(ctx: Context, m: Market, d1: StructureState, h4: StructureState) {
        val v = if (d1.trend != 0 && d1.trend == h4.trend) d1.trend else 0
        ctx.getSharedPreferences("genie", Context.MODE_PRIVATE).edit().putInt("sf_" + m.code, v).apply()
    }

    fun allows(ctx: Context, m: Market, dir: Direction): Boolean {
        if (!isOn(ctx)) return true
        val v = ctx.getSharedPreferences("genie", Context.MODE_PRIVATE).getInt("sf_" + m.code, UNKNOWN)
        if (v == UNKNOWN) return true
        if (v == 0) return false
        return (v > 0) == (dir == Direction.BUY)
    }
}
