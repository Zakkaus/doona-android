package dev.doona.android.prototype.rv

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.RvChrome
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Where the design board's captures go: set by `-Ddoona.board=<dir>`; unset, nothing is written. */
private val BOARD = System.getProperty("doona.board")

/** Whether this run draws the design board: the goldens are then neither recorded nor compared. */
internal val rvBoarding = BOARD != null

/** The board's window: the phone's, [RV_BOARD_HEIGHT] at most, also where a golden records a whole page in a taller one. */
internal const val RV_BOARD_HEIGHT = 915f

private const val BOARD_WINDOW = "rv-board-window"

/** Holds [content] to the board's window while the board is drawn; otherwise [content] as it is. */
@Composable
internal fun RvBoardWindow(content: @Composable () -> Unit) {
    if (!rvBoarding) return content()
    Box(Modifier.heightIn(max = RV_BOARD_HEIGHT.dp).testTag(BOARD_WINDOW)) { content() }
}

/**
 * The design board's capture of a golden's page (plans/briefs/design-board.md): with `doona.board` set, writes the
 * window as `<screen>-<variant>-1.png` and its layout as `-1.json` (the library's parts, the rulers' measurements, the
 * outlined nodes and the text baselines, all in dp from the window's top left); a page that scrolls further is scrolled
 * one screen at a time and written again as `-2`, `-3` and on to its end, then scrolled back. Only the light and dark
 * goldens are captured. [into] is the board's directory, `doona.board` unless a test gives its own.
 */
internal fun ComposeContentTestRule.rvBoard(screen: String, variant: String, into: File? = BOARD?.let(::File)) {
    val dir = into ?: return
    if (variant != "light" && variant != "dark") return
    dir.mkdirs()
    val top = boardTop(rvParts())
    write(dir, "$screen-$variant-1", top, start = true, scrolled = 0f)
    var scrolled = 0f
    for (window in 2..BOARD_WINDOWS) {
        val scroll = pageScroll() ?: break
        val d = scroll.layoutInfo.density.density
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        val left = (range.maxValue() - range.value()) / d
        if (left <= RV_TOLERANCE) break
        val step = min(left, scroll.size.height / d - BOARD_COVERED)
        scrollBy(scroll, step * d)
        scrolled += step
        // The parts above the window are still laid out, so a scrolled page keeps its start's gaps; its header's top is not measured.
        write(dir, "$screen-$variant-$window", top, start = false, scrolled = scrolled)
    }
    pageScroll()?.let { scrollBy(it, -scrolled * it.layoutInfo.density.density) }
}

/** The page's own scroll: the first node that scrolls vertically and can be scrolled by a test. */
private fun ComposeContentTestRule.pageScroll(): SemanticsNode? =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
        .firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }

/**
 * How the page starts, from where its parts sit: a large title (or the mark over it) makes a pushed page; otherwise the
 * first block at a large title's place, when it is one composite block, is a centred header, and a first block a search
 * gap under a tab's bar starts a tab home.
 * Any other page opens with its own hero, whose top no ruler sets.
 */
internal fun boardTop(parts: Map<String, List<Rect>>): Top {
    if (parts.getValue(RvPart.TITLE).isNotEmpty() || parts.getValue(RvPart.MARK).isNotEmpty()) return Top.Title
    val (tag, rect) = RvMeasure(parts, 0f).blocks().firstOrNull() ?: return Top.Hero
    return when {
        // Both sit 108 dp down; a centred header is one block (a detail page's), a tab home starts with a card or control.
        tag == RvPart.BLOCK && abs(rect.top - (rvBarBottom(Top.Header) + HEADER_TOP)) <= RV_TOLERANCE -> Top.Header

        abs(rect.top - (rvBarBottom(Top.Tab) + TAB_FIRST)) <= RV_TOLERANCE -> Top.Tab

        else -> Top.Hero
    }
}

private fun ComposeContentTestRule.scrollBy(node: SemanticsNode, px: Float) {
    onNode(SemanticsMatcher("the page's scroll") { it.id == node.id }).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
    waitForIdle()
}

private fun ComposeContentTestRule.write(dir: File, name: String, top: Top, start: Boolean, scrolled: Float) {
    waitForIdle()
    val window = onAllNodesWithTag(BOARD_WINDOW).fetchSemanticsNodes().isNotEmpty()
    val bitmap = (if (window) onNodeWithTag(BOARD_WINDOW) else onRoot()).captureToImage().asAndroidBitmap()
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    val parts = rvParts()
    val measure = RvMeasure(parts, rvBarBottom(top))
    val ran = measure.run(top, start)
    val outlined = nodes()
    val rules = rvSplitGaps(measure.rules, outlined.map { it.rect })
    // A gap split at its content is measured by its two parts, so its own drift goes.
    val split = measure.rules - rules.toSet()
    val drift = ran.filterNot { line -> split.any { line.startsWith(it.what + ":") } }
    val json = JSONObject()
        .put("name", name)
        .put("top", top.name)
        .put("bar", rvBarBottom(top).toDouble())
        .put("scrolled", scrolled.toDouble())
        .put("width", (bitmap.width / density.density).toDouble())
        .put("height", (bitmap.height / density.density).toDouble())
        .put("tokens", JSONArray(rvSpacingTokens.map { it.toDouble() }))
        .put("parts", JSONArray(parts.flatMap { (tag, rects) -> rects.map { JSONObject().put("tag", tag).put("rect", it.json()) } }))
        .put("blocks", JSONArray(measure.blocks().map { (tag, rect) -> JSONObject().put("tag", tag).put("rect", rect.json()) }))
        .put("rules", JSONArray(rules.map { it.json() }))
        .put("drift", JSONArray(drift))
        .put("nodes", JSONArray(outlined.map { it.json() }))
        .put("baselines", JSONArray(baselines()))
    File(dir, "$name.json").writeText(json.toString(1))
}

