package dev.doona.android.feature.subscriptions

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.doona.android.R
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.StaticNodeDraft
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvShot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 訂閱's 新增節點 and 刪除節點 as wired (`app-rv-addnode-*`, `app-rv-node-*`; approved panels s1125f-addnode-sheet and
 * -pages, the several-link stages of samples5 round 2): the sample engine with the core's static node commands
 * ([StaticNodesEngine]), through the route, the view model and the page, driven as the user drives them: the card's row
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

    // The card under the subscriptions, before any node is added.
    @Test
    @Config(qualifiers = ZH)
    fun entryLight() = page("addnode-entry") { Subs() }

    @Test
    @Config(qualifiers = ZH)
    fun entryDark() = page("addnode-entry", dark = true) { Subs() }

    @Test
    @Config(qualifiers = ZH)
    fun entryFs200() = page("addnode-entry", fontScale = RV_FS200) { Subs() }

    @Test
    @Config(qualifiers = EN)
    fun entryEn() = page("addnode-entry", english = true) { Subs() }

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

    // The clipboard's link taken: the name the core gives it.
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

    // The core could not be asked: the engine's error under the field, the sheet kept.
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

    // Several links, one of which the core refused when adding although the preview took it: a warning naming both.
    @Test
    @Config(qualifiers = PARTIAL)
    fun partialLight() = page("addnode-partial", { add(MANY) }) { Subs(::refusing) }

    @Test
    @Config(qualifiers = PARTIAL)
    fun partialDark() = page("addnode-partial", { add(MANY) }, dark = true) { Subs(::refusing) }

    @Test
    @Config(qualifiers = PARTIAL_FS200)
    fun partialFs200() = page("addnode-partial", { add(MANY) }, fontScale = RV_FS200) { Subs(::refusing) }

    @Test
    @Config(qualifiers = PARTIAL_EN)
    fun partialEn() = page("addnode-partial", { add(MANY) }, english = true) { Subs(::refusing) }

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

    // The sheet's 刪除 tapped and the core refused: the banner with why, and 重試.
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

    // 新增 tapped while the core takes its time: the field and 新增 are held until it answers (round 2, fix 9).
    @Test
    @Config(qualifiers = ZH)
    fun addingLight() = sheet("addnode-adding", { add(TOKYO) }, build = ::slow)

    @Test
    @Config(qualifiers = ZH)
    fun addingDark() = sheet("addnode-adding", { add(TOKYO) }, dark = true, build = ::slow)

    @Test
    @Config(qualifiers = EN)
    fun addingEn() = sheet("addnode-adding", { add(TOKYO) }, english = true, build = ::slow)

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
        build: () -> StaticNodesEngine = ::engine,
    ) {
        // The sheet's dialog and the menu's popup are windows of their own, which read the font scale from the
        // configuration, as on a phone, not from the page's composition.
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        compose.rvShot(name, dark, before = {
            before()
            waitForIdle()
        }, fontScale = fontScale, prefix = APP, english = english, windows = true) { Subs(build) }
    }

    @Composable
    private fun Subs(build: () -> StaticNodesEngine = ::engine) {
        val engine = remember { build() }
        SubscriptionsRoute(engine, onBack = {}, onOpen = {}, onAdd = {}, clock = { RvData.nowEpochMs })
    }

    /** The card's 新增節點 tapped, the clipboard holding [clip]. */
    private fun ComposeContentTestRule.open(clip: String?) {
        clipboard().apply { if (clip == null) clearPrimaryClip() else setPrimaryClip(ClipData.newPlainText("link", clip)) }
        onAllNodesWithText(string(R.string.rv_addnode)).onFirst().performClick()
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

    /** [clip] pasted and added. */
    private fun ComposeContentTestRule.add(clip: String) {
        paste(clip)
        onNodeWithText(string(R.string.rv_add_import)).performClick()
        waitForIdle()
    }

    /** The link pasted while the core answers, then 新增 tapped as it stops answering. */
    private fun ComposeContentTestRule.failAdd() {
        paste(TOKYO)
        failingEngine?.failing = ErrorCode.Internal
        onNodeWithText(string(R.string.rv_add_import)).performClick()
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

    /** The engine of [failAdd], kept to make it fail after the preview. */
    private var failingEngine: StaticNodesEngine? = null

    private fun failing() = engine().also { failingEngine = it }

    /** An add that never answers. */
    private fun slow() = engine().apply { addGate = CompletableDeferred() }

    /** The add refuses Singapore although the preview took it. */
    private fun refusing() = engine().apply { refused += SINGAPORE }

    /** 東京 03 already added; with [fails] the core refuses to delete it. */
    private fun seeded(fails: Boolean = false) = engine().apply {
        runBlocking { addStaticNodes(listOf(StaticNodeDraft.Link(TOKYO))) }
        if (fails) deleteFailing = ErrorCode.Internal
    }

    private companion object {
        const val APP = "app-rv"
        const val ZH = "zh-rTW-w412dp-h915dp-notnight-xhdpi"
        const val ZH_927 = "zh-rTW-w412dp-h927dp-notnight-xhdpi"
        const val EN = "en-w412dp-h915dp-notnight-xhdpi"
        const val ADDED = "zh-rTW-w412dp-h984dp-notnight-xhdpi"
        const val ADDED_FS200 = "zh-rTW-w412dp-h1324dp-notnight-xhdpi"
        const val ADDED_EN = "en-w412dp-h1001dp-notnight-xhdpi"
        const val MANY_ADDED = "zh-rTW-w412dp-h1148dp-notnight-xhdpi"
        const val MANY_ADDED_FS200 = "zh-rTW-w412dp-h1577dp-notnight-xhdpi"
        const val MANY_ADDED_EN = "en-w412dp-h1147dp-notnight-xhdpi"
        const val PARTIAL = "zh-rTW-w412dp-h1066dp-notnight-xhdpi"
        const val PARTIAL_FS200 = "zh-rTW-w412dp-h1514dp-notnight-xhdpi"
        const val PARTIAL_EN = "en-w412dp-h1083dp-notnight-xhdpi"
        const val DELETE_FAILED = "zh-rTW-w412dp-h960dp-notnight-xhdpi"
        const val DELETE_FAILED_FS200 = "zh-rTW-w412dp-h1242dp-notnight-xhdpi"
        const val DELETE_FAILED_EN = "en-w412dp-h959dp-notnight-xhdpi"

        const val TOKYO_NAME = "東京 03"
        const val TOKYO = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.24:443?security=reality&type=tcp#%E6%9D%B1%E4%BA%AC%2003"
        const val SINGAPORE = "trojan://sample-pass@203.0.113.40:443?sni=sg.example.net#%E6%96%B0%E5%8A%A0%E5%9D%A1%2005"
        const val FRANKFURT = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@192.0.2.61:443?security=tls#%E6%B3%95%E8%98%AD%E5%85%8B%E7%A6%8F%2001"
        const val NOT_A_LINK = "203.0.113.24:443"

        /** samples5 round 2's paste: Shadowsocks 東京 03, Trojan 新加坡 05, VLESS 法蘭克福 01, then a line that is no link. */
        const val MANY = "ss://YWVzLTI1Ni1nY206c2FtcGxlLXBhc3M@198.51.100.7:8388#%E6%9D%B1%E4%BA%AC%2003\n$SINGAPORE\n$FRANKFURT\n$NOT_A_LINK"

        /** Two lines past the cap, every one a link of its own. */
        val OVER_CAP = (1..ADD_NODE_LINES + 2).joinToString("\n") { "vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.24:${1000 + it}?security=tls#%E6%9D%B1%E4%BA%AC%20$it" }

        /** The regions the core reads from these names. */
        val REGIONS = mapOf("東京" to "JP", "新加坡" to "SG", "法蘭克福" to "DE")

        fun engine() = StaticNodesEngine(rvSampleEngine()) { name -> REGIONS.entries.firstOrNull { name.startsWith(it.key) }?.value }

        fun clipboard(): ClipboardManager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)

        fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
    }
}
