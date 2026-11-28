package dev.doona.android.feature.subscriptions

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.localnetwork.LocalNetworkAccess
import dev.doona.android.feature.localnetwork.LocalNetworkHost
import dev.doona.android.feature.localnetwork.LocalNetworkPrompts
import dev.doona.android.feature.onboarding.FirstRunGate
import dev.doona.android.feature.onboarding.FirstRunViewModel
import dev.doona.android.feature.reasonRes
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.gallery.RV_SAMPLE_LINK
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.subs.RV_LAN_LINK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wired subscription pages and the welcome page (`app-rv-<screen>-*`, plan §2.4): honk's RV sample profile
 * ([HonkFixtures.rvSample]) through each page's route, view model and mapper, in zh light, dark, fs200 and en light,
 * each set against its `proto-rv` golden. 新增訂閱 is driven as the user drives it: the name typed and the keyboard put
 * away, the link taken from the clipboard's row, 新增 tapped. honk answers each add (Harbor Cloud as it is in the
 * sample, or the failure under test), or never, for the import in flight. The sheets and the long-press menu open as
 * the user opens them, in their own windows, so those goldens capture every window (light and dark, as their approved
 * goldens). The welcome page and the 組態內 card still read the engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvSubsShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun subsLight() = shot("subs") { Subs() }

    @Test fun subsDark() = shot("subs", dark = true) { Subs() }

    @Test fun subsFs200() = shot("subs", fontScale = RV_FS200, whole = false) { Subs() }

    @Test
    @Config(qualifiers = RV_EN)
    fun subsEn() = shot("subs", english = true) { Subs() }

    @Test fun subsEmptyLight() = shot("subs-empty") { SubsEmpty() }

    @Test fun subsEmptyDark() = shot("subs-empty", dark = true) { SubsEmpty() }

    @Test fun subsEmptyFs200() = shot("subs-empty", fontScale = RV_FS200, whole = false) { SubsEmpty() }

    @Test
    @Config(qualifiers = RV_EN)
    fun subsEmptyEn() = shot("subs-empty", english = true) { SubsEmpty() }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailLight() = shot("sub-detail") { Detail() }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailDark() = shot("sub-detail", dark = true) { Detail() }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailFs200() = shot("sub-detail", fontScale = RV_FS200, whole = false) { Detail() }

    /** English runs half a dp past the zh page's window: the phone's height, as the variants without a whole capture. */
    @Test
    @Config(qualifiers = RV_EN)
    fun detailEn() = shot("sub-detail", english = true, whole = false) { Detail() }

    /** Auto-update off in the settings: the interval row reads 關閉 (orchestrator ruling 2026-11-26). */
    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailOffLight() = shot("sub-detail-off") { Detail(OFF) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailOffDark() = shot("sub-detail-off", dark = true) { Detail(OFF) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailOffFs200() = shot("sub-detail-off", fontScale = RV_FS200, whole = false) { Detail(OFF) }

    @Test
    @Config(qualifiers = RV_EN)
    fun detailOffEn() = shot("sub-detail-off", english = true, whole = false) { Detail(OFF) }

    @Test fun addLight() = add("sub-add", Answer.None, typed = false)

    @Test fun addDark() = add("sub-add", Answer.None, typed = false, dark = true)

    @Test fun addFs200() = add("sub-add", Answer.None, typed = false, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun addEn() = add("sub-add", Answer.None, typed = false, english = true)

    @Test fun importingLight() = add("sub-adding-unknown", Answer.Never, busyFrames = BUSY_FRAMES)

    @Test fun importingDark() = add("sub-adding-unknown", Answer.Never, dark = true, busyFrames = BUSY_FRAMES)

    @Test fun importingFs200() = add("sub-adding-unknown", Answer.Never, fontScale = RV_FS200, busyFrames = BUSY_FRAMES)

    @Test
    @Config(qualifiers = RV_EN)
    fun importingEn() = add("sub-adding-unknown", Answer.Never, english = true, busyFrames = BUSY_FRAMES)

    @Test fun doneLight() = add("sub-add-done", Answer.Harbor)

    @Test fun doneDark() = add("sub-add-done", Answer.Harbor, dark = true)

    @Test fun doneFs200() = add("sub-add-done", Answer.Harbor, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun doneEn() = add("sub-add-done", Answer.Harbor, english = true)

    @Test fun unreachableLight() = add("sub-add-unreachable", Answer.Fails(ErrorCode.FetchFailed))

    @Test fun unreachableDark() = add("sub-add-unreachable", Answer.Fails(ErrorCode.FetchFailed), dark = true)

    @Test fun unreachableFs200() = add("sub-add-unreachable", Answer.Fails(ErrorCode.FetchFailed), fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun unreachableEn() = add("sub-add-unreachable", Answer.Fails(ErrorCode.FetchFailed), english = true)

    @Test fun notSubscriptionLight() = add("sub-add-not-subscription", Answer.Fails(ErrorCode.ParseFailed))

    @Test fun notSubscriptionDark() = add("sub-add-not-subscription", Answer.Fails(ErrorCode.ParseFailed), dark = true)

    @Test fun notSubscriptionFs200() = add("sub-add-not-subscription", Answer.Fails(ErrorCode.ParseFailed), fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun notSubscriptionEn() = add("sub-add-not-subscription", Answer.Fails(ErrorCode.ParseFailed), english = true)

    @Test fun tooLargeLight() = add("sub-add-too-large", Answer.Fails(ErrorCode.TooLarge))

    @Test fun tooLargeDark() = add("sub-add-too-large", Answer.Fails(ErrorCode.TooLarge), dark = true)

    @Test fun tooLargeFs200() = add("sub-add-too-large", Answer.Fails(ErrorCode.TooLarge), fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun tooLargeEn() = add("sub-add-too-large", Answer.Fails(ErrorCode.TooLarge), english = true)

    /** Harbor Cloud's last fetch failed: the banner over the cards, as honk reports it in the provider's `last_error`. */
    @Test fun failedLight() = shot("subs-failed") { Subs { failedHonk() } }

    @Test fun failedDark() = shot("subs-failed", dark = true) { Subs { failedHonk() } }

    /**
     * Harbor Cloud deleted from its menu and honk's delete failed, as its configuration store could not be written
     * (`store_unavailable`, honk's nearest to the core's `write_failed`): the delete-failure banner with its reason, the
     * update failure's (orchestrator ruling 12, 2026-11-26; the reason, p5c 2026-11-26). No approved golden yet; the
     * owner sees these first. honk runs the delete in the background, so the banner comes after its first poll.
     */
    @Test fun deleteFailedLight() = shot("subs-delete-failed", before = { confirmDeleteFromMenu(ErrorCode.WriteFailed) }) { Subs { deleteFails() } }

    @Test fun deleteFailedDark() = shot("subs-delete-failed", dark = true, before = { confirmDeleteFromMenu(ErrorCode.WriteFailed) }) { Subs { deleteFails() } }

    @Test
    fun deleteFailedFs200() = shot("subs-delete-failed", fontScale = RV_FS200, whole = false, before = { confirmDeleteFromMenu(ErrorCode.WriteFailed) }) { Subs { deleteFails() } }

    @Test
    @Config(qualifiers = RV_EN)
    fun deleteFailedEn() = shot("subs-delete-failed", english = true, before = { confirmDeleteFromMenu(ErrorCode.WriteFailed) }) { Subs { deleteFails() } }

    /** The same refused as honk cannot take the request now (503 `temporarily_unavailable`); honk has no `storage_full`. */
    @Test fun deleteFullLight() = shot("subs-delete-full", before = { confirmDeleteFromMenu() }) { Subs { deleteRefused() } }

    @Test
    fun deleteFullFs200() = shot("subs-delete-full", fontScale = RV_FS200, whole = false, before = { confirmDeleteFromMenu() }) { Subs { deleteRefused() } }

    @Test
    @Config(qualifiers = RV_EN)
    fun deleteFullEn() = shot("subs-delete-full", english = true, before = { confirmDeleteFromMenu() }) { Subs { deleteRefused() } }

    /** The same from Harbor Cloud's page: its 刪除訂閱 row, then the sheet's 刪除 (`store_unavailable`, then a 503). */
    @Test
    @Config(qualifiers = WHOLE_DETAIL_BANNER)
    fun detailDeleteFailedLight() = shot("sub-detail-delete-failed", before = { confirmDeleteFromPage(ErrorCode.WriteFailed) }) { Detail { deleteFails() } }

    @Test
    @Config(qualifiers = WHOLE_DETAIL_BANNER)
    fun detailDeleteFailedDark() = shot("sub-detail-delete-failed", dark = true, before = { confirmDeleteFromPage(ErrorCode.WriteFailed) }) { Detail { deleteFails() } }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailDeleteFailedFs200() = shot("sub-detail-delete-failed", fontScale = RV_FS200, whole = false, before = { confirmDeleteFromPage(ErrorCode.WriteFailed) }) { Detail { deleteFails() } }

    @Test
    @Config(qualifiers = RV_EN)
    fun detailDeleteFailedEn() = shot("sub-detail-delete-failed", english = true, whole = false, before = { confirmDeleteFromPage(ErrorCode.WriteFailed) }) { Detail { deleteFails() } }

    @Test
    @Config(qualifiers = WHOLE_DETAIL_BANNER)
    fun detailDeleteFullLight() = shot("sub-detail-delete-full", before = { confirmDeleteFromPage() }) { Detail { deleteRefused() } }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailDeleteFullFs200() = shot("sub-detail-delete-full", fontScale = RV_FS200, whole = false, before = { confirmDeleteFromPage() }) { Detail { deleteRefused() } }

    @Test
    @Config(qualifiers = RV_EN)
    fun detailDeleteFullEn() = shot("sub-detail-delete-full", english = true, whole = false, before = { confirmDeleteFromPage() }) { Detail { deleteRefused() } }

    /** 立即更新 on Harbor Cloud's page while honk cannot take the request (503; honk has no `storage_full`): the banner says why, and its nodes stay. */
    @Test
    @Config(qualifiers = WHOLE_DETAIL_BANNER)
    fun detailUpdateFullLight() = shot("sub-detail-update-full", before = { tap(R.string.rv_sub_update_now) }) { Detail { writeRefused() } }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailUpdateFullFs200() = shot("sub-detail-update-full", fontScale = RV_FS200, whole = false, before = { tap(R.string.rv_sub_update_now) }) { Detail { writeRefused() } }

    @Test
    @Config(qualifiers = RV_EN)
    fun detailUpdateFullEn() = shot("sub-detail-update-full", english = true, whole = false, before = { tap(R.string.rv_sub_update_now) }) { Detail { writeRefused() } }

    /** The User-Agent sheet saved while honk cannot take the file's replacement (503): the banner says why, and the User-Agent stays. */
    @Test
    @Config(qualifiers = WHOLE_DETAIL_BANNER)
    fun detailAgentFullLight() = shot("sub-detail-agent-full", before = { saveSheet(R.string.rv_sub_user_agent) }) { Detail { writeRefused() } }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailAgentFullFs200() = shot("sub-detail-agent-full", fontScale = RV_FS200, whole = false, before = { saveSheet(R.string.rv_sub_user_agent) }) { Detail { writeRefused() } }

    @Test
    @Config(qualifiers = RV_EN)
    fun detailAgentFullEn() = shot("sub-detail-agent-full", english = true, whole = false, before = { saveSheet(R.string.rv_sub_user_agent) }) { Detail { writeRefused() } }

    /** The rename sheet saved while honk cannot take the file's replacement (503): it opens again on the name, why under the field. */
    @Test fun renameFullLight() = shot("sub-detail-rename-full", whole = false, windows = true, before = { saveSheet(R.string.rv_sub_name, waitFor = ErrorCode.Busy) }) { Detail { writeRefused() } }

    @Test
    @Config(qualifiers = RV_EN)
    fun renameFullEn() = shot("sub-detail-rename-full", english = true, whole = false, windows = true, before = { saveSheet(R.string.rv_sub_name, waitFor = ErrorCode.Busy) }) { Detail { writeRefused() } }

    /** Harbor Cloud's card held where the prototype's finger held it; the menu opens at the finger. */
    @Test fun menuLight() = shot("subs-menu", whole = false, windows = true, before = { holdHarbor() }) { Subs() }

    @Test fun menuDark() = shot("subs-menu", dark = true, whole = false, windows = true, before = { holdHarbor() }) { Subs() }

    /** 刪除 picked from that menu: the question's sheet. */
    @Test fun deleteLight() = shot("subs-delete", whole = false, windows = true, before = { pickDelete() }) { Subs() }

    @Test fun deleteDark() = shot("subs-delete", dark = true, whole = false, windows = true, before = { pickDelete() }) { Subs() }

    /** The name's row tapped on Harbor Cloud's page: the rename sheet, on the name. */
    @Test fun renameLight() = shot("sub-detail-rename", whole = false, windows = true, before = { tap(R.string.rv_sub_name) }) { Detail() }

    @Test fun renameDark() = shot("sub-detail-rename", dark = true, whole = false, windows = true, before = { tap(R.string.rv_sub_name) }) { Detail() }

    /** The User-Agent row tapped: its sheet, the field empty for the default. */
    @Test fun userAgentLight() = shot("sub-detail-user-agent", whole = false, windows = true, before = { tap(R.string.rv_sub_user_agent) }) { Detail() }

    @Test fun userAgentDark() = shot("sub-detail-user-agent", dark = true, whole = false, windows = true, before = { tap(R.string.rv_sub_user_agent) }) { Detail() }

    /**
     * A link on the local network added: the import asks for the permission before it adds to honk, and the app's prompt
     * asks over the page. Until P1b this is the old dialog ([LocalNetworkPrompts]), not the approved sheet.
     */
    @Test fun lanAskLight() = lan("sub-add-lan-ask", refused = false)

    @Test fun lanAskDark() = lan("sub-add-lan-ask", refused = false, dark = true)

    /** The system's question refused: the refusal, which leads to the app's settings. */
    @Test fun lanDeniedLight() = lan("sub-add-lan-denied", refused = true)

    @Test fun lanDeniedDark() = lan("sub-add-lan-denied", refused = true, dark = true)

    @Test fun welcomeLight() = shot("welcome") { Welcome() }

    @Test fun welcomeDark() = shot("welcome", dark = true) { Welcome() }

    @Test fun welcomeFs200() = shot("welcome", fontScale = RV_FS200, whole = false) { Welcome() }

    @Test
    @Config(qualifiers = WELCOME_EN)
    fun welcomeEn() = shot("welcome", english = true) { Welcome() }

    @Test
    @Config(qualifiers = ZH_CN)
    fun welcomeZhCn() = shot("welcome-zhcn") { Welcome() }

    private fun shot(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        english: Boolean = false,
        whole: Boolean = true,
        windows: Boolean = false,
        before: ComposeContentTestRule.() -> Unit = {},
        content: @Composable () -> Unit,
    ) = compose.rvShot(
        name,
        dark,
        before = {
            before()
            waitForIdle()
            if (whole) assertWhole()
        },
        fontScale = fontScale,
        prefix = APP,
        english = english,
        windows = windows,
        content = content,
    )

    private fun ComposeContentTestRule.tap(label: Int) = onNodeWithText(string(label)).performClick()

    /** A long press where the prototype's finger held Harbor Cloud's card. */
    private fun ComposeContentTestRule.holdHarbor() = onRoot().performTouchInput { longClick(Offset(MENU_X.toPx(), MENU_Y.toPx())) }

    private fun ComposeContentTestRule.pickDelete() {
        holdHarbor()
        waitForIdle()
        tap(R.string.rv_menu_delete)
    }

    /** The question's 刪除, the last of that label once the sheet is up. */
    private fun ComposeContentTestRule.confirmSheetDelete() {
        waitForIdle()
        onAllNodesWithText(string(R.string.rv_menu_delete)).onLast().performClick()
    }

    /** The sheet [row] opens on the page, saved as it opens; with [waitFor], until the reopened sheet says why the save failed with that code. */
    private fun ComposeContentTestRule.saveSheet(row: Int, waitFor: ErrorCode? = null) {
        tap(row)
        waitForIdle()
        onAllNodesWithText(string(R.string.rv_save)).onLast().performClick()
        if (waitFor != null) waitForReason(waitFor)
        waitForIdle()
    }

    /** 刪除 from the menu and its sheet; with [waitFor], until the banner says why the delete failed with that code. */
    private fun ComposeContentTestRule.confirmDeleteFromMenu(waitFor: ErrorCode? = null) {
        pickDelete()
        confirmSheetDelete()
        if (waitFor != null) waitForReason(waitFor)
    }

    private fun ComposeContentTestRule.waitForReason(code: ErrorCode) = waitUntil(POLL_WAIT_MS) { onAllNodesWithText(string(code.reasonRes()), substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** 刪除訂閱 and its sheet; with [waitFor], until the banner says why the delete failed with that code. */
    private fun ComposeContentTestRule.confirmDeleteFromPage(waitFor: ErrorCode? = null) {
        onNodeWithText(string(R.string.rv_sub_delete)).performScrollTo().performClick()
        confirmSheetDelete()
        if (waitFor != null) waitForReason(waitFor)
    }

    /**
     * 新增訂閱 with [RV_LAN_LINK] typed and 新增 tapped, while the local network is not allowed: the import asks before it
     * adds to honk, and with [refused] the system's question is refused. The import waits on the
     * prompt, so the busy bar runs under it: the clock stops at the tap and runs [LAN_FRAMES] frames for each step.
     */
    private fun lan(name: String, refused: Boolean, dark: Boolean = false) {
        val access = LocalNetworkAccess { false }
        shot(
            name,
            dark,
            whole = false,
            windows = true,
            before = {
                onAllNodes(hasSetTextAction())[0].performTextReplacement(RV_LAN_LINK)
                runOnIdle { focus.clearFocus() }
                mainClock.autoAdvance = false
                onNodeWithText(string(R.string.rv_add_import)).performClick()
                repeat(LAN_FRAMES) { mainClock.advanceTimeByFrame() }
                // The sheet is a dialog window, laid out only once the main looper idles.
                runOnIdle {
                    if (refused) {
                        access.requesting()
                        access.answered(false)
                    }
                }
                repeat(LAN_FRAMES) { mainClock.advanceTimeByFrame() }
            },
        ) {
            Add(Answer.Harbor, access, remember { access.prompt.map { it != null } })
            // The sheet over the page, as AppSheets draws it under MainActivity's AppTheme.
            DoonaTheme { LocalNetworkPrompts(remember { LocalNetworkHost(access, request = {}, openSettings = {}) }) }
        }
    }

    /**
     * 新增訂閱 over honk answering the add with [answer]; with [typed] the sample's name is typed, the keyboard put away,
     * the link taken from the clipboard's row and 新增 tapped, so no field keeps the focus or a scrolled link. With
     * [busyFrames] the clock stops at the tap and runs that many frames, so a busy bar is drawn at a fixed point; else
     * the shot waits for the import to end, as an add honk runs in the background ends after its first poll.
     */
    private fun add(
        name: String,
        answer: Answer,
        typed: Boolean = true,
        dark: Boolean = false,
        fontScale: Float = 1f,
        english: Boolean = false,
        busyFrames: Int = 0,
    ) {
        // The clipboard holds a link, as on the approved page; its content is read only if its row is tapped.
        clipboard().setPrimaryClip(ClipData.newPlainText(null, RV_SAMPLE_LINK))
        shot(
            name,
            dark,
            fontScale,
            english,
            whole = fontScale == 1f,
            before = {
                if (typed) {
                    onAllNodes(hasSetTextAction())[1].performTextReplacement(RvData.subscriptions.first().name)
                    runOnIdle { focus.clearFocus() }
                    onNodeWithText(string(R.string.rv_add_clipboard)).performClick()
                    if (busyFrames > 0) mainClock.autoAdvance = false
                    onNodeWithText(string(R.string.rv_add_import)).performClick()
                    repeat(busyFrames) { mainClock.advanceTimeByFrame() }
                    if (busyFrames == 0) waitUntil(POLL_WAIT_MS) { onAllNodesWithText(string(R.string.rv_add_importing)).fetchSemanticsNodes().isEmpty() }
                }
            },
        ) { Add(answer) }
    }

    /** 訂閱 on [honk], the 組態內 card under the subscriptions too. */
    @Composable
    private fun Subs(honk: () -> FakeHonk = ::sampleHonk) {
        val api = remember { honk() }
        val viewModel = remember { SubscriptionsViewModel(api, clock = { RvData.nowEpochMs }, io = Dispatchers.Unconfined) }
        val nodes = remember { AddNodeViewModel(api, HeldClipboard(), io = Dispatchers.Unconfined) }
        SubscriptionsRoute(onBack = {}, onOpen = {}, onAdd = {}, clock = { RvData.nowEpochMs }, viewModel = viewModel, nodes = nodes)
    }

    /** No subscription and no node of the configuration's own. */
    @Composable
    private fun SubsEmpty() = Subs {
        sampleHonk().apply { on("GET", "/api/v1/providers", 200, HonkFixtures.body("providers/empty")) }
    }

    /** Harbor Cloud's page on [honk]. */
    @Composable
    private fun Detail(hours: Flow<Int?> = TWELVE_HOURS, honk: () -> FakeHonk = ::sampleHonk) {
        val viewModel = remember { SubDetailViewModel(honk(), hours, "s1", io = Dispatchers.Unconfined) }
        SubDetailRoute(hours, "s1", onBack = {}, onAutoUpdate = {}, viewModel = viewModel)
    }

    /** 新增訂閱 over honk's sample answering an add with [answer] (Never: it never runs); a LAN link asks [access]. */
    @Composable
    private fun Add(answer: Answer, access: LocalNetworkAccess = LocalNetworkAccess { true }, lanAsking: Flow<Boolean> = flowOf(false)) {
        val manager = LocalFocusManager.current
        SideEffect { focus = manager }
        val honk = remember { answering(answer) }
        val scope = rememberCoroutineScope()
        val imports = remember { SubscriptionImports(honk, access, scope, if (answer == Answer.Never) StandardTestDispatcher() else Dispatchers.Unconfined) }
        val viewModel = remember { SubAddViewModel(honk, imports, AndroidClipboardLinks(clipboard()), lanAsking, io = Dispatchers.Unconfined) }
        SubAddRoute(imports, lanAsking, onBack = {}, viewModel = viewModel)
    }

    /** honk's RV sample profile with its configuration file, whose entries set no User-Agent. */
    private fun sampleHonk(): FakeHonk = HonkFixtures.rvSample().apply { on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample")) }

    /** The sample with Harbor Cloud's last fetch failed. */
    private fun failedHonk(): FakeHonk = sampleHonk().apply { on("GET", "/api/v1/providers", 200, HonkFixtures.body("providers/rv-sample-failed")) }

    /** The sample, whose delete of Harbor Cloud honk accepts and then fails, as its configuration store cannot be written. */
    private fun deleteFails(): FakeHonk = sampleHonk().apply {
        on("DELETE", "/api/v1/providers/s1", 202, HonkFixtures.body("operations/provider-delete-accepted"))
        on("GET", "/api/v1/operations/op-delete", 200, HonkFixtures.body("operations/provider-delete-store-unavailable"))
    }

    /** The sample, whose delete of Harbor Cloud honk refuses now. */
    private fun deleteRefused(): FakeHonk = sampleHonk().apply { on("DELETE", "/api/v1/providers/s1", 503, HonkFixtures.body("errors/temporarily-unavailable")) }

    /** The sample, whose refresh of Harbor Cloud and replacement of its configuration file honk refuses now. */
    private fun writeRefused(): FakeHonk = sampleHonk().apply {
        on("POST", "/api/v1/providers/s1/refresh", 503, HonkFixtures.body("errors/temporarily-unavailable"))
        on("PUT", "/api/v1/config/sources/source-main", 503, HonkFixtures.body("errors/temporarily-unavailable"))
    }

    /** The page's focus, to put the keyboard away after typing; taken once the page has composed. */
    private lateinit var focus: FocusManager

    @Composable
    private fun Welcome() = FirstRunGate(remember { FirstRunViewModel(FakeEngine(subscriptions = emptyList()), NO_NODES, MutableStateFlow(ImportState()), Dispatchers.Unconfined) }, onAdd = {}, frame = { it() }) {}

    /** How honk answers an add. */
    private sealed interface Answer {
        data object None : Answer

        /** The add never ends: the import stays in flight. */
        data object Never : Answer

        /** The sample's Harbor Cloud, as honk would add it from the sample's link. */
        data object Harbor : Answer

        data class Fails(val code: ErrorCode) : Answer
    }

    /** The sample profile answering an add with [answer]: at once, or through an operation that fails fetching. */
    private fun answering(answer: Answer): FakeHonk = sampleHonk().apply {
        on("POST", "/api/v1/providers", 201, HonkFixtures.body("providers/created-harbor"))
        val failed = when ((answer as? Answer.Fails)?.code) {
            ErrorCode.FetchFailed -> HonkFixtures.body("operations/provider-create-fetch-failed")
            ErrorCode.ParseFailed -> HonkFixtures.body("operations/provider-create-parse-failed")
            ErrorCode.TooLarge -> HonkFixtures.body("operations/provider-create-parse-failed").replace("parse_failed", "asset_too_large")
            else -> return@apply
        }
        on("POST", "/api/v1/providers", 202, HonkFixtures.body("operations/provider-create-accepted"))
        on("GET", "/api/v1/operations/op-create", 200, failed)
    }

    private companion object {
        const val APP = "app-rv"

        /** honk with no node, so a profile of no subscription has none: the first run's welcome page shows. */
        val NO_NODES = FakeHonk().apply { on("GET", "/api/v1/nodes", 200, """{"nodes":[],"next_cursor":null}""") }

        /**
         * The approved 匯入中 frame of the busy bar: 24 frames (384 ms) into its loop, the time the prototype's page
         * takes to settle after its first composition. Measured 2026-11-26: frame by frame from the tap, the 24th
         * matches `proto-rv-sub-adding-unknown-light` with no pixel apart; the bar starts at the tap here.
         */
        const val BUSY_FRAMES = 24

        /** How long a change honk runs in the background takes to report: its first poll is a second after it starts. */
        const val POLL_WAIT_MS = 5_000L

        /** Frames from a step to its prompt shown in place: the dialog's window and its fade, a second at most. */
        const val LAN_FRAMES = 60

        /** Where the prototype's finger held Harbor Cloud's card (RvSubsShotTest): a third of the way in, on its row. */
        val MENU_X = 136.dp
        val MENU_Y = 210.dp

        /** A subscription's page is taller than the phone, as in the prototype's golden. */
        const val WHOLE_DETAIL = "+h1087dp"

        /** The same page with the delete-failure banner over its stats, 175.5 dp taller (its reason takes two lines). */
        const val WHOLE_DETAIL_BANNER = "+h1263dp"

        /** The welcome page in English is 58 dp taller than the phone, as `proto-rv-welcome-en`'s window. */
        const val WELCOME_EN = "en-w412dp-h973dp-notnight-xhdpi"
        const val ZH_CN = "zh-rCN-w412dp-h915dp-notnight-xhdpi"

        /** The settings' default interval. */
        val TWELVE_HOURS = MutableStateFlow(12)

        /** Auto-update off. */
        val OFF = MutableStateFlow<Int?>(null)

        fun clipboard(): ClipboardManager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)

        fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
    }
}
