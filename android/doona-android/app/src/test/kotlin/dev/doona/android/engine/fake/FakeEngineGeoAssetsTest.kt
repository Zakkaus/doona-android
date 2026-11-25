package dev.doona.android.engine.fake

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEngineGeoAssetsTest {
    @Test
    fun stagesOnlyThroughAConnectionAndActivatesOnTheNextStart() = runTest {
        val engine = FakeEngine()
        assertEquals(ErrorCode.FetchFailed, (engine.updateConfigAssets().exceptionOrNull() as EngineException).code)
        assertEquals("not_connected", engine.configAssets().getOrThrow().reason)

        engine.connect()
        assertEquals("checking", engine.updateConfigAssets().getOrThrow().lastResult)
        val pending = engine.configAssets().getOrThrow()
        assertEquals("builtin", pending.source)
        assertNull(pending.release)
        engine.connect()
        assertEquals(pending, engine.configAssets().getOrThrow())
        assertTrue(pending.updateWaitingForNextStart)
        assertEquals(ErrorCode.Busy, (engine.updateConfigAssets().exceptionOrNull() as EngineException).code)

        engine.disconnect()
        engine.connect()
        val active = engine.configAssets().getOrThrow()
        assertEquals("download", active.source)
        assertEquals("activated", active.lastResult)
        assertNotNull(active.release)
        assertFalse(active.updateWaitingForNextStart)
    }
}
