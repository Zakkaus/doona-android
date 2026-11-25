package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.settings.RvPerAppActions
import dev.doona.android.prototype.rv.settings.RvPerAppLook
import dev.doona.android.prototype.rv.settings.RvPerAppScreen
import dev.doona.android.prototype.rv.settings.rvPerAppLookSample
import dev.doona.android.prototype.rv.settings.rvPerAppSample
import dev.doona.android.prototype.rv.subs.RvSubsActions
import dev.doona.android.prototype.rv.subs.RvSubsPage
import dev.doona.android.prototype.rv.subs.RvSubsState
import dev.doona.android.testing.drawFully
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The pages with a skeleton, by the names their goldens carry. */
internal object RvSkeletonPages {
    const val ACTIVITY = "activity"
    const val POLICY = "policy"
    const val POLICY_GLOBAL = "policy-global"
    const val POLICY_DIRECT = "policy-direct"
    const val SUBS = "subs"
    const val PER_APP = "perapp"
    const val PER_APP_V1 = "perapp-v1"
}

/** [page] with its skeleton while [loading], else with its content, in the samples its goldens show. */
@Composable
internal fun RvSkeletonPage(page: String, loading: Boolean) {
    when (page) {
        RvSkeletonPages.ACTIVITY -> RvActivityScreen(rvIdleActivity(), onAction = {}, onConnect = {}, loading = loading)
        RvSkeletonPages.POLICY -> RvPolicyScreen(RvPolicyState(), onChange = {}, loading = loading)
        RvSkeletonPages.POLICY_GLOBAL -> RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Global), onChange = {}, loading = loading)
        RvSkeletonPages.POLICY_DIRECT -> RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Direct), onChange = {}, loading = loading)
        RvSkeletonPages.SUBS -> RvSubsPage(RvSubsState(), RvSubsActions(), loading = loading)
        RvSkeletonPages.PER_APP_V1 -> RvPerAppScreen(rvPerAppLookSample(if (loading) "loading" else ""), RvPerAppActions(), onBack = {}, look = RvPerAppLook.Checks)
        else -> RvPerAppScreen(rvPerAppSample(if (loading) "loading" else ""), RvPerAppActions(), onBack = {})
    }
}

