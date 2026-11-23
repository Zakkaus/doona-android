package dev.doona.android.prototype.rv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [rvKeepWords] needs the platform's ICU and its dictionary, so it runs under Robolectric, as the pages do. */
@RunWith(RobolectricTestRunner::class)
class RvKeepWordsTest {
    @Test
    fun aWordsLettersAreJoinedAndTwoWordsAreNot() {
        val kept = rvKeepWords("中國大陸網站直接連線")
        assertTrue(kept, kept.contains("直${J}接"))
        assertTrue(kept, kept.contains("連${J}線"))
        assertTrue(kept, kept.contains("站直"))
        assertEquals("中國大陸網站直接連線", kept.replace(J, ""))
    }

    @Test
    fun latinTextIsLeftAsItIs() {
        assertEquals("Telegram、Apple", rvKeepWords("Telegram、Apple"))
    }

    @Test
    fun theTailJoinsTheLastWordToTheWordBeforeItsFirstLetter() {
        // The tail's three letters, 接連線, reach into 直接, so 直接連線 goes to the last line whole.
        val line = rvKeepTail(rvKeepWords("中國大陸網站直接連線"))
        assertTrue(line, line.contains("直${J}接${J}連${J}線"))
        assertTrue(line, line.contains("站直"))
    }

    private companion object {
        const val J = "⁠"
    }
}
