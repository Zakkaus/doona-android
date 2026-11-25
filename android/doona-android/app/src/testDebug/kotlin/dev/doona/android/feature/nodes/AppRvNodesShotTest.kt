package dev.doona.android.feature.nodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.rememberRvPullState
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeRuleMode
import dev.doona.android.engine.fake.RvSampleNodes
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RV_NODES_DETAIL
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 節點, a node's page and the pull-down's picker as the app draws them: the RV sample engine, NodesViewModel, the mapper,
 * then the routes (goldens `app-rv-nodes-page-*`, `app-rv-node-detail-*`, `app-rv-node-picker-*`), each in zh light,
 * dark and fs200 and en light, to set beside `proto-rv-nodes-page-*`, `proto-rv-node-detail-*` and `proto-rv-node-picker-*`.
 * The page and the node's page are whole; the picker is its first screen, as its prototype golden. RvData's now is the
 * ViewModel's clock. The `-nomain` goldens are a profile with no main group (no Proxy); `-split` the main group's TCP and
 * UDP on two nodes; `-noread` a failed read of the nodes' details; `-inline` a node the profile lists itself.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvNodesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageLight() = shot("nodes-page", dark = false, whole = true) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageDark() = shot("nodes-page", dark = true, whole = true) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_PAGE_FS200)
    fun pageFs200() = shot("nodes-page", fontScale = RV_FS200, whole = true) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_PAGE_EN)
    fun pageEnLight() = shot("nodes-page-en", dark = false, whole = true) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailLight() = shot("node-detail", dark = false, whole = true) { Detail(it) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailDark() = shot("node-detail", dark = true, whole = true) { Detail(it) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL_FS200)
    fun detailFs200() = shot("node-detail", fontScale = RV_FS200, whole = true) { Detail(it) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL_EN)
    fun detailEnLight() = shot("node-detail-en", dark = false, whole = true) { Detail(it) }

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageNoMainLight() = shot("nodes-page-nomain", dark = false, whole = true, engine = ::noMainEngine) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageNoMainDark() = shot("nodes-page-nomain", dark = true, whole = true, engine = ::noMainEngine) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailNoMainLight() = shot("node-detail-nomain", dark = false, whole = true, engine = ::noMainEngine) { Detail(it) }

    @Test fun pickerNoMainLight() = shot("node-picker-nomain", dark = false, engine = ::noMainEngine) { Picker(it) }

    /** The main group's networks apart: 套用到 over the list, and each network's node checked. */
    @Test fun pickerSplitLight() = shot("node-picker-split", dark = false, engine = ::splitEngine) { Picker(it) }

    /** A node the profile lists itself, in no subscription: the list ends with its 組態內 section. */
    @Test
    @Config(qualifiers = WHOLE_PICKER)
    fun pickerInlineLight() = shot("node-picker-inline", dark = false, whole = true, engine = ::inlineEngine) { Picker(it) }

    /** 節點 with the main group's networks apart: its TCP and its UDP node both carry the check. */
    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageSplitLight() = shot("nodes-page-split", dark = false, whole = true, engine = ::splitEngine) { Page(it) }

    /** The nodes' details could not be read: the banner with 重試, and dashes for the facts the core did not send. */
    @Test
    @Config(qualifiers = WHOLE_DETAIL_BANNER)
    fun detailNoReadLight() = shot("node-detail-noread", dark = false, whole = true, engine = ::noReadEngine) { Detail(it) }

    /** A node the profile lists itself: its page names 組態內 as its source. */
    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailInlineLight() = shot("node-detail-inline", dark = false, whole = true, engine = ::inlineEngine) { NodeRoute(it, INLINE_ID, onBack = {}) }

    /** A search that matches no node: the chips, then 找不到符合的節點 where the list would start. */
    @Test
    fun searchNoMatchLight() = shot("node-search-nomatch", dark = false, act = { compose.onNode(hasSetTextAction()).performTextInput("xyz") }) {
        NodeSearchRoute(it, onClose = {}, onDetail = {}, onAdd = {})
    }

    /** No node at all: the pull-down's picker shows the empty state with 新增訂閱, not chips over nothing. */
    @Test fun pickerEmptyLight() = shot("node-picker-empty", dark = false, engine = ::emptyEngine) { Picker(it) }

    @Test fun pickerLight() = shot("node-picker", dark = false) { Picker(it) }

    @Test fun pickerDark() = shot("node-picker", dark = true) { Picker(it) }

    @Test fun pickerFs200() = shot("node-picker", fontScale = RV_FS200) { Picker(it) }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun pickerEnLight() = shot("node-picker-en", dark = false) { Picker(it) }

    private fun shot(
        screen: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        whole: Boolean = false,
        engine: () -> Engine = ::rvSampleEngine,
        act: () -> Unit = {},
        content: @Composable (NodesViewModel) -> Unit,
    ) {
        rvAnimatorScale(0f)
        val vm = NodesViewModel(engine(), Dispatchers.Unconfined, CoreNodePlaces, clock = { RvData.nowEpochMs })
        compose.rvContent(dark, fontScale) { content(vm) }
        act()
        if (whole) compose.assertWhole()
        compose.drawFully()
        val variant = when {
            fontScale != 1f -> "fs${(fontScale * PERCENT).toInt()}"
            dark -> "dark"
            else -> "light"
        }
        val name = "app-rv-$screen-$variant"
        compose.rvRowRules(name)
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$name.png")
    }

    @Composable
    private fun Page(vm: NodesViewModel) = NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {})

    /** The node of the prototype's detail golden, found by its name: the engine has its own ids. */
    @Composable
    private fun Detail(vm: NodesViewModel) {
        val engine = remember { rvSampleEngine() }
        val id = remember { detailId(engine) }
        NodeRoute(vm, id, onBack = {})
    }

    @Composable
    private fun Picker(vm: NodesViewModel) =
        NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = {}) {}

    /** The sample with Proxy's UDP on another node than its TCP. */
    private fun splitEngine(): Engine = rvSampleEngine(
        RvSampleNodes.groups.map { g ->
            if (g.id != FakeRuleMode.MAIN_GROUP_ID) g else g.copy(udp = Group.Selection(g.members.first { it.id != g.tcp.selectedId }.id, pinned = true))
        },
    )

    /** The sample with a node of no subscription added to Proxy, as a profile lists one inline. */
    private fun inlineEngine(): Engine = rvSampleEngine(
        RvSampleNodes.groups.map { g -> if (g.id != FakeRuleMode.MAIN_GROUP_ID) g else g.copy(members = g.members + Member(INLINE_ID, "Home 01")) },
    )

    /** The sample whose nodes' details cannot be read. */
    private fun noReadEngine(): Engine = rvSampleEngine().let { sample ->
        object : Engine by sample {
            override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> = Result.failure(EngineException(ErrorCode.Busy))
        }
    }

    /** A profile with no node: an empty Proxy and no subscription. */
    private fun emptyEngine(): Engine = FakeEngine(groups = listOf(Group(FakeRuleMode.MAIN_GROUP_ID, "Proxy", GroupKind.Selector, emptyList(), Group.Selection(null, pinned = false))))

    /** A hand-edited profile without Proxy: the sample's other groups, so the catalog's main group is missing. */
    private fun noMainEngine(): Engine = rvSampleEngine(RvSampleNodes.groups.filterNot { it.id == FakeRuleMode.MAIN_GROUP_ID })

    private fun detailId(engine: Engine): String {
        val name = RvData.nodes.first { it.id == RV_NODES_DETAIL }.name
        return engine.catalog.value!!.groups.flatMap { it.members }.first { it.name == name }.id
    }

    private companion object {
        val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100
        const val WHOLE_PAGE = "+h2893dp"
        const val WHOLE_PAGE_FS200 = "+h4925dp"
        const val WHOLE_PAGE_EN = "en-w412dp-h2950dp-notnight-xhdpi"
        const val WHOLE_DETAIL = "+h1220dp"
        const val WHOLE_DETAIL_BANNER = "+h1360dp"
        const val INLINE_ID = "inline-home-01"
        const val WHOLE_PICKER = "+h4850dp"
        const val WHOLE_DETAIL_FS200 = "+h1900dp"
        const val WHOLE_DETAIL_EN = "en-w412dp-h1220dp-notnight-xhdpi"
    }
}
