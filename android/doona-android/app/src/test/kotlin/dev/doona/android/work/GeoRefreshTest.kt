package dev.doona.android.work

import dev.doona.android.data.settings.GeoUpdateInterval
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoRefreshTest {
    @Test
    fun skipsWhileDisconnectedOrOffAndUpdatesOnlyWhenRunningAndEnabled() = runTest {
        val interval = MutableStateFlow(GeoUpdateInterval.Weekly)
        val engine = FakeEngine()
        val refresh = GeoRefresh(interval) { engine }
        val initial = engine.configAssets().getOrThrow()
        assertEquals(GeoRefresh.Outcome.Skipped, refresh.run())
        assertEquals(initial, engine.configAssets().getOrThrow())

        engine.connect()
        interval.value = GeoUpdateInterval.Off
        assertEquals(GeoRefresh.Outcome.Skipped, refresh.run())
        assertEquals(initial, engine.configAssets().getOrThrow())

        interval.value = GeoUpdateInterval.Daily
        assertEquals(GeoRefresh.Outcome.Completed, refresh.run())
        assertTrue(engine.configAssets().getOrThrow().updateWaitingForNextStart)
    }
}
