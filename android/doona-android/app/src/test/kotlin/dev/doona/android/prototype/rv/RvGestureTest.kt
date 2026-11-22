package dev.doona.android.prototype.rv

import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The platform's gestures in the sample (brief rv-gestures): the stretch overscroll where Android has it and no glow
 * below. 活動's pull-down still wins at the top over the stretch: [RvPullTest] runs on an SDK that has it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvGestureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun belowAndroid12NothingGlows() = assertNull("no overscroll effect at all", overscroll())

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun fromAndroid12ScrollingStretches() = assertNotNull("the platform's stretch", overscroll())

    /** The overscroll a scrolling page under [RvTheme] gets. */
    private fun overscroll(): OverscrollFactory? {
        var factory: OverscrollFactory? = null
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { factory = LocalOverscrollFactory.current } }
        compose.waitForIdle()
        return factory
    }
}
