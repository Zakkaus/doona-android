package dev.doona.android.vpn

import dev.doona.android.engine.Traffic
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficRatesTest {
    @Test
    fun theFirstSampleOnlySetsTheBaseline() {
        val traffic = Traffic().next(previous = null, sample = sample(1_000, 5_000, atMs = 10_000))
        assertEquals(Traffic(upTotal = 1_000, downTotal = 5_000), traffic)
    }

    @Test
    fun ratesAreBytesPerSecondBetweenSamples() {
        val previous = sample(1_000, 5_000, atMs = 10_000)
        val traffic = Traffic().next(previous, sample(3_000, 9_000, atMs = 12_000))
        assertEquals(1_000L, traffic.upRate)
        assertEquals(2_000L, traffic.downRate)
        assertEquals(listOf(2_000L), traffic.downHistory)
    }

    @Test
    fun historiesKeepTheLastSixtyRates() {
        var traffic = Traffic()
        var previous = sample(0, 0, atMs = 0)
        for (second in 1..75L) {
            val sample = sample(second * 10, 0, atMs = second * 1_000)
            traffic = traffic.next(previous, sample)
            previous = sample
        }
        assertEquals(60, traffic.upHistory.size)
        assertEquals(10L, traffic.upHistory.last())
    }

    private fun sample(up: Long, down: Long, atMs: Long) = TrafficSample(TrafficDto(up, down, activeFlows = 0), atMs)
}
