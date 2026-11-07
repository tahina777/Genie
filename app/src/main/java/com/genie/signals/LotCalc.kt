package com.genie.signals

import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Calculateur de taille de position, pour un compte en dollars.
 * Lot précis pour EUR/USD, GBP/USD, AUD/USD, USD/JPY, USD/CAD et l'or.
 * Pour les autres marchés, on affiche le montant risqué et la distance du stop.
 */
object LotCalc {
    private const val PREFS = "genie"

    fun capital(ctx: Context): Double =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("capital", 0f).toDouble()

    fun riskPct(ctx: Context): Double =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("risk_pct", 1f).toDouble()

    fun save(ctx: Context, capital: Double, riskPct: Double) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("capital", capital.toFloat())
            .putFloat("risk_pct", riskPct.toFloat())
            .apply()
    }

    fun describe(s: Signal, capital: Double, riskPct: Double): String {
        if (capital <= 0.0 || riskPct <= 0.0) return ""
        val amount = capital * riskPct / 100.0
        val dist = abs(s.entry - s.sl)
        if (dist <= 0.0) return ""
        val head = "Risque " + String.format(Locale.US, "%.2f", amount) + " $"
        val lot: Double? = when (s.market.code) {
            "frxEURUSD", "frxGBPUSD", "frxAUDUSD" -> amount / ((dist / 0.0001) * 10.0)
            "frxUSDJPY" -> amount / ((dist / 0.01) * (1000.0 / s.entry))
            "frxUSDCAD" -> amount / ((dist / 0.0001) * (10.0 / s.entry))
            "frxXAUUSD" -> amount / (dist * 100.0)
            else -> null
        }
        if (lot == null) {
            return head + " · Stop à " + s.fmt(dist) + " du prix (vérifie le lot dans ta plateforme)"
        }
        val rounded = floor(lot * 100.0) / 100.0
        return if (rounded < 0.01) {
            head + " · Lot < 0.01 : stop trop large pour ce risque"
        } else {
            head + " · Lot ≈ " + String.format(Locale.US, "%.2f", rounded)
        }
    }
}
