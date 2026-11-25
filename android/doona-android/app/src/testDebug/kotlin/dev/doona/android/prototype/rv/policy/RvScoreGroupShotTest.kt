package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Score group's page (sample, not approved), each whole, light, dark, fs115 and fs200: both networks verified with
 * challengers of each relation (`proto-rv-policy-group-score-*`), both still evaluating
 * (`proto-rv-policy-group-score-evaluating-*`) and no eligible candidate (`proto-rv-policy-group-score-empty-*`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvScoreGroupShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = CHOSEN)
    fun chosenLight() = shot(CHOSEN_NAME, dark = false) { Chosen() }

    @Test
    @Config(qualifiers = CHOSEN)
    fun chosenDark() = shot(CHOSEN_NAME, dark = true) { Chosen() }

    @Test
    @Config(qualifiers = CHOSEN_FS115)
    fun chosenFs115() = shot(CHOSEN_NAME, fontScale = RV_FS115) { Chosen() }

    @Test
    @Config(qualifiers = CHOSEN_FS200)
    fun chosenFs200() = shot(CHOSEN_NAME, fontScale = RV_FS200) { Chosen() }

    @Test
    @Config(qualifiers = EVALUATING)
    fun evaluatingLight() = shot(EVALUATING_NAME, dark = false) { Evaluating() }

    @Test
    @Config(qualifiers = EVALUATING)
    fun evaluatingDark() = shot(EVALUATING_NAME, dark = true) { Evaluating() }

    @Test
    @Config(qualifiers = EVALUATING_FS115)
    fun evaluatingFs115() = shot(EVALUATING_NAME, fontScale = RV_FS115) { Evaluating() }

    @Test
    @Config(qualifiers = EVALUATING_FS200)
    fun evaluatingFs200() = shot(EVALUATING_NAME, fontScale = RV_FS200) { Evaluating() }

    @Test
    @Config(qualifiers = EMPTY)
    fun emptyLight() = shot(EMPTY_NAME, dark = false) { Empty() }

    @Test
    @Config(qualifiers = EMPTY)
    fun emptyDark() = shot(EMPTY_NAME, dark = true) { Empty() }

    @Test
    @Config(qualifiers = EMPTY_FS115)
    fun emptyFs115() = shot(EMPTY_NAME, fontScale = RV_FS115) { Empty() }

    @Test
    @Config(qualifiers = EMPTY_FS200)
    fun emptyFs200() = shot(EMPTY_NAME, fontScale = RV_FS200) { Empty() }

    /** Each network's card says its state, how much it evaluated and how each challenger compares; no node gets a number. */
    @Test
    @Config(qualifiers = CHOSEN)
    fun chosenCardsSayStateCoverageAndRelations() {
        compose.rvContent(dark = false) { Chosen() }
        assertEquals(2, compose.onAllNodesWithText(SELECTED).fetchSemanticsNodes().size)
        compose.onNodeWithText("已比較 4/6").assertExists()
        compose.onNodeWithText("已比較 2/6").assertExists()
        compose.onNodeWithText(FASTER).assertExists()
        compose.onNodeWithText(SLOWER).assertExists()
        assertEquals(2, compose.onAllNodesWithText(SIMILAR).fetchSemanticsNodes().size)
        // The nodes card: the fastest chosen on TCP, the second on UDP, each line beginning with its network.
        compose.onNodeWithText("TCP · ", substring = true).assertExists()
        compose.onNodeWithText("UDP · ", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = EVALUATING)
    fun evaluatingSaysWhatEachNetworkWaitsFor() {
        compose.rvContent(dark = false) { Evaluating() }
        compose.onNodeWithText("評估中 · 測試中").assertExists()
        compose.onNodeWithText("評估中 · 測試流量已達上限").assertExists()
        compose.onNodeWithText("已比較 1/6").assertExists()
        compose.onNodeWithText("已比較 0/6").assertExists()
        // One node chosen on both networks.
        compose.onNodeWithText("TCP/UDP · ", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = EMPTY)
    fun emptyChoosesNothing() {
        compose.rvContent(dark = false) { Empty() }
        assertEquals(2, compose.onAllNodesWithText(NO_NODE).fetchSemanticsNodes().size)
        // Nothing is chosen and nothing is under way: no state under it.
        assertEquals(0, compose.onAllNodesWithText("評估中", substring = true).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithText("已比較 0/0").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("TCP · ", substring = true).fetchSemanticsNodes().size)
    }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { assertWhole() }, fontScale = fontScale, content = content)

    @Composable
    private fun Chosen() = Page(RvScoreSample.group, RvScoreSample.chosen)

    @Composable
    private fun Evaluating() = Page(RvScoreSample.group, RvScoreSample.evaluating)

    @Composable
    private fun Empty() = Page(RvScoreSample.emptyGroup, RvScoreSample.empty)

    @Composable
    private fun Page(group: RvPolicyGroup, score: ScoreGroup) = RvPolicyGroupPage(group, onBack = {}, onSelect = {}, onTest = {}, score = score)

    private companion object {
        const val CHOSEN_NAME = "policy-group-score"
        const val EVALUATING_NAME = "policy-group-score-evaluating"
        const val EMPTY_NAME = "policy-group-score-empty"

        /** Windows as tall as each page whole, rounded up to a whole dp (measured: 1475.5, 1486, 2522; 1229.5, 1239, 2136.5; 1099.5, 1102, 1502.5). */
        const val CHOSEN = "+h1476dp"
        const val CHOSEN_FS115 = "+h1486dp"
        const val CHOSEN_FS200 = "+h2522dp"
        const val EVALUATING = "+h1230dp"
        const val EVALUATING_FS115 = "+h1239dp"
        const val EVALUATING_FS200 = "+h2137dp"
        const val EMPTY = "+h1100dp"
        const val EMPTY_FS115 = "+h1102dp"
        const val EMPTY_FS200 = "+h1503dp"

        const val SELECTED = "已選定"
        const val FASTER = "較快"
        const val SLOWER = "較慢"
        const val SIMILAR = "相當"
        const val NO_NODE = "沒有可用的節點"
    }
}
