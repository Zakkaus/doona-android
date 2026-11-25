package dev.doona.android.prototype.rv

import dev.doona.android.designsystem.rv.rvKeepRuns
import dev.doona.android.designsystem.rv.rvKeepTail
import org.junit.Assert.assertEquals
import org.junit.Test

/** The word joiner and the no-break space, as the helpers write them. */
private const val J = "⁠"
private const val NB = " "

class RvLineBreaksTest {
    @Test
    fun aRunOfCjkIsJoinedAndNothingElseIs() {
        assertEquals("目${J}前${J}已${J}套${J}用", rvKeepRuns("目前已套用"))
        assertEquals("MAC 位${J}址", rvKeepRuns("MAC 位址"))
        assertEquals("第${NB}3${NB}行 · Linux", rvKeepRuns("第 3 行 · Linux"))
        assertEquals("共 8 MiB。", rvKeepRuns("共 8 MiB。"))
    }

    @Test
    fun aNumberStaysWithItsUnit() {
        assertEquals("36${NB}行", rvKeepRuns("36 行"))
        assertEquals("3${NB}項${J}已${J}略${J}過", rvKeepRuns("3 項已略過"))
        assertEquals("第${NB}9${NB}版", rvKeepRuns("第 9 版"))
        assertEquals("8 MiB", rvKeepRuns("8 MiB"))
    }

    @Test
    fun keepingRunsTwiceChangesNothingMore() {
        assertEquals(rvKeepRuns("無法比對程式名稱"), rvKeepRuns(rvKeepRuns("無法比對程式名稱")))
    }

    @Test
    fun theTailOfThreeLettersIsJoined() {
        assertEquals("引入的檔案在匯入範${J}圍${J}外", rvKeepTail("引入的檔案在匯入範圍外"))
    }

    @Test
    fun theTailTurnsItsSpacesIntoNoBreakSpaces() {
        assertEquals("第${NB}6${NB}版", rvKeepTail("第 6 版"))
        assertEquals("由 config.dae 引入 · 12${NB}行", rvKeepTail("由 config.dae 引入 · 12 行"))
    }

    @Test
    fun theTailReachesBackOverASeparatorWhenItsLastPartIsShort() {
        assertEquals("2026/11/19 08:24$NB·${NB}匯${J}入", rvKeepTail("2026/11/19 08:24 · 匯入"))
    }

    @Test
    fun aTextOfFewerLettersJoinsAllOfIt() {
        assertEquals("版", rvKeepTail("版"))
        assertEquals("a${NB}b", rvKeepTail("a b", 5))
        assertEquals("", rvKeepTail(""))
    }

    @Test
    fun theTailLeavesAJoinerThatIsAlreadyThere() {
        assertEquals("範${J}圍${J}外", rvKeepTail("範${J}圍外"))
    }
}
