package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.doona.android.R
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNodeSort
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvScrollBy
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main node's picker in each order, light and dark, in the phone's window: 延遲 (`proto-rv-policy-picker-*`), 穩定
 * (`-stability-*`, each row's second trailing line how many tests answered), 名稱 (`-name-*`), and 地區
 * (`-region-*`, a card per country across both subscriptions; `-region-scrolled-*` shows 香港's card, whose nodes come
 * from both, and 日本's under it). `-edges-latency-*` and `-edges-name-*` show one card of six nodes under 延遲 and 名稱:
 * New York 05, whose last test timed out, and Amsterdam 01, never tested, sort last under both keys though their names
 * would lead or fall in the middle; the current node's long name ends in an ellipsis before its latency.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodeListShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun latencyLight() = compose.rvShot("policy-picker", dark = false) { Picker(RvNodeSort.Latency) }

    @Test
    fun latencyDark() = compose.rvShot("policy-picker", dark = true) { Picker(RvNodeSort.Latency) }

    @Test
    fun stabilityLight() = compose.rvShot("policy-picker-stability", dark = false) { Picker(RvNodeSort.Stability) }

    @Test
    fun stabilityDark() = compose.rvShot("policy-picker-stability", dark = true) { Picker(RvNodeSort.Stability) }

    @Test
    fun nameLight() = compose.rvShot("policy-picker-name", dark = false) { Picker(RvNodeSort.Name) }

    @Test
    fun nameDark() = compose.rvShot("policy-picker-name", dark = true) { Picker(RvNodeSort.Name) }

    @Test
    fun regionLight() = compose.rvShot("policy-picker-region", dark = false) { Picker(RvNodeSort.Region) }

    @Test
    fun regionDark() = compose.rvShot("policy-picker-region", dark = true) { Picker(RvNodeSort.Region) }

    @Test
    fun regionScrolledLight() = compose.rvShot("policy-picker-region-scrolled", dark = false, before = { rvScrollBy(REGION_SCROLL) }) {
        Picker(RvNodeSort.Region)
    }

    @Test
    fun regionScrolledDark() = compose.rvShot("policy-picker-region-scrolled", dark = true, before = { rvScrollBy(REGION_SCROLL) }) {
        Picker(RvNodeSort.Region)
    }

    @Test
    fun edgesLatencyLight() = compose.rvShot("policy-picker-edges-latency", dark = false) { Edges(RvNodeSort.Latency) }

    @Test
    fun edgesLatencyDark() = compose.rvShot("policy-picker-edges-latency", dark = true) { Edges(RvNodeSort.Latency) }

    @Test
    fun edgesNameLight() = compose.rvShot("policy-picker-edges-name", dark = false) { Edges(RvNodeSort.Name) }

    @Test
    fun edgesNameDark() = compose.rvShot("policy-picker-edges-name", dark = true) { Edges(RvNodeSort.Name) }

    /** The order's chips sit one search gap under the page's search, as every list sits under its search (item 4). */
    @Test
    fun theOrderSitsASearchGapUnderTheSearch() {
        compose.rvContent(dark = false) { Picker(RvNodeSort.Latency) }
        val search = compose.onNodeWithText(rvText(R.string.rv_search_nodes)).fetchSemanticsNode()
        val chip = compose.onNode(hasText(rvText(R.string.rv_nodes_sort_latency)) and isSelectable()).fetchSemanticsNode()
        val gap = (chip.boundsInRoot.top - search.boundsInRoot.bottom) / search.layoutInfo.density.density
        assertEquals("search → order chips, dp", RvSpace.searchGap.value, gap, DP)
    }

    /** One subscription of six sample nodes: four answered, one timed out, one never tested, and a long name. */
    @Composable
    private fun Edges(sort: RvNodeSort) {
        val sub = RvData.subscriptions.first()
        val nodes = EDGE_NODES.map { id ->
            val node = RvData.nodes.first { it.id == id }
            when (id) {
                UNTESTED -> node.untested()
                RvData.CURRENT_ID -> node.copy(name = LONG_NAME)
                else -> node
            }.copy(subscription = sub.name)
        }
        RvNodePickerPage(nodes, listOf(sub), RvData.CURRENT_ID, sort, onSort = {}, onPick = {}, onBack = {}, onTest = {})
    }

    @Composable
    private fun Picker(sort: RvNodeSort) = RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, sort, onSort = {}, onPick = {}, onBack = {}, onTest = {})

    private companion object {
        /** Scrolled this far, 臺灣's card ends under the bar and 香港's follows in full. */
        const val REGION_SCROLL = 600f

        /** New York 05's last test timed out in the sample; Amsterdam 01 is made untested; hk-02 is the current node. */
        val EDGE_NODES = listOf("us-05", "nl-01", "tw-04", RvData.CURRENT_ID, "hk-01", "jp-01")
        const val UNTESTED = "nl-01"
        const val LONG_NAME = "Hong Kong 02 IPLC Premium Dedicated Business Line"
        const val DP = 0.5f
    }
}
