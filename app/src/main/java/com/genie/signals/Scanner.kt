package com.genie.signals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

object Scanner {

    suspend fun scan(markets: List<Market>, tf: Tf): ScanResult = coroutineScope {
        val failed = AtomicInteger(0)
        val gate = Semaphore(4)
        val results = markets.map { m ->
            async {
                gate.withPermit {
                    try {
                        val candles = Deriv.candles(m.code, tf.seconds, 300)
                        Engine.analyse(m, tf, candles)
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
