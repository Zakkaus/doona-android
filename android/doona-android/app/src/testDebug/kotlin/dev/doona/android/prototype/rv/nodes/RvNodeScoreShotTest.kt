package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.policy.RvScoreSample
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        // Each dot is held to the word after it (a no-break space), so a wrapped line never ends on a dot.
        assertEquals(listOf("Score 選用 TCP ·\u00A0VLESS ·\u00A0香港", "Score 選用 UDP ·\u00A0Trojan ·\u00A0香港"), marked)
        val points = compose.onAllNodesWithText("分", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .filter { it.text.replace(WORD_JOINER, "").matches(POINTS) }
        assertEquals(emptyList<Any>(), points)
    }

    /** B puts each node's own score under its latency, and 未測試 under an untested node's dash. */
    @Test
    @Config(qualifiers = PAGE_B)
    fun bScoresEveryTestedNode() {
        compose.rvContent(dark = false) { Page(RvScoreLook.Composite) }
        val first = "${chosen.composite()} 分"
        val second = "${other.composite()} 分"
        assertNotEquals(first, second)
        compose.onNode(row(chosen.name)).assert(shows(first)).assert(shows(second).not())
        compose.onNode(row(other.name)).assert(shows(second)).assert(shows(first).not())
        compose.onNode(row(untested.name)).assert(shows("未測試"))
    }

    @Test
    fun theCompositeWeighsLatencyStabilityAndTheLastTest() {
        val node = chosen
        assertEquals(PERFECT, node.copy(history = List(TESTS) { FAST }).composite())
        // Every test at 500 ms or more: no speed, and the rest still counts.
        assertEquals(STEADY_AND_RECENT, node.copy(history = List(TESTS) { SLOW }).composite())
        // The last test timed out: 最近 is lost and 穩定 falls to 7 of 8.
        assertEquals(LAST_FAILED, node.copy(history = List(TESTS - 1) { FAST } + null).composite())
        // An even, uneven history: the median is the middle two's mean, 250 ms, not the upper one, 450 ms.
        assertEquals(EVEN_HISTORY, node.copy(history = listOf(FAST_EVEN, SLOW_EVEN)).composite())
        assertNull(node.untested().composite())
    }

    /** The 節點 row of the node named [name]. */
    private fun row(name: String) = SemanticsMatcher("the row of $name") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag) == RvPart.ROW && node.texts().any { it == name }
    }

    /** A node one of whose texts is [text], without the word joiners RvText binds words with. */
    private fun shows(text: String) = SemanticsMatcher("shows $text") { node -> node.texts().any { it == text } }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text.replace(WORD_JOINER, "") }

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
        val other: RvNode = page.nodes.first { it.name == OTHER_NODE }

        const val UNTESTED_NODE = "Melbourne 02"
        const val OTHER_NODE = "Sydney 03"
        const val MARK = "Score 選用"
        const val WORD_JOINER = "\u2060"
        val POINTS = Regex("\\d+ 分")
        const val TESTS = 8
        const val FAST = 40
        const val SLOW = 600
        const val PERFECT = 100
        const val STEADY_AND_RECENT = 60
        const val LAST_FAILED = 75
        const val FAST_EVEN = 50
        const val SLOW_EVEN = 450

        /** 0.4 × 55.6 (a 250 ms median) + 0.4 × 0 (a 200 ms spread) + 0.2 × 100. */
        const val EVEN_HISTORY = 42

        /** Windows as tall as each page whole, rounded up to a whole dp. */
        const val PAGE_A = "+h2917dp"
        const val PAGE_A_FS115 = "+h2950dp"
        const val PAGE_A_FS200 = "+h4852dp"
        const val PAGE_B = "+h2881dp"
        const val PAGE_B_FS115 = "+h2899dp"
        const val PAGE_B_FS200 = "+h4844dp"
        const val DETAIL_A = "+h1208dp"
        const val DETAIL_A_FS115 = "+h1216dp"
        const val DETAIL_A_FS200 = "+h1390dp"
        const val DETAIL_B = "+h1266dp"
        const val DETAIL_B_FS115 = "+h1274dp"
        const val DETAIL_B_FS200 = "+h1396dp"
        const val UNTESTED = "+h1205dp"
    }
}
