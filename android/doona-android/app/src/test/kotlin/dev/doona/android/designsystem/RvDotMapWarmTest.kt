package dev.doona.android.designsystem

import androidx.compose.ui.geometry.Offset
import dev.doona.android.designsystem.rv.rvDotRows
import dev.doona.android.designsystem.rv.rvWarmDotMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The land mask is decoded by the warm-up, and the map reads the same from it. */
class RvDotMapWarmTest {
    @Test
    fun theWarmUpFinishesAndTheMapHasLand() {
        val warm = rvWarmDotMap()
        runBlocking { warm.join() }
        assertTrue(warm.isCompleted && !warm.isCancelled)
        val rows = rvDotRows(35f, 105f, Offset(200f, 300f), 8f, 800f)
        assertFalse("China's latitude has land to draw", rows.all { it.dots.isEmpty() })
    }
}
