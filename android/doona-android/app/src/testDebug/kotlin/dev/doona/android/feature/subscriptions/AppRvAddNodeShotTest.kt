package dev.doona.android.feature.subscriptions

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.CoroutineContext

/**
 * 訂閱's 新增節點 and 刪除節點 as wired (`app-rv-addnode-*`, `app-rv-node-*`; approved panels s1125f-addnode-sheet and
 * -pages, the several-link stages of samples5 round 2): honk's sample profile with its inline nodes ([InlineHonk]),
 * through the route, the view model and the page, driven as the user drives them: the card's row
 * tapped, the clipboard's row tapped, a line typed, 新增 tapped; a node's row held, 刪除節點 picked, the sheet's 刪除
 * tapped. The sheets and the menu are windows of their own, so those goldens capture every window, at the phone's size.
 * Window heights are each page's whole height, measured.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppRvAddNodeShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    // 訂閱's one 新增, its menu of 訂閱 and 節點 open.
    @Test
    @Config(qualifiers = ZH)
    fun entryLight() = sheet("addnode-entry", { menu() })

    @Test
    @Config(qualifiers = ZH)
    fun entryDark() = sheet("addnode-entry", { menu() }, dark = true)

    @Test
    @Config(qualifiers = ZH_927)
    fun entryFs200() = sheet("addnode-entry", { menu() }, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = EN)
    fun entryEn() = sheet("addnode-entry", { menu() }, english = true)

    // The sheet, empty, the clipboard holding a link.
    @Test
    @Config(qualifiers = ZH)
    fun clipboardLight() = sheet("addnode-clipboard", { open(TOKYO) })

    @Test
    @Config(qualifiers = ZH)
    fun clipboardDark() = sheet("addnode-clipboard", { open(TOKYO) }, dark = true)

    @Test
    @Config(qualifiers = ZH_927)
    fun clipboardFs200() = sheet("addnode-clipboard", { open(TOKYO) }, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = EN)
    fun clipboardEn() = sheet("addnode-clipboard", { open(TOKYO) }, english = true)

    // The clipboard's link taken: the name it is filed under.
    @Test
    @Config(qualifiers = ZH)
    fun readyLight() = sheet("addnode-ready", { paste(TOKYO) })

    @Test
    @Config(qualifiers = ZH)
    fun readyDark() = sheet("addnode-ready", { paste(TOKYO) }, dark = true)

    @Test
    @Config(qualifiers = ZH_927)
    fun readyFs200() = sheet("addnode-ready", { paste(TOKYO) }, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = EN)
    fun readyEn() = sheet("addnode-ready", { paste(TOKYO) }, english = true)

    // 加入群組 (new, for the owner's review): honk's groups under the link, HK checked; Proxy and Auto take every node
    // and are held checked, US is in a file honk cannot write.
    @Test
    @Config(qualifiers = ZH)
    fun groupsLight() = sheet("addnode-groups", { pickGroup(TOKYO, "HK") })

    @Test
    @Config(qualifiers = ZH)
    fun groupsDark() = sheet("addnode-groups", { pickGroup(TOKYO, "HK") }, dark = true)

    @Test
    @Config(qualifiers = ZH_927)
    fun groupsFs200() = sheet("addnode-groups", { pickGroup(TOKYO, "HK") }, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = EN)
    fun groupsEn() = sheet("addnode-groups", { pickGroup(TOKYO, "HK") }, english = true)

    // Eight groups run the sheet past the window: 新增 and 取消 are reached by scrolling, and 新增 writes HK's file alone.
    @Test
    @Config(qualifiers = ZH)
    fun groupsScrollToTheActions() {
        val honk = honk()
        compose.rvContent(dark = false) { Subs(build = { honk }) }
        compose.pickGroup(TOKYO, "HK")
        compose.onNodeWithText(string(R.string.rv_cancel)).assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.rv_add_import)).onLast().performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf("source-regions"), honk.writes.map { it.first })
    }

    // A line that is no share link typed: the field's error, 新增 held. The field has the focus the typing gave it.
    @Test
    @Config(qualifiers = ZH)
    fun invalidLight() = sheet("addnode-invalid", { type(NOT_A_LINK) })

    @Test
    @Config(qualifiers = ZH)
    fun invalidDark() = sheet("addnode-invalid", { type(NOT_A_LINK) }, dark = true)

    @Test
    @Config(qualifiers = ZH_927)
    fun invalidFs200() = sheet("addnode-invalid", { type(NOT_A_LINK) }, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = EN)
    fun invalidEn() = sheet("addnode-invalid", { type(NOT_A_LINK) }, english = true)

    // Several links pasted, one per line: the count, and the line skipped.
    @Test
    @Config(qualifiers = ZH)
    fun manyReadyLight() = sheet("addnode-many-ready", { paste(MANY) })

    @Test
    @Config(qualifiers = ZH)
    fun manyReadyDark() = sheet("addnode-many-ready", { paste(MANY) }, dark = true)

    @Test
    @Config(qualifiers = ZH)
    fun manyReadyFs200() = sheet("addnode-many-ready", { paste(MANY) }, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = EN)
    fun manyReadyEn() = sheet("addnode-many-ready", { paste(MANY) }, english = true)

    // honk could not take the add: its error under the field, the sheet kept.
    @Test
    @Config(qualifiers = ZH)
    fun errorLight() = sheet("addnode-error", { failAdd() }) { failing() }

    @Test
    @Config(qualifiers = ZH)
    fun errorDark() = sheet("addnode-error", { failAdd() }, dark = true) { failing() }

    @Test
    @Config(qualifiers = ZH_927)
    fun errorFs200() = sheet("addnode-error", { failAdd() }, fontScale = RV_FS200) { failing() }

    @Test
    @Config(qualifiers = EN)
    fun errorEn() = sheet("addnode-error", { failAdd() }, english = true) { failing() }

    // One link added: the banner naming the groups that took it, the 組態內 card listing it.
    @Test
    @Config(qualifiers = ADDED)
    fun addedLight() = page("addnode-added", { add(TOKYO) }) { Subs() }

    @Test
    @Config(qualifiers = ADDED)
    fun addedDark() = page("addnode-added", { add(TOKYO) }, dark = true) { Subs() }

    @Test
    @Config(qualifiers = ADDED_FS200)
    fun addedFs200() = page("addnode-added", { add(TOKYO) }, fontScale = RV_FS200) { Subs() }

    @Test
    @Config(qualifiers = ADDED_EN)
    fun addedEn() = page("addnode-added", { add(TOKYO) }, english = true) { Subs() }

    // Several links added.
    @Test
    @Config(qualifiers = MANY_ADDED)
    fun manyAddedLight() = page("addnode-many-added", { add(MANY) }) { Subs() }

    @Test
    @Config(qualifiers = MANY_ADDED)
    fun manyAddedDark() = page("addnode-many-added", { add(MANY) }, dark = true) { Subs() }

    @Test
    @Config(qualifiers = MANY_ADDED_FS200)
    fun manyAddedFs200() = page("addnode-many-added", { add(MANY) }, fontScale = RV_FS200) { Subs() }

    @Test
    @Config(qualifiers = MANY_ADDED_EN)
    fun manyAddedEn() = page("addnode-many-added", { add(MANY) }, english = true) { Subs() }

    // Several links, one of which honk refused when adding although the sheet took it: a warning naming both.
    @Test
    @Config(qualifiers = PARTIAL)
    fun partialLight() = page("addnode-partial", { add(MANY) }) { Subs(build = ::refusing) }

    @Test
    @Config(qualifiers = PARTIAL)
    fun partialDark() = page("addnode-partial", { add(MANY) }, dark = true) { Subs(build = ::refusing) }

    @Test
    @Config(qualifiers = PARTIAL_FS200)
    fun partialFs200() = page("addnode-partial", { add(MANY) }, fontScale = RV_FS200) { Subs(build = ::refusing) }

    @Test
    @Config(qualifiers = PARTIAL_EN)
    fun partialEn() = page("addnode-partial", { add(MANY) }, english = true) { Subs(build = ::refusing) }

    // 東京 03 held: the node menu, 刪除節點 set apart at its end.
    @Test
    @Config(qualifiers = ZH)
    fun menuLight() = sheet("node-menu", { hold() }) { seeded() }

    @Test
    @Config(qualifiers = ZH)
    fun menuDark() = sheet("node-menu", { hold() }, dark = true) { seeded() }

    @Test
    @Config(qualifiers = ZH)
    fun menuFs200() = sheet("node-menu", { hold() }, fontScale = RV_FS200) { seeded() }

    @Test
    @Config(qualifiers = EN)
    fun menuEn() = sheet("node-menu", { hold() }, english = true) { seeded() }

    // 刪除節點 picked: the question.
    @Test
    @Config(qualifiers = ZH)
    fun deleteLight() = sheet("node-delete", { pickDelete() }) { seeded() }

    @Test
    @Config(qualifiers = ZH)
    fun deleteDark() = sheet("node-delete", { pickDelete() }, dark = true) { seeded() }

    @Test
    @Config(qualifiers = ZH)
    fun deleteFs200() = sheet("node-delete", { pickDelete() }, fontScale = RV_FS200) { seeded() }

    @Test
    @Config(qualifiers = EN)
    fun deleteEn() = sheet("node-delete", { pickDelete() }, english = true) { seeded() }

    // The sheet's 刪除 tapped and honk refused: the banner with why, and 重試.
    @Test
    @Config(qualifiers = DELETE_FAILED)
    fun deleteFailedLight() = page("node-delete-failed", { confirmDelete() }) { Subs { seeded(fails = true) } }

    @Test
    @Config(qualifiers = DELETE_FAILED)
    fun deleteFailedDark() = page("node-delete-failed", { confirmDelete() }, dark = true) { Subs { seeded(fails = true) } }

    @Test
    @Config(qualifiers = DELETE_FAILED_FS200)
    fun deleteFailedFs200() = page("node-delete-failed", { confirmDelete() }, fontScale = RV_FS200) { Subs { seeded(fails = true) } }

    @Test
    @Config(qualifiers = DELETE_FAILED_EN)
    fun deleteFailedEn() = page("node-delete-failed", { confirmDelete() }, english = true) { Subs { seeded(fails = true) } }

    // More lines than one add takes: the count, and how many lines past the cap are not read (round 2, fix 7).
    @Test
    @Config(qualifiers = ZH)
    fun unreadLight() = sheet("addnode-unread", { paste(OVER_CAP) })

    @Test
    @Config(qualifiers = ZH)
    fun unreadDark() = sheet("addnode-unread", { paste(OVER_CAP) }, dark = true)

    @Test
    @Config(qualifiers = ZH_927)
    fun unreadFs200() = sheet("addnode-unread", { paste(OVER_CAP) }, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = EN)
    fun unreadEn() = sheet("addnode-unread", { paste(OVER_CAP) }, english = true)

    // 新增 tapped while honk takes its time: the field and 新增 are held until it answers (round 2, fix 9).
    @Test
    @Config(qualifiers = ZH)
    fun addingLight() = sheet("addnode-adding", { add(TOKYO) }, io = Never)

    @Test
    @Config(qualifiers = ZH)
    fun addingDark() = sheet("addnode-adding", { add(TOKYO) }, dark = true, io = Never)

    @Test
    @Config(qualifiers = EN)
    fun addingEn() = sheet("addnode-adding", { add(TOKYO) }, english = true, io = Never)

    /** A whole page: its own window, every row inside it. */
    private fun page(
        name: String,
        before: ComposeContentTestRule.() -> Unit = {},
        dark: Boolean = false,
        fontScale: Float = 1f,
        english: Boolean = false,
        content: @Composable () -> Unit,
    ) = compose.rvShot(name, dark, before = {
        before()
        waitForIdle()
        assertWhole()
    }, fontScale = fontScale, prefix = APP, english = english, content = content)

    /** A sheet or a menu over the page: every window, at the phone's size. */
    private fun sheet(
        name: String,
        before: ComposeContentTestRule.() -> Unit,
        dark: Boolean = false,
        fontScale: Float = 1f,
        english: Boolean = false,
        io: CoroutineDispatcher = Dispatchers.Unconfined,
        build: () -> InlineHonk = ::honk,
    ) {
        // The sheet's dialog and the menu's popup are windows of their own, which read the font scale from the
        // configuration, as on a phone, not from the page's composition.
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        compose.rvShot(name, dark, before = {
            before()
            waitForIdle()
        }, fontScale = fontScale, prefix = APP, english = english, windows = true) { Subs(io, build) }
    }

    /** 訂閱 on [build]'s honk, the 組態內 card reading it on [io]. */
    @Composable
    private fun Subs(io: CoroutineDispatcher = Dispatchers.Unconfined, build: () -> InlineHonk = ::honk) {
        val honk = remember { build() }
        val subscriptions = remember { SubscriptionsViewModel(honk, clock = { RvData.nowEpochMs }, io = Dispatchers.Unconfined) }
        val nodes = remember { AddNodeViewModel(honk, AndroidClipboardLinks(clipboard()), io) }
        SubscriptionsRoute(onBack = {}, onOpen = {}, onAdd = {}, clock = { RvData.nowEpochMs }, viewModel = subscriptions, nodes = nodes)
    }

    /** The page's 新增 tapped: its menu of 訂閱 and 節點. */
    private fun ComposeContentTestRule.menu() {
        onNodeWithText(string(R.string.rv_list_create)).performClick()
        waitForIdle()
    }

    /** 新增's 節點 picked, the clipboard holding [clip]. */
    private fun ComposeContentTestRule.open(clip: String?) {
        clipboard().apply { if (clip == null) clearPrimaryClip() else setPrimaryClip(ClipData.newPlainText("link", clip)) }
        menu()
        onNodeWithText(string(R.string.rv_subs_add_node)).performClick()
        waitForIdle()
    }

    /** The sheet opened with [clip] on the clipboard, and its row tapped. */
    private fun ComposeContentTestRule.paste(clip: String) {
        open(clip)
        onNodeWithText(string(R.string.rv_add_clipboard)).performClick()
        waitForIdle()
    }

    /** The sheet opened with nothing on the clipboard, and [text] typed into the field. */
    private fun ComposeContentTestRule.type(text: String) {
        open(null)
        onNode(hasSetTextAction()).performTextReplacement(text)
        waitForIdle()
    }

    /** [clip] pasted and [group] checked in 加入群組. */
    private fun ComposeContentTestRule.pickGroup(clip: String, group: String) {
        paste(clip)
        tap(group)
    }

    /** [clip] pasted and added. */
    private fun ComposeContentTestRule.add(clip: String) {
        paste(clip)
        tapAdd()
    }

    /** The link pasted, then 新增 tapped as honk fails every add. */
    private fun ComposeContentTestRule.failAdd() {
        paste(TOKYO)
        failingHonk?.addStatus = 500
        tapAdd()
    }

    private fun ComposeContentTestRule.tapAdd() = tap(string(R.string.rv_add_import))

    /**
     * [text] tapped, scrolled to first when off the window: 加入群組 runs the sheet past a phone's window. The sheet's
     * window is the last, so its 新增 is the last of the page's and its own.
     */
    private fun ComposeContentTestRule.tap(text: String) {
        val node = onAllNodesWithText(text).onLast()
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
        waitForIdle()
    }

    private fun ComposeContentTestRule.hold() {
        onNodeWithText(TOKYO_NAME).performTouchInput { longClick() }
        waitForIdle()
    }

    private fun ComposeContentTestRule.pickDelete() {
        hold()
        onNodeWithText(string(R.string.rv_node_delete)).performClick()
        waitForIdle()
    }

    private fun ComposeContentTestRule.confirmDelete() {
        pickDelete()
        onNodeWithText(string(R.string.rv_menu_delete)).performClick()
        waitForIdle()
    }

    /** The honk of [failAdd], kept to make it fail once the link is in the field. */
    private var failingHonk: InlineHonk? = null

    private fun failing() = honk().also { failingHonk = it }

    /** honk refuses Singapore's link although the sheet took it. */
    private fun refusing() = honk().apply { refused += SINGAPORE }

    /** 東京 03 already added; with [fails] honk refuses to delete it. */
    private fun seeded(fails: Boolean = false) = honk().apply {
        add(TOKYO_NAME, TOKYO)
        if (fails) deleteStatus = 500
    }

    /** A dispatcher that never runs what it is given: honk's answer never comes. */
    private object Never : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private companion object {
        const val APP = "app-rv"
        const val ZH = "zh-rTW-w412dp-h915dp-notnight-xhdpi"
        const val ZH_927 = "zh-rTW-w412dp-h927dp-notnight-xhdpi"
        const val EN = "en-w412dp-h915dp-notnight-xhdpi"
        const val ADDED = "zh-rTW-w412dp-h1002dp-notnight-xhdpi"
        const val ADDED_FS200 = "zh-rTW-w412dp-h1383dp-notnight-xhdpi"
        const val ADDED_EN = "en-w412dp-h1001dp-notnight-xhdpi"
        const val MANY_ADDED = "zh-rTW-w412dp-h1166dp-notnight-xhdpi"
        const val MANY_ADDED_FS200 = "zh-rTW-w412dp-h1637dp-notnight-xhdpi"
        const val MANY_ADDED_EN = "en-w412dp-h1165dp-notnight-xhdpi"
        const val PARTIAL = "zh-rTW-w412dp-h1084dp-notnight-xhdpi"
        const val PARTIAL_FS200 = "zh-rTW-w412dp-h1573dp-notnight-xhdpi"
        const val PARTIAL_EN = "en-w412dp-h1083dp-notnight-xhdpi"
        const val DELETE_FAILED = "zh-rTW-w412dp-h978dp-notnight-xhdpi"
        const val DELETE_FAILED_FS200 = "zh-rTW-w412dp-h1301dp-notnight-xhdpi"
        const val DELETE_FAILED_EN = "en-w412dp-h977dp-notnight-xhdpi"

        const val TOKYO_NAME = "東京 03"
        const val TOKYO = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.24:443?security=reality&type=tcp#%E6%9D%B1%E4%BA%AC%2003"
        const val SINGAPORE = "trojan://sample-pass@203.0.113.40:443?sni=sg.example.net#%E6%96%B0%E5%8A%A0%E5%9D%A1%2005"
        const val FRANKFURT = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@192.0.2.61:443?security=tls#%E6%B3%95%E8%98%AD%E5%85%8B%E7%A6%8F%2001"
        const val NOT_A_LINK = "203.0.113.24:443"

        /** samples5 round 2's paste: Shadowsocks 東京 03, Trojan 新加坡 05, VLESS 法蘭克福 01, then a line that is no link. */
        const val MANY = "ss://YWVzLTI1Ni1nY206c2FtcGxlLXBhc3M@198.51.100.7:8388#%E6%9D%B1%E4%BA%AC%2003\n$SINGAPORE\n$FRANKFURT\n$NOT_A_LINK"

        /** Two lines past the cap, every one a link of its own. */
        val OVER_CAP = (1..ADD_NODE_LINES + 2).joinToString("\n") { "vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.24:${1000 + it}?security=tls#%E6%9D%B1%E4%BA%AC%20$it" }

        /** The regions these names give, whose groups take them. */
        val REGIONS = mapOf("東京" to "JP", "新加坡" to "SG", "法蘭克福" to "DE")

        fun honk() = InlineHonk { name -> REGIONS.entries.firstOrNull { name.startsWith(it.key) }?.value }

        fun clipboard(): ClipboardManager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)

        fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
    }
}
