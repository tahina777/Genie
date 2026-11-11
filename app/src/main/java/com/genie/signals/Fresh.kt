package com.genie.signals

import kotlin.math.abs

/**
 * Contrôle de fraîcheur : un signal est périmé si le prix actuel a déjà touché TP1 ou le stop,
 * ou a déjà trop avancé dans le sens du trade (entrée dépassée).
 * `now` est la bougie en cours de formation, la dernière renvoyée par Deriv.
 */
object Fresh {
    private const val MAX_MOVE_R = 0.5

    fun stale(s: Signal, now: Candle): Boolean {
        val buy = s.dir == Direction.BUY
        val risk = abs(s.entry - s.sl)
        if (risk <= 0.0) return false
        val hitTp = if (buy) now.high >= s.tp1 else now.low <= s.tp1
        val hitSl = if (buy) now.low <= s.sl else now.high >= s.sl
        if (hitTp || hitSl) return true
        val sign = if (buy) 1.0 else -1.0
        val moved = (now.close - s.entry) * sign / risk
        return moved > MAX_MOVE_R
    }
}
