package com.genie.signals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

object Scanner {

    /** Timeframe supérieur utilisé pour confirmer la tendance et les niveaux. */
    private fun higherSeconds(s: Int): Int? = when {
        s <= 300 -> 3600      // M5 (et moins) -> H1
        s <= 900 -> 14400     // M15 -> H4
        s <= 3600 -> 14400    // H1 -> H4
        s <= 14400 -> 86400   // H4 -> D1
        else -> null
    }

    suspend fun scan(markets: List<Market>, tf: Tf): ScanResult = coroutineScope {
        val failed = AtomicInteger(0)
        val gate = Semaphore(4)
        val results = markets.map { m ->
            async {
                gate.withPermit {
                    try {
                        val candles = Deriv.candles(m.code, tf.seconds, 300)
                        val hs = higherSeconds(tf.seconds)
                        val htf = if (hs == null) null else try {
                            Deriv.candles(m.code, hs, 300)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        Engine.analyse(m, tf, candles, htf)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                        null
                    }
                }
            }
        }.awaitAll()
        val signals = results.filterNotNull().sortedByDescending { it.score }
        ScanResult(signals, markets.size - failed.get(), failed.get())
    }
}