/**
 * Skeleton to content moves nothing (owner, 2026-11-24): on every page with a skeleton, each skeleton part in the window
 * has the bounds of the part that replaces it, within [TOLERANCE]: each card its top and the bottom the window shows,
 * each row its top and height, each line of text its top, its height and its start, middle or end (a bar's length is
 * the skeleton's where its words are data, not there yet), and no bar reaches into another text; each block (a
 * control's track, a chip, a chart's frame, a quota's bar) its four edges. A row's disc stands where its row puts it, so
 * the row stands for it. Words the page knows before its data (a node's, a group's, a subscription's name) are laid out
 * in the skeleton as in the row, so a row whose name wraps at a large font size is as tall as its skeleton.
 *
 * A row whose words are data (an app's package, a quota) is taller than its skeleton where they wrap, as the skeleton
 * cannot know them: such rows are named in [WRAPS], so none is a surprise; every row keeps its height but those, and
 * every part above the first of them its bounds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSkeletonBoundsTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun activity() = bounds(RvSkeletonPages.ACTIVITY, 1f)

    @Test fun activityFs115() = bounds(RvSkeletonPages.ACTIVITY, RV_FS115)

    @Test fun activityFs200() = bounds(RvSkeletonPages.ACTIVITY, RV_FS200)

    @Test fun policy() = bounds(RvSkeletonPages.POLICY, 1f)

    @Test fun policyFs115() = bounds(RvSkeletonPages.POLICY, RV_FS115)

    @Test fun policyFs200() = bounds(RvSkeletonPages.POLICY, RV_FS200)

    @Test fun policyGlobal() = bounds(RvSkeletonPages.POLICY_GLOBAL, 1f)

    @Test fun policyGlobalFs115() = bounds(RvSkeletonPages.POLICY_GLOBAL, RV_FS115)

    @Test fun policyGlobalFs200() = bounds(RvSkeletonPages.POLICY_GLOBAL, RV_FS200)

    @Test fun policyDirect() = bounds(RvSkeletonPages.POLICY_DIRECT, 1f)

    @Test fun policyDirectFs115() = bounds(RvSkeletonPages.POLICY_DIRECT, RV_FS115)

    @Test fun policyDirectFs200() = bounds(RvSkeletonPages.POLICY_DIRECT, RV_FS200)

    @Test fun subs() = bounds(RvSkeletonPages.SUBS, 1f)

    @Test fun subsFs115() = bounds(RvSkeletonPages.SUBS, RV_FS115)

    @Test fun subsFs200() = bounds(RvSkeletonPages.SUBS, RV_FS200)

    @Test fun perApp() = bounds(RvSkeletonPages.PER_APP, 1f)

    @Test fun perAppFs115() = bounds(RvSkeletonPages.PER_APP, RV_FS115)

    @Test fun perAppFs200() = bounds(RvSkeletonPages.PER_APP, RV_FS200)

    @Test fun perAppV1() = bounds(RvSkeletonPages.PER_APP_V1, 1f)

    @Test fun perAppV1Fs115() = bounds(RvSkeletonPages.PER_APP_V1, RV_FS115)

    @Test fun perAppV1Fs200() = bounds(RvSkeletonPages.PER_APP_V1, RV_FS200)

    /**
     * 依 App's skeleton rows stop above 儲存's room (the gap over the button and the button), as the loaded list's last
     * row clears it at the end of its scroll: a row ending past it keeps its place but draws nothing, so its disc's middle
     * shows the card; a row above it draws its disc.
     */
    @Test
    fun perAppDrawsNoSkeletonRowUnderSave() {
        rvAnimatorScale(0f)
        compose.rvContent(dark = false) { RvSkeletonPage(RvSkeletonPages.PER_APP, loading = true) }
        compose.waitForIdle()
        compose.drawFully()
        val image = compose.onRoot().captureToImage().toPixelMap()
        val save = compose.onNodeWithText(rvText(R.string.perapp_save)).fetchSemanticsNode()
        val px = save.layoutInfo.density.density
        val floor = save.boundsInRoot.top - RvSpace.gutter.value * px
        val rows = compose.onAllNodesWithTag(RvPart.ROW, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        val discs = compose.onAllNodesWithTag(RvPart.SKELETON_DISC, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        // A disc past the window's foot has no bounds in it.
        val shown = discs.filter { it.height > 0f && it.center.y < image.height }
        assertTrue("依 App shows skeleton rows on both sides of 儲存's room", shown.any { rowOf(rows, it).bottom <= floor } && shown.any { rowOf(rows, it).bottom > floor })
        shown.forEach { disc ->
            val row = rowOf(rows, disc)
            // Past the disc and the bars, the row's end: the card's own fill.
            val card = image.at(Offset(row.right - RvSpace.rowPadding.value * px, disc.center.y))
            assertEquals("the skeleton row at ${(row.top / px).roundToInt()} dp draws its disc", row.bottom <= floor, image.at(disc.center) != card)
        }
    }

    private fun rowOf(rows: List<Rect>, disc: Rect) = rows.firstOrNull { it.contains(disc.center) } ?: error("no row holds the disc at $disc; rows $rows")

    private fun PixelMap.at(at: Offset) = this[at.x.toInt(), at.y.toInt()]

    private fun bounds(page: String, fontScale: Float) {
        rvAnimatorScale(0f)
        val loading = mutableStateOf(true)
        compose.rvContent(dark = false, fontScale) { RvSkeletonPage(page, loading.value) }
        compose.waitForIdle()
        val window = compose.onRoot().fetchSemanticsNode().let { it.size.height / it.layoutInfo.density.density }
        val skeleton = compose.parts(window, SKELETON_TAGS)
        // What the page shows before its data (its bars, its nav, a real card) is not what the skeleton stands for.
        val chrome = compose.texts(window)
        loading.value = false
        compose.waitForIdle()
        val content = compose.texts(window).filter { text -> chrome.none { abs(it.top - text.top) <= TOLERANCE && abs(it.left - text.left) <= TOLERANCE } }
        val loaded = compose.parts(window, LOADED_TAGS) + (TEXT to content)
        val rows = skeleton.of(RvPart.ROW).zip(loaded.of(RvPart.ROW))
        val wraps = rows.indices.filter { abs(rows[it].first.height - rows[it].second.height) > TOLERANCE }.toSet()
        assertEquals("$page at $fontScale: the rows whose data, not there yet, wraps past their skeleton", WRAPS[page to fontScale].orEmpty(), wraps)
        // Under the first such row the content stands lower than its skeleton: there only the rows' heights are compared.
        val cut = wraps.minOrNull()?.let { rows[it].second.top } ?: Float.MAX_VALUE
        val drift = mutableListOf<String>()
        if (wraps.isEmpty()) {
            same(drift, "cards in the window", skeleton.of(RvPart.CARD).size.toFloat(), loaded.of(RvPart.CARD).size.toFloat())
            same(drift, "rows in the window", skeleton.of(RvPart.ROW).size.toFloat(), loaded.of(RvPart.ROW).size.toFloat())
        }
        skeleton.of(RvPart.CARD).zip(loaded.of(RvPart.CARD)).forEachIndexed { i, (card, real) ->
            if (card.top <= cut) same(drift, "card $i's top", card.top, real.top)
            if (card.bottom <= cut) same(drift, "card $i's bottom in the window", min(card.bottom, window), min(real.bottom, window))
        }
        rows.forEachIndexed { i, (row, real) ->
            if (row.top <= cut) same(drift, "row $i's top", row.top, real.top)
            if (i !in wraps) same(drift, "row $i's height", row.height, real.height)
        }
        skeleton.of(RvPart.SKELETON_LINE).filter { it.bottom <= cut }.forEach { line(drift, it, loaded.of(TEXT)) }
        skeleton.of(RvPart.SKELETON_BLOCK).filter { it.bottom <= cut }.forEach { block(drift, it, loaded.of(RvPart.FRAME) + loaded.of(RvPart.SEGMENTED)) }
        assertTrue("$page at $fontScale moves when its content comes:\n" + drift.joinToString("\n"), drift.isEmpty())
    }

    /**
     * A skeleton [line] against its text: the one it overlaps most of those with its top, its height and one of its start,
     * middle and end. Nor may its bar reach into any other text: in the cross-fade it would lie under that text.
     */
    private fun line(drift: MutableList<String>, line: Rect, texts: List<Rect>) {
        val where = "the line at ${line.left}..${line.right}, ${line.top} (${line.height} tall)"
        val text = texts.filter { onItsLine(line, it) && overlap(it, line) > 0f }.maxByOrNull { overlap(it, line) }
        if (text == null) {
            drift += "$where: no text on its line box ever comes: " + texts.filter { overlap(it, line) > 0f }.joinToString { "${it.left}..${it.right}, ${it.top} (${it.height})" }
            return
        }
        texts.filter { it != text && !text.containsRect(it) && !it.containsRect(text) && overlap(it, line) > 0f }.forEach {
            drift += "$where reaches into the text at ${it.left}..${it.right}, ${it.top}"
        }
    }

    private fun onItsLine(line: Rect, text: Rect): Boolean {
        val ends = listOf(line.left - text.left, line.center.x - text.center.x, line.right - text.right)
        return abs(line.top - text.top) <= TOLERANCE && abs(line.height - text.height) <= TOLERANCE && ends.any { abs(it) <= TOLERANCE }
    }

    private fun Rect.containsRect(other: Rect) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    /** A skeleton [block] against the frame (a control's track, a chip, a chart, a bar) it overlaps most: all four edges. */
    private fun block(drift: MutableList<String>, block: Rect, frames: List<Rect>) {
        val frame = frames.maxByOrNull { overlap(it, block) }?.takeIf { overlap(it, block) > 0f }
        if (frame == null) {
            drift += "the block at ${block.top} dp stands over no part"
            return
        }
        listOf(Rect::left, Rect::top, Rect::right, Rect::bottom).forEach { edge ->
            same(drift, "the block at ${block.left}, ${block.top}: ${edge.name}", edge(block), edge(frame))
        }
    }

    private fun overlap(a: Rect, b: Rect) = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))

    private fun same(drift: MutableList<String>, what: String, skeleton: Float, content: Float) {
        if (abs(skeleton - content) > TOLERANCE) drift += "$what: $skeleton dp with the skeleton, $content dp with the content"
    }

    private fun Map<String, List<Rect>>.of(kind: String) = getValue(kind)

    /** The parts of [tags] in the [window] (dp tall), each in root dp, top to bottom. */
    private fun ComposeContentTestRule.parts(window: Float, tags: List<String>): Map<String, List<Rect>> = tags.associateWith { tag ->
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.rect() }.inWindow(window)
    }

    /**
     * The lines of text in the [window]: each text, as wide as its words (a text that fills its room, as a goal's used
     * amount, is no wider than they are), and each glyph-and-text line as one.
     */
    private fun ComposeContentTestRule.texts(window: Float): List<Rect> {
        val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        val inline = onAllNodesWithTag(RvPart.TEXT_LINE, useUnmergedTree = true).fetchSemanticsNodes()
        return (texts.map { it.words() } + inline.map { it.rect() }).inWindow(window)
    }

    /** A text's box from its first line's top to its last's bottom, from where its words start to where they end. */
    private fun SemanticsNode.words(): Rect {
        val box = rect()
        val laid = mutableListOf<TextLayoutResult>().also { config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(it) }.firstOrNull() ?: return box
        val d = layoutInfo.density.density
        val lines = 0 until laid.lineCount
        return Rect(box.left + lines.minOf { laid.getLineLeft(it) } / d, box.top, box.left + lines.maxOf { laid.getLineRight(it) } / d, box.bottom)
    }

    private fun SemanticsNode.rect(): Rect {
        val d = layoutInfo.density.density
        return Rect(positionInRoot / d, size.toSize() / d)
    }

    private fun List<Rect>.inWindow(window: Float) = filter { it.left >= 0f && it.top < window }.sortedWith(compareBy({ it.top }, { it.left }))

    private companion object {
        const val TEXT = "text"
        val SKELETON_TAGS = listOf(RvPart.CARD, RvPart.ROW, RvPart.SKELETON_LINE, RvPart.SKELETON_BLOCK)
        val LOADED_TAGS = listOf(RvPart.CARD, RvPart.ROW, RvPart.FRAME, RvPart.SEGMENTED)

        /**
         * The rows whose words are data the page has not yet, and wrap at a large font size, by page and font size; none
         * elsewhere. Where the page knows a row's words (a node's, a group's, a subscription's name), its skeleton takes
         * their lines and none of these is theirs. At 2.0 全域's subscription over its quota and two of
         * its nodes, which their latency orders; at 2.0 訂閱's subscriptions over their nodes and last update, and 依 App's
         * apps over their packages, which the phone's list gives.
         */
        val WRAPS = mapOf(
            (RvSkeletonPages.POLICY_GLOBAL to RV_FS200) to setOf(0, 3, 4),
            (RvSkeletonPages.SUBS to RV_FS200) to setOf(0, 1),
            (RvSkeletonPages.PER_APP to RV_FS200) to setOf(3, 4, 5, 6),
            (RvSkeletonPages.PER_APP_V1 to RV_FS200) to setOf(3, 4, 5),
        )
        const val TOLERANCE = 0.5f
    }
}
