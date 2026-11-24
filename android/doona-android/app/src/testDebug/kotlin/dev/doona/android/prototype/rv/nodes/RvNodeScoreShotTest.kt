package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.policy.RvScoreSample
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Score on 節點 and a node's page, the owner's pair (samples, not approved, no pick), each whole in light, dark, fs115 and
 * fs200: A marks the nodes the 香港 Score group chose (`proto-rv-nodes-page-score-a-*`, `-node-detail-score-a-*`, the
 * node in use, chosen on TCP); B gives every node doona's 綜合評分 (`-nodes-page-score-b-*`, `-node-detail-score-b-*`), with
 * Melbourne 02 untested on the page and on its own page (`-node-detail-score-b-untested-*`, light and dark).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodeScoreShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = PAGE_A)
    fun pageALight() = shot("nodes-page-score-a", dark = false) { Page(marks) }

    @Test
    @Config(qualifiers = PAGE_A)
    fun pageADark() = shot("nodes-page-score-a", dark = true) { Page(marks) }

    @Test
    @Config(qualifiers = PAGE_A_FS115)
    fun pageAFs115() = shot("nodes-page-score-a", fontScale = RV_FS115) { Page(marks) }

    @Test
    @Config(qualifiers = PAGE_A_FS200)
    fun pageAFs200() = shot("nodes-page-score-a", fontScale = RV_FS200) { Page(marks) }

    @Test
    @Config(qualifiers = PAGE_B)
    fun pageBLight() = shot("nodes-page-score-b", dark = false) { Page(RvScoreLook.Composite) }

    @Test
    @Config(qualifiers = PAGE_B)
    fun pageBDark() = shot("nodes-page-score-b", dark = true) { Page(RvScoreLook.Composite) }

    @Test
    @Config(qualifiers = PAGE_B_FS115)
    fun pageBFs115() = shot("nodes-page-score-b", fontScale = RV_FS115) { Page(RvScoreLook.Composite) }

    @Test
    @Config(qualifiers = PAGE_B_FS200)
    fun pageBFs200() = shot("nodes-page-score-b", fontScale = RV_FS200) { Page(RvScoreLook.Composite) }

    @Test
    @Config(qualifiers = DETAIL_A)
    fun detailALight() = shot("node-detail-score-a", dark = false) { Detail(chosen, marks) }

    @Test
    @Config(qualifiers = DETAIL_A)
    fun detailADark() = shot("node-detail-score-a", dark = true) { Detail(chosen, marks) }

    @Test
    @Config(qualifiers = DETAIL_A_FS115)
    fun detailAFs115() = shot("node-detail-score-a", fontScale = RV_FS115) { Detail(chosen, marks) }

    @Test
    @Config(qualifiers = DETAIL_A_FS200)
    fun detailAFs200() = shot("node-detail-score-a", fontScale = RV_FS200) { Detail(chosen, marks) }

    @Test
    @Config(qualifiers = DETAIL_B)
    fun detailBLight() = shot("node-detail-score-b", dark = false) { Detail(chosen, RvScoreLook.Composite) }

    @Test
    @Config(qualifiers = DETAIL_B)
    fun detailBDark() = shot("node-detail-score-b", dark = true) { Detail(chosen, RvScoreLook.Composite) }

    @Test
    @Config(qualifiers = DETAIL_B_FS115)
    fun detailBFs115() = shot("node-detail-score-b", fontScale = RV_FS115) { Detail(chosen, RvScoreLook.Composite) }

    @Test
    @Config(qualifiers = DETAIL_B_FS200)
    fun detailBFs200() = shot("node-detail-score-b", fontScale = RV_FS200) { Detail(chosen, RvScoreLook.Composite) }

    @Test
    @Config(qualifiers = UNTESTED)
    fun untestedLight() = shot("node-detail-score-b-untested", dark = false) { Detail(untested, RvScoreLook.Composite) }

    @Test
    @Config(qualifiers = UNTESTED)
    fun untestedDark() = shot("node-detail-score-b-untested", dark = true) { Detail(untested, RvScoreLook.Composite) }

    /** A marks the TCP choice (the node in use) and the UDP choice, each with its network, and shows no number. */
    @Test
    @Config(qualifiers = PAGE_A)
    fun aMarksTheChosenNodesOnly() {
        compose.rvContent(dark = false) { Page(marks) }
        // RvText binds a line's words with word joiners; the words themselves are compared.
        val marked = compose.onAllNodesWithText("Score", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .map { it.text.replace(WORD_JOINER, "") }
            .filter { MARK in it }
        assertEquals(listOf("Score 選用 TCP · VLESS · 香港", "Score 選用 UDP · Trojan · 香港"), marked)
        val points = compose.onAllNodesWithText("分", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .filter { it.text.replace(WORD_JOINER, "").matches(POINTS) }
        assertEquals(emptyList<Any>(), points)
    }

    /** B puts each node's score under its latency, and 未測試 under an untested node's dash. */
    @Test
    @Config(qualifiers = PAGE_B)
    fun bScoresEveryTestedNode() {
        compose.rvContent(dark = false) { Page(RvScoreLook.Composite) }
        assert(compose.onAllNodesWithText("${chosen.composite()} 分").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("未測試").assertExists()
    }

    @Test
    fun theCompositeWeighsLatencyStabilityAndTheLastTest() {
        val node = chosen
        assertEquals(PERFECT, node.copy(history = List(TESTS) { FAST }).composite())
        // Every test at 500 ms or more: no speed, and the rest still counts.
        assertEquals(STEADY_AND_RECENT, node.copy(history = List(TESTS) { SLOW }).composite())
        // The last test timed out: 最近 is lost and 穩定 falls to 7 of 8.
        assertEquals(LAST_FAILED, node.copy(history = List(TESTS - 1) { FAST } + null).composite())
        assertNull(node.untested().composite())
    }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { assertWhole() }, fontScale = fontScale, content = content)

    @Composable
    private fun Page(score: RvScoreLook) = RvNodesPage(page, onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {}, score = score)

    @Composable
    private fun Detail(node: RvNode, score: RvScoreLook) =
        RvNodeDetail(node, current = node.id == page.currentId, page.testedAtEpochMs, onBack = {}, onUse = {}, onTest = {}, score = score)

    private companion object {
        val marks = RvScoreLook.Marks(listOf(RvScoreSample.chosen))
        private val sample = sampleNodesPage()

        /** The page with Melbourne 02 before any test, so B shows how an untested node reads. */
        val page = RvNodesPageState(sample.nodes.map { if (it.name == UNTESTED_NODE) it.untested() else it }, sample.currentId, sample.testedAtEpochMs)
        val chosen: RvNode = page.nodes.first { it.id == RvScoreSample.chosen.tcp.selectedId }
        val untested: RvNode = page.nodes.first { it.name == UNTESTED_NODE }

        const val UNTESTED_NODE = "Melbourne 02"
        const val MARK = "Score 選用"
        const val WORD_JOINER = "\u2060"
        val POINTS = Regex("\\d+ 分")
        const val TESTS = 8
        const val FAST = 40
        const val SLOW = 600
        const val PERFECT = 100
        const val STEADY_AND_RECENT = 60
        const val LAST_FAILED = 75

        /** Windows as tall as each page whole, rounded up to a whole dp. */
        const val PAGE_A = "+h2917dp"
        const val PAGE_A_FS115 = "+h2950dp"
        const val PAGE_A_FS200 = "+h4852dp"
        const val PAGE_B = "+h2881dp"
        const val PAGE_B_FS115 = "+h2899dp"
        const val PAGE_B_FS200 = "+h4185dp"
        const val DETAIL_A = "+h1208dp"
        const val DETAIL_A_FS115 = "+h1216dp"
        const val DETAIL_A_FS200 = "+h1390dp"
        const val DETAIL_B = "+h1266dp"
        const val DETAIL_B_FS115 = "+h1274dp"
        const val DETAIL_B_FS200 = "+h1396dp"
        const val UNTESTED = "+h1205dp"
    }
}
