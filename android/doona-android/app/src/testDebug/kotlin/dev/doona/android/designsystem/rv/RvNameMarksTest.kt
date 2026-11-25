package dev.doona.android.designsystem.rv

import dev.doona.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The emoji of a name become the library's marks. */
class RvNameMarksTest {
    @Test
    fun aFlagBecomesTheFlagAndLeavesTheName() {
        val shown = rvShownName("🇭🇰 香港 01")
        assertEquals(RvNameMark.Flag("HK"), shown.mark)
        assertEquals("香港 01", shown.text)
    }

    @Test
    fun anUnrecognisedEmojiStaysInTheName() {
        val shown = rvShownName("🇺🇸 US 02 ⚡")
        assertEquals(RvNameMark.Flag("US"), shown.mark)
        assertEquals("US 02 ⚡", shown.text)
    }

    @Test
    fun aBrandBecomesItsMark() {
        val shown = rvShownName("🍎 Apple")
        assertEquals(R.string.rv_name_apple, (shown.mark as RvNameMark.Icon).name)
        assertEquals("Apple", shown.text)
    }

    @Test
    fun aNameOfOnlyAnEmojiIsLeftEmpty() {
        val shown = rvShownName("🇯🇵")
        assertEquals(RvNameMark.Flag("JP"), shown.mark)
        assertEquals("", shown.text)
    }

    @Test
    fun aNameWithOnlyAnUnknownEmojiChangesNothing() {
        val shown = rvShownName("🦄 Unicorn 03")
        assertNull(shown.mark)
        assertEquals("🦄 Unicorn 03", shown.text)
    }

    @Test
    fun aVariationSelectorGoesWithItsEmoji() {
        assertEquals("", rvShownName("🍎️").text)
    }

    @Test
    fun theFirstMarkWinsAndTheOthersLeaveToo() {
        val shown = rvShownName("🇺🇸 🚀 Fast")
        assertEquals(RvNameMark.Flag("US"), shown.mark)
        assertEquals("Fast", shown.text)
    }
}
