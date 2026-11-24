package dev.doona.android.prototype.rv

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_CHART
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 流量詳情's chart (brief rv-gestures G5), on a clock the test drives: a scrub ticks once as it reaches the peak sample
 * and once at each end, and not again while it stays; a range chip cross-fades the chart to the new range, part way and
 * then at rest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvChartTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val taps = RvTaps()

    @Test
    fun aScrubTicksAtThePeakAndEachEndOnce() {
        traffic()
        val values = rvSampleActivity().downHistory
        val peak = values.indices.maxBy { values[it] }
        compose.onNodeWithTag(RV_CHART).performTouchInput {
            val y = centerY
            down(Offset(0f, y))
            // Half a sample a step, to the right edge and a little past the last sample, where it stays.
            repeat(STEPS) { moveTo(Offset((width - 1f) * (it + 1) / STEPS, y), delayMillis = FRAME_MS) }
            repeat(STAY) { moveBy(Offset(-1f, 0f), delayMillis = FRAME_MS) }
            up()
        }
        val marks = setOf(0, peak, values.lastIndex).size
        assertEquals("the peak at $peak and each end, once each", List(marks) { HapticFeedbackType.SegmentTick }, taps.kinds)
    }

    @Test
    fun aRangeChipCrossFadesTheChart() {
        traffic()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        val minute = chart()
        compose.onNodeWithText(rvText(R.string.rv_range_1h)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.FADE_MS / 2L)
        val half = chart()
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS * 2L)
        val hour = chart()
        compose.mainClock.advanceTimeByFrame()
        assertFalse("part way it is no longer the minute", half.sameAs(minute))
        assertFalse("nor yet the hour", half.sameAs(hour))
        assertFalse("the hour is not the minute", hour.sameAs(minute))
        assertTrue("then it rests", chart().sameAs(hour))
    }

    private fun traffic() = compose.setContent {
        CompositionLocalProvider(LocalHapticFeedback provides taps) {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}) }
        }
    }

    private fun chart(): Bitmap = compose.onNodeWithTag(RV_CHART).captureToImage().asAndroidBitmap()

    private companion object {
        const val STEPS = 120
        const val STAY = 4
        const val FRAME_MS = 16L
    }
}