/**
 * The gaps the rulers measured, with any that has content in it which is no library part (a search field, a busy bar,
 * a line of text) split at that content: the gap down to it and the gap under it, which no token pairs, so each only has
 * to be a spacing token. [nodes] are the outlined nodes, in dp.
 */
internal fun rvSplitGaps(rules: List<RvRule>, nodes: List<Rect>): List<RvRule> = rules.flatMap { rule ->
    val strip = rule.at
    val inside = nodes.filter {
        rule.kind == RvRuleKind.Gap && it.top >= strip.top - RV_TOLERANCE && it.bottom <= strip.bottom + RV_TOLERANCE &&
            it.height > RV_TOLERANCE && it.left < strip.right && strip.left < it.right
    }
    if (inside.isEmpty()) return@flatMap listOf(rule)
    val top = inside.minOf { it.top }
    val bottom = inside.maxOf { it.bottom }
    listOf(
        RvRule(RvRuleKind.Gap, "gap down to the content at $top dp", top - strip.top, null, Rect(strip.left, strip.top, strip.right, top)),
        RvRule(RvRuleKind.Gap, "gap under the content at $bottom dp", strip.bottom - bottom, null, Rect(strip.left, bottom, strip.right, strip.bottom)),
    )
}

private fun RvRule.json() = JSONObject()
    .put("kind", kind.name)
    .put("what", what)
    .put("actual", actual.toDouble())
    .put("expected", expected?.toDouble() ?: JSONObject.NULL)
    .put("off", off)
    .put("token", token)
    .put("at", at.json())

private fun Rect.json() = JSONArray(listOf(left, top, right, bottom).map { it.toDouble() })

/** A node the board outlines: one with a tag, text, a description or a click, as the window clips it, in dp. */
private class Outlined(val rect: Rect, val tag: String?, val text: String?, val click: Boolean) {
    fun json() = JSONObject().put("rect", rect.json()).put("tag", tag ?: JSONObject.NULL).put("text", text ?: JSONObject.NULL).put("click", click)
}

private fun ComposeContentTestRule.nodes(): List<Outlined> = unmerged().mapNotNull { node ->
    val c = node.config
    val tag = c.getOrNull(SemanticsProperties.TestTag)
    val text = c.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    val click = c.contains(SemanticsActions.OnClick)
    val d = node.layoutInfo.density.density
    val b = node.boundsInRoot
    if ((tag == null && text == null && !click) || b.width <= 0f || b.height <= 0f) null else Outlined(Rect(b.left / d, b.top / d, b.right / d, b.bottom / d), tag, text, click)
}

/** Each visible line of text: its baseline's y and the line's ends, in dp. */
private fun ComposeContentTestRule.baselines(): List<JSONObject> = unmerged().flatMap { node ->
    val layouts = mutableListOf<TextLayoutResult>()
    val get = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
    if (get == null || get(layouts) != true) return@flatMap emptyList()
    val d = node.layoutInfo.density.density
    val clip = node.boundsInRoot
    if (clip.height <= 0f) return@flatMap emptyList()
    val origin = node.positionInRoot
    layouts.flatMap { layout ->
        (0 until layout.lineCount).mapNotNull { line ->
            val y = origin.y + layout.getLineBaseline(line)
            if (y < clip.top || y > clip.bottom) return@mapNotNull null
            JSONObject()
                .put("y", (y / d).toDouble())
                .put("x0", ((origin.x + layout.getLineLeft(line)) / d).toDouble())
                .put("x1", ((origin.x + layout.getLineRight(line)) / d).toDouble())
        }
    }
}

private fun ComposeContentTestRule.unmerged(): List<SemanticsNode> {
    val root = onRoot(useUnmergedTree = true).fetchSemanticsNode()
    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    return walk(root)
}

private const val PNG_QUALITY = 100

/** The top bar over a scrolled page and the nav or gesture bar under it: the next screen starts under what they covered. */
private const val BOARD_COVERED = 192f

/** The most windows one page gets: a list whose length the scroll only estimates cannot run the board on for ever. */
private const val BOARD_WINDOWS = 20

/** A centred header sits where a large title would ([RvChrome.largeTitleTop]). */
private val HEADER_TOP = RvChrome.largeTitleTop.value

/** A tab home's first content sits a search gap under its bar. */
private val TAB_FIRST = RvSpace.searchGap.value
