package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.nodes.RvNodeDetail
import dev.doona.android.feature.nodes.RvNodesPage
import dev.doona.android.feature.nodes.RvScoreMarks
import dev.doona.android.feature.nodes.ui.matches
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.policy.RvScoreSample
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
 * Score on 節點 and a node's page (samples, not approved), each whole in light, dark, fs115 and fs200: the nodes the 香港
 * Score group chose carry its mark. Where TCP and UDP chose different nodes, each mark names its network
 * (`proto-rv-nodes-page-score-a-*`, `-node-detail-score-a-*`, the node in use, chosen on TCP); where both chose the node
 * in use, its mark on 節點 is 「Score」 alone (`-nodes-page-score-both-*`) and on its page 「Score TCP/UDP」
 * (`-node-detail-score-both-*`).
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
    @Config(qualifiers = PAGE_BOTH)
    fun pageBothLight() = shot("nodes-page-score-both", dark = false) { Page(both) }

    @Test
    @Config(qualifiers = PAGE_BOTH)
    fun pageBothDark() = shot("nodes-page-score-both", dark = true) { Page(both) }

    @Test
    @Config(qualifiers = PAGE_BOTH_FS115)
    fun pageBothFs115() = shot("nodes-page-score-both", fontScale = RV_FS115) { Page(both) }

    @Test
    @Config(qualifiers = PAGE_BOTH_FS200)
    fun pageBothFs200() = shot("nodes-page-score-both", fontScale = RV_FS200) { Page(both) }

    @Test
    @Config(qualifiers = DETAIL_A)
    fun detailBothLight() = shot("node-detail-score-both", dark = false) { Detail(chosen, both) }

    @Test
    @Config(qualifiers = DETAIL_A)
    fun detailBothDark() = shot("node-detail-score-both", dark = true) { Detail(chosen, both) }

    @Test
    @Config(qualifiers = DETAIL_A_FS115)
    fun detailBothFs115() = shot("node-detail-score-both", fontScale = RV_FS115) { Detail(chosen, both) }

    @Test
    @Config(qualifiers = DETAIL_A_FS200)
    fun detailBothFs200() = shot("node-detail-score-both", fontScale = RV_FS200) { Detail(chosen, both) }

    /** A node both networks chose reads 「Score」 with no network on 節點; no other node is marked. */
    @Test
    @Config(qualifiers = PAGE_BOTH)
    fun bothNetworksMarkScoreAlone() {
        compose.rvContent(dark = false) { Page(both) }
        assertEquals(listOf(listOf("Score · VLESS · 香港")), markedLinesNow())
    }

    @Test
    @Config(qualifiers = DETAIL_A)
    fun bothNetworksMarkTheDetailWithBoth() {
        compose.rvContent(dark = false) { Detail(chosen, both) }
        val marks = compose.onAllNodesWithText(MARK, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .map { it.text.replace(WORD_JOINER, "") }
        assertEquals(listOf("Score TCP/UDP"), marks)
    }

    /** A marks the TCP choice (the node in use) and the UDP choice, each with its network, and shows no number. */
    @Test
    @Config(qualifiers = PAGE_A)
    fun aMarksTheChosenNodesOnly() {
        compose.rvContent(dark = false) { Page(marks) }
        // RvText binds a line's words with word joiners; the words themselves are compared.
        val marked = compose.onAllNodesWithText("Score", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .map { it.text.replace(WORD_JOINER, "") }
            .filter { it.startsWith(MARK) }
        assertEquals(listOf("Score TCP · VLESS · 香港", "Score UDP · Trojan · 香港"), marked)
        val points = compose.onAllNodesWithText("分", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .filter { it.text.replace(WORD_JOINER, "").matches(POINTS) }
        assertEquals(emptyList<Any>(), points)
    }

    /** A marked row's line fits one line at the default size, as an unmarked row's does. */
    @Test
    @Config(qualifiers = PAGE_A)
    fun aMarkKeepsTheLineOnOneLine() = assertEquals(listOf(listOf("Score TCP · VLESS · 香港"), listOf("Score UDP · Trojan · 香港")), markedLines(1f))

    /** At fs115 the line is too long for one line and breaks after the mark's dot: the protocol begins the second line. */
    @Test
    @Config(qualifiers = PAGE_A_FS115)
    fun aMarkBreaksAfterTheDotAtFs115() =
        assertEquals(listOf(listOf("Score TCP ·", "VLESS · 香港"), listOf("Score UDP ·", "Trojan · 香港")), markedLines(RV_FS115))

    /** At fs200 too the dot ends the first line and the protocol begins the next. */
    @Test
    @Config(qualifiers = PAGE_A_FS200)
    fun aMarkBreaksAfterTheDotAtFs200() = markedLines(RV_FS200).forEach { lines ->
        assertEquals(lines.toString(), true, lines.size > 1 && lines.dropLast(1).all { it.endsWith(" ·") } && lines[1].first().isLetter())
    }

    /** The lines each marked row's line is laid out in, without word joiners. */
    private fun markedLines(fontScale: Float): List<List<String>> {
        compose.rvContent(dark = false, fontScale = fontScale) { Page(marks) }
        return markedLinesNow()
    }

    private fun markedLinesNow(): List<List<String>> =
        compose.onAllNodesWithText(MARK, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { SemanticsActions.GetTextLayoutResult in it.config }
            .map { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val layout = layouts.single()
                val text = layout.layoutInput.text.text
                (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)).replace(WORD_JOINER, "").trim() }
            }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { assertWhole() }, fontScale = fontScale, content = content)

    @Composable
    private fun Page(score: RvScoreMarks) = RvNodesPage(page, onBack = {}, onOpen = {}, onAllNodes = {}, score = score)

    @Composable
    private fun Detail(node: RvNode, score: RvScoreMarks) =
        RvNodeDetail(node, current = node.id == page.currentId, page.testedAtEpochMs, onBack = {}, onUse = {}, onTest = {}, score = score)

    private companion object {
        val marks = RvScoreMarks(listOf(RvScoreSample.chosen))
        val both = RvScoreMarks(listOf(RvScoreSample.agreed))
        val page = sampleNodesPage()
        val chosen: RvNode = page.nodes.first { it.id == RvScoreSample.chosen.tcp.selectedId }

        const val MARK = "Score "
        const val WORD_JOINER = "\u2060"
        val POINTS = Regex("\\d+ 分")

        /** Windows as tall as each page whole, rounded up to a whole dp. */
        const val PAGE_A = "+h2881dp"
        const val PAGE_A_FS115 = "+h2949dp"
        const val PAGE_A_FS200 = "+h4925dp"
        const val DETAIL_A = "+h1208dp"
        const val DETAIL_A_FS115 = "+h1216dp"
        const val DETAIL_A_FS200 = "+h1389dp"
        const val PAGE_BOTH = "+h2881dp"
        const val PAGE_BOTH_FS115 = "+h2899dp"
        const val PAGE_BOTH_FS200 = "+h4845dp"
    }
}
