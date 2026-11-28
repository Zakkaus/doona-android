package dev.doona.android.designsystem.rv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet's let-go rule ([rvSheetAway]) and the speed it springs back with ([rvSheetReturn]). */
class RvSheetFlingTest {
    @Test
    fun aFlingDownDismissesFromAnywhere() {
        assertTrue(rvSheetAway(drag = 10f, height = HEIGHT, velocity = FLING + 1f, fling = FLING))
        assertFalse(rvSheetAway(drag = 10f, height = HEIGHT, velocity = FLING - 1f, fling = FLING))
    }

    @Test
    fun aDragPastAThirdDismissesUnlessFlungBackUp() {
        val past = HEIGHT * RvMotion.SHEET_DISMISS_SHARE + 1f
        assertTrue(rvSheetAway(past, HEIGHT, velocity = 0f, fling = FLING))
        assertTrue("a slow move up still dismisses", rvSheetAway(past, HEIGHT, velocity = -FLING + 1f, fling = FLING))
        assertFalse("a flick back up keeps it", rvSheetAway(past, HEIGHT, velocity = -FLING - 1f, fling = FLING))
        assertFalse("short of a third it stays", rvSheetAway(HEIGHT * RvMotion.SHEET_DISMISS_SHARE - 1f, HEIGHT, velocity = 0f, fling = FLING))
    }

    @Test
    fun aSheetAtItsPlaceTakesNoSpeedFromAFlickUp() {
        assertEquals("at rest a flick up must not lift the card", 0f, rvSheetReturn(drag = 0f, velocity = -3_000f), 0f)
    }

    @Test
    fun aSheetBelowItsPlaceSpringsBackAtTheFingersSpeed() {
        assertEquals(-500f, rvSheetReturn(drag = 100f, velocity = -500f), 0f)
        assertEquals(300f, rvSheetReturn(drag = 100f, velocity = 300f), 0f)
    }

    private companion object {
        const val HEIGHT = 600f
        const val FLING = 2_000f
    }
}
