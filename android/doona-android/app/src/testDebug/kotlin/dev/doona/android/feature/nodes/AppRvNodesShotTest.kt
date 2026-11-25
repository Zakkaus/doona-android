package dev.doona.android.feature.nodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.rememberRvPullState
import dev.doona.android.engine.Engine
import dev.doona.android.engine.fake.FakeRuleMode
import dev.doona.android.engine.fake.RvSampleNodes
import dev.doona.android.engine.fake.rvSampleEngine
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
 * ViewModel's clock. The `-nomain` goldens are a profile with no main group (no Proxy).
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

    @Test fun pickerLight() = shot("node-picker", dark = false) { Picker(it) }

    @Test fun pickerDark() = shot("node-picker", dark = true) { Picker(it) }

    @Test fun pickerFs200() = shot("node-picker", fontScale = RV_FS200) { Picker(it) }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun pickerEnLight() = shot("node-picker-en", dark = false) { Picker(it) }

    private fun shot(screen: String, dark: Boolean = false, fontScale: Float = 1f, whole: Boolean = false, engine: () -> Engine = ::rvSampleEngine, content: @Composable (NodesViewModel) -> Unit) {
        rvAnimatorScale(0f)
        val vm = NodesViewModel(engine(), Dispatchers.Unconfined, CoreNodePlaces, clock = { RvData.nowEpochMs })
        compose.rvContent(dark, fontScale) { content(vm) }
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
        NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}) {}

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
        const val WHOLE_DETAIL_FS200 = "+h1900dp"
        const val WHOLE_DETAIL_EN = "en-w412dp-h1220dp-notnight-xhdpi"
    }
}
