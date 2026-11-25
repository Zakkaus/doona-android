package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.nodes.RvNodeDetail
import dev.doona.android.feature.nodes.RvNodesPage
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Revolut-style 節點 page and a node's page, light and dark, in the scheme of the owner's wallpaper, each in one
 * capture of the whole page: the 節點 page (`proto-rv-nodes-page-light` and `-dark`), and the node's page, sg-03
 * slowing down, with every key stat and the space it keeps clear above its floating actions
 * (`proto-rv-node-detail-light` and `-dark`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageLight() = compose.rvShot("nodes-page", dark = false, before = { assertWhole() }) { Page() }

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageDark() = compose.rvShot("nodes-page", dark = true, before = { assertWhole() }) { Page() }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailLight() = compose.rvShot("node-detail", dark = false, before = { assertWhole() }) { Detail() }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailDark() = compose.rvShot("node-detail", dark = true, before = { assertWhole() }) { Detail() }

    @Composable
    private fun Page() = RvNodesPage(sampleNodesPage(), onBack = {}, onOpen = {}, onAllNodes = {})

    @Composable
    private fun Detail() {
        val page = sampleNodesPage()
        val node = page.nodes.first { it.id == RV_NODES_DETAIL }
        RvNodeDetail(node, current = node.id == page.currentId, page.testedAtEpochMs, onBack = {}, onUse = {}, onTest = {})
    }

    private companion object {
        /** A window as tall as the whole 節點 page, so one capture holds every card. */
        const val WHOLE_PAGE = "+h2893dp"

        /** A window as tall as the whole node page, so one capture holds every stat above the floating actions. */
        const val WHOLE_DETAIL = "+h1220dp"
    }
}
