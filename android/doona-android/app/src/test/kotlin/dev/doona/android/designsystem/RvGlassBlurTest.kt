package dev.doona.android.designsystem

import android.os.Build
import dev.doona.android.designsystem.rv.RvGlassSpot
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A glass surface keeps its blur between draws and makes a new one only when the radius changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class RvGlassBlurTest {
    @Test
    fun theBlurIsKeptWhileTheRadiusStays() {
        val spot = RvGlassSpot()
        val first = spot.blur(24f)
        assertSame(first, spot.blur(24f))
        assertNotSame(first, spot.blur(48f))
    }
}
