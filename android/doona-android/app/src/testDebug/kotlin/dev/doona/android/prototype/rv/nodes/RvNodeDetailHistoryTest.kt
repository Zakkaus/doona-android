package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.rvTestTimes
import dev.doona.android.prototype.rv.rvContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 延遲紀錄 on one open page while its node's history changes under it, as the engine's tests come in. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodeDetailHistoryTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val node = RvData.nodes.first { it.id == RV_NODES_DETAIL }
    private var probes by mutableIntStateOf(0)

    /** No test, then the first one, on the same page: the figure becomes that test's, not a pick from before it. */
    @Test
    fun theFirstTestOnTheOpenPageIsShown() {
        show()
        probes = 1
        compose.waitForIdle()
        assertFigure(node.history.first())
    }

    /** The latest test while none is picked, and again when the history shrinks under a pick it no longer has. */
    @Test
    fun aPickTheHistoryNoLongerHasFallsBackToTheLatest() {
        probes = node.history.size
        show()
        assertFigure(node.history.last())
        probes = 1
        compose.waitForIdle()
        assertFigure(node.history.first())
    }

    private fun show() {
        compose.rvContent(dark = false) {
            RvNodeDetail(node.copy(history = node.history.take(probes)), current = false, rvTestTimes().take(probes), onBack = {}, onUse = {}, onTest = {})
        }
        compose.waitForIdle()
    }

    private fun assertFigure(ms: Int?) {
        val figure = requireNotNull(ms).toString()
        assertTrue("the figure shows $figure", compose.onAllNodes(hasText(figure, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
