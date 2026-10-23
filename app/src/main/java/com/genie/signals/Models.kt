package com.genie.signals

import java.util.Locale

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

enum class Direction { BUY, SELL }

enum class Tf(val label: String, val seconds: Int) {
    M5("M5", 300),
    M15("M15", 900),
    H1("H1", 3600),
    H4("H4", 14400)
}

data class Market(
    val code: String,
    val name: String,
    val synthetic: Boolean,
    val decimals: Int
)

data class Signal(
    val market: Market,
    val tf: Tf,
    val dir: Direction,
    val setup: String,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val score: Int,
    val reasons: List<String>,
    val time: Long
) {
    fun fmt(v: Double): String =
        String.format(Locale.US, "%." + market.decimals + "f", v)
}

data class ScanResult(
    val signals: List<Signal>,
    val analysed: Int,
    val failed: Int
)

val ALL_MARKETS: List<Market> = listOf(
    Market("frxEURUSD", "EUR/USD", false, 5),
    Market("frxGBPUSD", "GBP/USD", false, 5),
    Market("frxUSDJPY", "USD/JPY", false, 3),
    Market("frxGBPJPY", "GBP/JPY", false, 3),
    Market("frxAUDUSD", "AUD/USD", false, 5),
    Market("frxUSDCAD", "USD/CAD", false, 5),
    Market("frxXAUUSD", "Or (XAU/USD)", false, 2),
    Market("R_10", "Volatility 10", true, 3),
    Market("R_25", "Volatility 25", true, 3),
    Market("R_50", "Volatility 50", true, 4),
    Market("R_75", "Volatility 75", true, 4),
    Market("R_100", "Volatility 100", true, 2),
    Market("BOOM500", "Boom 500", true, 4),
    Market("BOOM1000", "Boom 1000", true, 3),
    Market("CRASH500", "Crash 500", true, 4),
    Market("CRASH1000", "Crash 1000", true, 3),
    Market("stpRNG", "Step Index", true, 2)
)
