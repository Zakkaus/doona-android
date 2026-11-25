package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.prototype.rv.connections.RvConnectionDetail
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.sampleNodesPage
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.settings.RvAboutScreen
import dev.doona.android.prototype.rv.settings.rvSampleVersions
import dev.doona.android.prototype.rv.subs.RvSubAdd
import dev.doona.android.prototype.rv.subs.RvSubDetail
import dev.doona.android.prototype.rv.subs.RvSubDetailState
import dev.doona.android.prototype.rv.subs.RvUserAgentField
import dev.doona.android.prototype.rv.subs.rvAddSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The design board's capture ([rvBoard]): the window and its layout in dp, the rulers' measurements on it, and a long
 * page's every screen; how a page's start is told from its parts; a field as a block of the page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvBoardTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @get:Rule
    val folder = TemporaryFolder()

    /** 關於 fits its window: one capture, a pushed page, its cards 16 dp apart and every gap a token. */
    @Test
    fun aPushedPageIsOneWindowWithItsRulers() {
        val dir = capture("about") { RvAboutScreen(rvSampleVersions, onBack = {}) }
        assertTrue(File(dir, "about-light-1.png").isFile)
        assertFalse("關於 does not scroll", File(dir, "about-light-2.json").exists())
        val page = json(dir, "about-light-1")
        assertEquals("Title", page.getString("top"))
        assertEquals(412.0, page.getDouble("width"), 0.0)
        assertEquals(915.0, page.getDouble("height"), 0.0)
        val gaps = rules(page, "Gap")
        assertTrue("the cards' gaps are measured", gaps.count { abs(it.getDouble("actual") - 16.0) <= 0.5 } >= 2)
        assertTrue("every gap on 關於 is a token", gaps.all { it.getBoolean("token") })
        assertEquals("關於 keeps to the rulers", 0, page.getJSONArray("drift").length())
        assertTrue("the version rows' text has baselines", page.getJSONArray("baselines").length() >= 3)
        val tags = (0 until page.getJSONArray("parts").length()).map { page.getJSONArray("parts").getJSONObject(it).getString("tag") }
        assertTrue(RvPart.TITLE in tags && RvPart.CARD in tags && RvPart.ROW in tags)
    }

    /**
     * 節點 is longer than two windows: a capture each screen down to its end, each measured without the header, and the
     * page scrolled back to its start afterwards.
     */
    @Test
    fun aLongPageGetsEveryScreenToItsEnd() {
        val dir = capture("nodes") { RvNodesPage(sampleNodesPage(), onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {}) }
        val windows = generateSequence(1) { it + 1 }.takeWhile { File(dir, "nodes-light-$it.json").isFile }.map { json(dir, "nodes-light-$it") }.toList()
        assertTrue("節點 takes more than two windows, got ${windows.size}", windows.size > 2)
        assertTrue(File(dir, "nodes-light-${windows.size}.png").isFile)
        assertEquals("Title", windows.first().getString("top"))
        assertEquals(0.0, windows.first().getDouble("scrolled"), 0.0)
        val scrolled = windows.map { it.getDouble("scrolled") }
        assertTrue("each window is further down: $scrolled", scrolled.zipWithNext().all { (a, b) -> b > a + 100.0 })
        assertTrue(windows.drop(1).all { rules(it, "Top").isEmpty() })
        val cards = windows.map { top(it, RvPart.CARD) }
        assertTrue("the content moved up: $cards", cards.zipWithNext().all { (a, b) -> b < a })
        val page = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
            .first { it.config.contains(SemanticsActions.ScrollBy) }
        val range = page.config[SemanticsProperties.VerticalScrollAxisRange]
        val d = page.layoutInfo.density.density
        assertEquals("the page is back at its start", 0f, range.value(), RV_TOLERANCE)
        assertEquals("the last window reaches the page's end", range.maxValue() / d, scrolled.last().toFloat(), RV_TOLERANCE)
    }

    @Test
    fun aTabHomeIsToldByItsFirstBlock() {
        val dir = capture("policy") { RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}) }
        assertEquals("Tab", json(dir, "policy-light-1").getString("top"))
    }

    @Test
    fun aDetailPageIsToldByItsCentredHeader() {
        val dir = capture("connection") { RvConnectionDetail(RvPolicyData.connections[YOUTUBE], onBack = {}, onClose = {}, onNewRule = {}) }
        assertEquals("Header", json(dir, "connection-light-1").getString("top"))
    }

    @Test
    fun onlyLightAndDarkAreCaptured() {
        val dir = folder.newFolder("fs")
        compose.rvContent(dark = false) { RvAboutScreen(rvSampleVersions, onBack = {}) }
        compose.rvBoard("about", "fs200", dir)
        assertTrue(dir.list().isNullOrEmpty())
    }

    /** A gap with a search field in it is measured to the field and from it, each only held to the spacing tokens. */
    @Test
    fun aGapWithContentInItIsMeasuredToTheContent() {
        val gap = RvRule(RvRuleKind.Gap, "gap rv-title to rv-card at 211 dp", 68f, 24f, Rect(16f, 143f, 396f, 211f))
        val field = Rect(16f, 159f, 396f, 195f)
        val split = rvSplitGaps(listOf(gap), listOf(field, Rect(0f, 0f, 412f, 915f)))
        assertEquals(listOf(16f, 16f), split.map { it.actual })
        assertTrue(split.all { it.expected == null && it.token && !it.off })
        assertEquals(listOf(gap), rvSplitGaps(listOf(gap), listOf(Rect(16f, 300f, 396f, 400f))))
    }

    /** 新增訂閱's fields, label and message with each, are blocks of its column: the gaps run to them, none is split at a label. */
    @Test
    fun aFieldIsABlockOfThePage() {
        val add = json(capture("sub-add") { RvSubAdd(rvAddSample("importing"), {}, onPaste = {}, onImport = {}, onBack = {}) }, "sub-add-light-1")
        val blocks = (0 until add.getJSONArray("blocks").length()).map { add.getJSONArray("blocks").getJSONObject(it).getString("tag") }
        assertTrue("新增訂閱's fields are blocks: $blocks", blocks.count { it == RvPart.FIELD } >= 2)
        val gaps = rules(add, "Gap").map { it.getString("what") }
        assertTrue("a gap runs from the title to the first field: $gaps", gaps.any { it.startsWith("gap ${RvPart.TITLE} to ${RvPart.FIELD}") })
        assertTrue("no gap is split at a field's label: $gaps", gaps.none { it.startsWith("gap down to the content") })
    }

    /** A field in a sheet over a page (the User-Agent sheet) is the sheet's: no block of the page under it. */
    @Test
    fun aFieldInASheetIsNoPartOfThePage() {
        val sheet = json(
            capture("sheet") {
                Box(Modifier.fillMaxSize()) {
                    RvSubDetail(RvData.subscriptions.first(), RvSubDetailState(), {}, onBack = {}, onUpdate = {})
                    RvSheetLayout({ 1f }, {}, "User-Agent") { RvUserAgentField("", {}) }
                }
            },
            "sheet-light-1",
        )
        assertEquals("the sheet has its field", 1, compose.onAllNodesWithTag(RvPart.FIELD).fetchSemanticsNodes().size)
        val tags = (0 until sheet.getJSONArray("parts").length()).map { sheet.getJSONArray("parts").getJSONObject(it).getString("tag") }
        assertFalse("the sheet's field is no part of the page: $tags", RvPart.FIELD in tags)
    }

    private fun capture(screen: String, content: @Composable () -> Unit): File {
        val dir = folder.newFolder(screen)
        compose.rvContent(dark = false, content = content)
        compose.rvBoard(screen, "light", dir)
        return dir
    }

    private fun json(dir: File, name: String) = JSONObject(File(dir, "$name.json").readText())

    private fun rules(page: JSONObject, kind: String): List<JSONObject> {
        val all = page.getJSONArray("rules")
        return (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("kind") == kind }
    }

    private fun top(page: JSONObject, tag: String): Double {
        val parts = page.getJSONArray("parts")
        return (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.getString("tag") == tag }.minOf { it.getJSONArray("rect").getDouble(1) }
    }

    private companion object {
        /** YouTube's connection, as RvLayoutTest measures it. */
        const val YOUTUBE = 3
    }
}
