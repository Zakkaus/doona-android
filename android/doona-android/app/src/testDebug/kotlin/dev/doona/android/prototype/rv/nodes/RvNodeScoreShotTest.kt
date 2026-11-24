package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvNode
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
 * Score group chose carry its mark (`proto-rv-nodes-page-score-a-*`, `-node-detail-score-a-*`, the node in use, chosen on
 * TCP).
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
        // Each dot is held to the word after it (a no-break space), so a wrapped line never ends on a dot.
        assertEquals(listOf("Score TCP ·\u00A0VLESS ·\u00A0香港", "Score UDP ·\u00A0Trojan ·\u00A0香港"), marked)
        val points = compose.onAllNodesWithText("分", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .filter { it.text.replace(WORD_JOINER, "").matches(POINTS) }
        assertEquals(emptyList<Any>(), points)
    }

    /** A marked row's line fits one line at the default size, as an unmarked row's does. */
    @Test
    @Config(qualifiers = PAGE_A)
    fun aMarkKeepsTheLineOnOneLine() = assertEquals(listOf(listOf("Score TCP ·\u00A0VLESS ·\u00A0香港"), listOf("Score UDP ·\u00A0Trojan ·\u00A0香港")), markedLines(1f))

    /** At fs115 the line is too long for one line and breaks after the mark, before its dot. */
    @Test
    @Config(qualifiers = PAGE_A_FS115)
    fun aMarkBreaksBeforeTheDotAtFs115() =
        assertEquals(listOf(listOf("Score TCP", "·\u00A0VLESS ·\u00A0香港"), listOf("Score UDP", "·\u00A0Trojan ·\u00A0香港")), markedLines(RV_FS115))

    /** The lines each marked row's line is laid out in, without word joiners. */
    private fun markedLines(fontScale: Float): List<List<String>> {
        compose.rvContent(dark = false, fontScale = fontScale) { Page(marks) }
        return compose.onAllNodesWithText(MARK, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { SemanticsActions.GetTextLayoutResult in it.config }
            .map { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val layout = layouts.single()
                val text = layout.layoutInput.text.text
                (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)).replace(WORD_JOINER, "").trim() }
            }
    }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { assertWhole() }, fontScale = fontScale, content = content)

    @Composable
    private fun Page(score: RvScoreMarks) = RvNodesPage(page, onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {}, score = score)

    @Composable
    private fun Detail(node: RvNode, score: RvScoreMarks) =
        RvNodeDetail(node, current = node.id == page.currentId, page.testedAtEpochMs, onBack = {}, onUse = {}, onTest = {}, score = score)

    private companion object {
        val marks = RvScoreMarks(listOf(RvScoreSample.chosen))
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
    }
}
