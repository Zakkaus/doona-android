package dev.doona.android.feature.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.rememberRvPullState
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RV_NODES_DETAIL
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.CoroutineContext

/**
 * 節點, a node's page and the pull-down's picker as the app draws them: the RV sample as honk serves it, NodesViewModel, the mapper,
 * then the routes (goldens `app-rv-nodes-page-*`, `app-rv-node-detail-*`, `app-rv-node-picker-*`), each in zh light,
 * dark and fs200 and en light, to set beside `proto-rv-nodes-page-*`, `proto-rv-node-detail-*` and `proto-rv-node-picker-*`.
 * The page and the node's page are whole; the picker is its first screen, as its prototype golden. RvData's now is the
 * ViewModel's clock. The `-nomain` goldens are a profile with no main group (no Proxy); `-split` the main group's TCP and
 * UDP on two nodes; `-noread` a failed read of the nodes after a first one; `-inline` a node the profile lists itself.
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
    fun pageNoMainLight() = shot("nodes-page-nomain", dark = false, whole = true, honk = ::noMainHonk) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageNoMainDark() = shot("nodes-page-nomain", dark = true, whole = true, honk = ::noMainHonk) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailNoMainLight() = shot("node-detail-nomain", dark = false, whole = true, honk = ::noMainHonk) { Detail(it) }

    @Test fun pickerNoMainLight() = shot("node-picker-nomain", dark = false, honk = ::noMainHonk) { Picker(it) }

    /** The main group's networks apart: 套用到 over the list, and each network's node checked. */
    @Test fun pickerSplitLight() = shot("node-picker-split", dark = false, honk = ::splitHonk) { Picker(it) }

    /** A node the profile lists itself, in no subscription: the list ends with its 組態內 section. */
    @Test
    @Config(qualifiers = WHOLE_PICKER)
    fun pickerInlineLight() = shot("node-picker-inline", dark = false, whole = true, honk = ::inlineHonk) { Picker(it) }

    /** 節點 with the main group's networks apart: its TCP and its UDP node both carry the check. */
    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageSplitLight() = shot("nodes-page-split", dark = false, whole = true, honk = ::splitHonk) { Page(it) }

    /** A read of the nodes after the first failed: the banner with 重試 over what the first read showed. */
    @Test
    @Config(qualifiers = WHOLE_DETAIL_BANNER)
    fun detailNoReadLight() = shot("node-detail-noread", dark = false, whole = true, act = ::failReads) { Detail(it) }

    /** A node the profile lists itself: its page names 組態內 as its source. */
    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailInlineLight() = shot("node-detail-inline", dark = false, whole = true, honk = ::inlineHonk) { NodeRoute(it, INLINE_ID, onBack = {}) }

    /** A search that matches no node: the chips, then 找不到符合的節點 where the list would start. */
    @Test
    fun searchNoMatchLight() = shot("node-search-nomatch", dark = false, act = { _, _ -> compose.onNode(hasSetTextAction()).performTextInput("xyz") }) {
        NodeSearchRoute(it, onClose = {}, onDetail = {}, onAdd = {}, onAddNode = {})
    }

    /** No node at all: the pull-down's picker shows the empty state with 新增訂閱, not chips over nothing. */
    @Test fun pickerEmptyLight() = shot("node-picker-empty", dark = false, honk = ::emptyHonk) { Picker(it) }

    /** 規則's 主節點 page, whole: the search pill over every node picker's 訂閱 and 排序 chips (ruling q-p3c-mainchips). */
    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun mainNodeLight() = shot("main-node", dark = false, whole = true) { MainNodeRoute(it, onBack = {}, onSearch = {}, onAdd = {}, onAddNode = {}) }

    /** Before the catalog is first read (`-loading`, a cold start or a restore): each page's skeleton, never the empty state. */
    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageLoadingLight() = shot("nodes-page-loading", dark = false, whole = true, io = Unread) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageLoadingDark() = shot("nodes-page-loading", dark = true, whole = true, io = Unread) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_PAGE_FS200)
    fun pageLoadingFs200() = shot("nodes-page-loading", fontScale = RV_FS200, whole = true, io = Unread) { Page(it) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailLoadingLight() = shot("node-detail-loading", dark = false, whole = true, io = Unread) { NodeRoute(it, "n-unread", onBack = {}) }

    @Test fun pickerLoadingLight() = shot("node-picker-loading", dark = false, io = Unread) { Picker(it) }

    /** The node lists' skeleton runs to the window's foot, so these two are their first screen. */
    @Test
    fun mainNodeLoadingLight() = shot("main-node-loading", dark = false, io = Unread) { MainNodeRoute(it, onBack = {}, onSearch = {}, onAdd = {}, onAddNode = {}) }

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
        honk: () -> FakeHonk = HonkFixtures::rvSample,
        io: CoroutineDispatcher = Dispatchers.Unconfined,
        act: (FakeHonk, NodesViewModel) -> Unit = { _, _ -> },
        content: @Composable (NodesViewModel) -> Unit,
    ) {
        rvAnimatorScale(0f)
        val api = honk()
        val vm = NodesViewModel(api, io, CoreNodePlaces, clock = { RvData.nowEpochMs })
        vm.load()
        compose.rvContent(dark, fontScale) { content(vm) }
        act(api, vm)
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
    private fun Page(vm: NodesViewModel) = NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}, onAddNode = {})

    /** The node of the prototype's detail golden, found by its name in honk's nodes. */
    @Composable
    private fun Detail(vm: NodesViewModel) = NodeRoute(vm, DETAIL_ID, onBack = {})

    @Composable
    private fun Picker(vm: NodesViewModel) =
        NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = {}, onAddNode = {}) {}

    /** The sample with Proxy's UDP on its first member, Hong Kong 01, while TCP is on Hong Kong 02. */
    private fun splitHonk(): FakeHonk = HonkFixtures.rvSample().apply {
        val proxy = HonkFixtures.body("groups/rv-sample-g-Proxy")
        on("GET", "/api/v1/groups/g-Proxy", 200, UDP_SELECTION.replace(proxy, "$1\"$HK1\"$2\"$HK1\""))
    }

    /** The sample with a node written into the configuration (honk's inline provider) added to Proxy. */
    private fun inlineHonk(): FakeHonk = HonkFixtures.rvSample().apply {
        val nodes = Json.parseToJsonElement(HonkFixtures.body("nodes/rv-sample")).jsonObject
        val home = Json.parseToJsonElement(
            """{"id":"$INLINE_ID","name":"Home 01","protocol":null,"subscription_tag":null,"provider_id":"inline","group_ids":["g-Proxy"],"health":[]}""",
        )
        on("GET", "/api/v1/nodes", 200, JsonObject(nodes + ("nodes" to JsonArray(nodes.getValue("nodes").jsonArray + home))).toString())
    }

    /** After the first read, the nodes cannot be read: a pick reads again and fails. */
    private fun failReads(api: FakeHonk, vm: NodesViewModel) {
        api.on("PUT", "/api/v1/groups/g-Proxy/selection", 200, "{}")
        api.on("GET", "/api/v1/nodes", 503, "")
        vm.pick(HK2)
        compose.waitForIdle()
    }

    /** A profile with no node: an empty Proxy and only honk's inline provider. */
    private fun emptyHonk(): FakeHonk = HonkFixtures.rvSample().apply {
        on("GET", "/api/v1/nodes", 200, """{"nodes":[],"next_cursor":null}""")
        on("GET", "/api/v1/providers", 200, HonkFixtures.body("providers/empty"))
        on("GET", "/api/v1/groups", 200, """[{"id":"g-Proxy","name":"Proxy"}]""")
        val proxy = Json.parseToJsonElement(HonkFixtures.body("groups/rv-sample-g-Proxy")).jsonObject
        on("GET", "/api/v1/groups/g-Proxy", 200, JsonObject(proxy + ("members" to JsonArray(emptyList()))).toString())
    }

    /** A hand-edited profile without Proxy: the sample's other groups, so the fallback names no group. */
    private fun noMainHonk(): FakeHonk = HonkFixtures.rvSample().apply {
        val groups = Json.parseToJsonElement(HonkFixtures.body("groups/rv-sample")).jsonArray
        on("GET", "/api/v1/groups", 200, JsonArray(groups.filterNot { it.jsonObject.getValue("id").jsonPrimitive.content == "g-Proxy" }).toString())
    }

    /** A dispatcher that never runs what it is given: the first read never ends, as before honk answers. */
    private object Unread : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private companion object {
        val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100
        const val WHOLE_PAGE = "+h2893dp"
        const val WHOLE_PAGE_FS200 = "+h4925dp"
        const val WHOLE_PAGE_EN = "en-w412dp-h2950dp-notnight-xhdpi"
        const val WHOLE_DETAIL = "+h1266dp"
        const val WHOLE_DETAIL_BANNER = "+h1415dp"
        const val INLINE_ID = "inline-home-01"
        const val HK1 = "n-0001938e00000000"
        const val HK2 = "n-0001938f00000000"
        val UDP_SELECTION = Regex("(\"udp\": \\{\\s*\"member_id\": )\"$HK2\"(,\\s*\"resolved_leaf_node_id\": )\"$HK2\"")
        val DETAIL_ID: String = RvData.nodes.first { it.id == RV_NODES_DETAIL }.name.let { name ->
            Json.parseToJsonElement(HonkFixtures.body("nodes/rv-sample")).jsonObject.getValue("nodes").jsonArray
                .first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject.getValue("id").jsonPrimitive.content
        }
        const val WHOLE_PICKER = "+h4850dp"
        const val WHOLE_DETAIL_FS200 = "+h1900dp"
        const val WHOLE_DETAIL_EN = "en-w412dp-h1265dp-notnight-xhdpi"
    }
}
