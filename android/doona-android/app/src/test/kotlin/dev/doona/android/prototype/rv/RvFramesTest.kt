package dev.doona.android.prototype.rv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RvFrames counts a frame as jank when it missed its deadline, as Perfetto's App Deadline Missed does. */
class RvFramesTest {
    @Test
    fun aFrameIsJankOnlyPastItsDeadline() {
        assertTrue("finished past its deadline", RvFrames.missedDeadline(total = 17_000_000, deadline = 16_600_000))
        assertFalse("on time, however long it waited for the display", RvFrames.missedDeadline(total = 16_600_000, deadline = 16_600_000))
        assertFalse("no deadline known (before API 31)", RvFrames.missedDeadline(total = 40_000_000, deadline = 0))
    }
}
