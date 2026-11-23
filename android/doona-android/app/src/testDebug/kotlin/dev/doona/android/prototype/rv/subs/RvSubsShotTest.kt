package dev.doona.android.prototype.rv.subs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.RvActivityScreen
import dev.doona.android.prototype.rv.RvConfirmContent
import dev.doona.android.prototype.rv.RvMenuPanel
import dev.doona.android.prototype.rv.RvSheetLayout
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvSampleActivity
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The subscription pages, light and dark, each page whole in one capture: 訂閱 (`proto-rv-subs-*`), with a failed update
 * (`-subs-failed-*`), with the long-press menu open on Harbor Cloud (`-subs-menu-*`) and with the delete question
 * (`-subs-delete-*`); a subscription's page (`-sub-detail-*`), its rename sheet (`-sub-detail-rename-*`) and its
 * User-Agent sheet (`-sub-detail-user-agent-*`); 新增訂閱 with the clipboard's row (`-sub-add-*`), with a User-Agent
 * typed (`-sub-add-user-agent-*`) and with one the core refuses (`-sub-add-user-agent-invalid-*`), importing, done, and
 * failed for each reason; and the deep link's question over 活動 (`-sub-import-*`). Sheets are drawn as their window
 * draws them once in.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSubsShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun listLight() = shot("subs", false) { List() }

    @Test fun listDark() = shot("subs", true) { List() }

    @Test fun failedLight() = shot("subs-failed", false) { List(RvSubsState(failedId = HARBOR.id)) }

    @Test fun failedDark() = shot("subs-failed", true) { List(RvSubsState(failedId = HARBOR.id)) }

    @Test fun menuLight() = shot("subs-menu", false, whole = false) { Menu() }

    @Test fun menuDark() = shot("subs-menu", true, whole = false) { Menu() }

    @Test fun deleteLight() = shot("subs-delete", false, whole = false) { Delete() }

    @Test fun deleteDark() = shot("subs-delete", true, whole = false) { Delete() }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailLight() = shot("sub-detail", false) { Detail() }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailDark() = shot("sub-detail", true) { Detail() }

    @Test fun renameLight() = shot("sub-detail-rename", false, whole = false) { Rename() }

    @Test fun renameDark() = shot("sub-detail-rename", true, whole = false) { Rename() }

    @Test fun userAgentLight() = shot("sub-detail-user-agent", false, whole = false) { UserAgent() }

    @Test fun userAgentDark() = shot("sub-detail-user-agent", true, whole = false) { UserAgent() }

    @Test fun addLight() = shot("sub-add", false) { Add("") }

    @Test fun addDark() = shot("sub-add", true) { Add("") }

    @Test fun importingLight() = shot("sub-add-importing", false) { Add("importing") }

    @Test fun importingDark() = shot("sub-add-importing", true) { Add("importing") }

    @Test fun doneLight() = shot("sub-add-done", false) { Add("done") }

    @Test fun doneDark() = shot("sub-add-done", true) { Add("done") }

    @Test fun unreachableLight() = shot("sub-add-unreachable", false) { Add("unreachable") }

    @Test fun unreachableDark() = shot("sub-add-unreachable", true) { Add("unreachable") }

    @Test fun notSubscriptionLight() = shot("sub-add-not-subscription", false) { Add("not-subscription") }

    @Test fun notSubscriptionDark() = shot("sub-add-not-subscription", true) { Add("not-subscription") }

    @Test fun addUserAgentLight() = shot("sub-add-user-agent", false) { Add("user-agent") }

    @Test fun addUserAgentDark() = shot("sub-add-user-agent", true) { Add("user-agent") }

    @Test fun addUserAgentInvalidLight() = shot("sub-add-user-agent-invalid", false) { Add("user-agent-invalid") }

    @Test fun addUserAgentInvalidDark() = shot("sub-add-user-agent-invalid", true) { Add("user-agent-invalid") }

    @Test fun tooLargeLight() = shot("sub-add-too-large", false) { Add("too-large") }

    @Test fun tooLargeDark() = shot("sub-add-too-large", true) { Add("too-large") }

    @Test fun importLight() = shot("sub-import", false, whole = false) { Import() }

    @Test fun importDark() = shot("sub-import", true, whole = false) { Import() }

    @Test
    fun theClipboardRowFillsTheLinkAndImportWaitsForIt() {
        var state = RvAddState()
        var imports = 0
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvSubAdd(state, { state = it }, onPaste = { state = state.copy(link = "https://x.example/s") }, onImport = { imports++ }, onBack = {})
            }
        }
        compose.onNodeWithText(IMPORT).performClick()
        assertEquals(0, imports)
        compose.onNodeWithText(CLIPBOARD).performClick()
        assertEquals("https://x.example/s", state.link)
    }

    @Test
    fun theUserAgentRowShowsTheSubscriptionsOwnAndItsSheetSavesToIt() {
        var state by mutableStateOf(RvSubDetailState())
        val saved = mutableListOf<String?>()
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvSubDetail(HARBOR.copy(userAgent = OWN_AGENT), state, { state = it }, onBack = {}, onUpdate = {}, onUserAgent = { saved += it })
            }
        }
        compose.onNodeWithText(OWN_AGENT).performClick()
        assertEquals(RvSubDetailState(RvSubSheet.UserAgent, userAgent = OWN_AGENT), state)
        compose.onNode(hasSetTextAction()).performTextReplacement(TYPED_AGENT)
        compose.onNodeWithText(SAVE).performClick()
        // An empty field saves null: the subscription goes back to the default.
        compose.onNodeWithText(OWN_AGENT).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText(SAVE).performClick()
        assertEquals(listOf(TYPED_AGENT, null), saved)
        assertEquals(null, state.sheet)
    }

    /** The core's rule (`fetch::user_agent`): empty or blank is the default; else 256 printable ASCII characters at most. */
    @Test
    fun aUserAgentIsCheckedAndSavedByTheCoresRule() {
        listOf("", "   ", RV_SAMPLE_AGENT, "x".repeat(256), " ~").forEach { assertTrue(it, rvAgentValid(it)) }
        listOf("x".repeat(257), "a\nb", "a\u0000", RV_SAMPLE_AGENT + RV_SAMPLE_AGENT_BAD).forEach { assertFalse(it, rvAgentValid(it)) }
        assertEquals(null, rvAgentOf("   "))
        assertEquals(RV_SAMPLE_AGENT, rvAgentOf(" $RV_SAMPLE_AGENT "))
    }

    private fun shot(name: String, dark: Boolean, whole: Boolean = true, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { if (whole) assertWhole() }, content = content)

    @Composable
    private fun List(state: RvSubsState = RvSubsState()) = RvSubsPage(state, RvSubsActions())

    @Composable
    private fun Detail() = RvSubDetail(HARBOR, RvSubDetailState(), {}, onBack = {}, onUpdate = {})

    @Composable
    private fun Add(phase: String) = RvSubAdd(rvAddSample(phase), {}, onPaste = {}, onImport = {}, onBack = {})

    /** The list with the menu where a finger held Harbor Cloud's card, as the popup places it. */
    @Composable
    private fun Menu() {
        Box(Modifier.fillMaxSize()) {
            List()
            RvMenuPanel(rvSubscriptionMenu({}, {}, {}), onPick = {}, Modifier.offset(MENU_X, MENU_Y))
        }
    }

    @Composable
    private fun Delete() {
        Box(Modifier.fillMaxSize()) {
            List()
            Sheet(
                stringResource(R.string.rv_sub_delete_title, HARBOR.name),
                pluralStringResource(R.plurals.rv_sub_delete_body, HARBOR.nodes, HARBOR.nodes),
                stringResource(R.string.rv_menu_delete),
            )
        }
    }

    @Composable
    private fun Rename() {
        Box(Modifier.fillMaxSize()) {
            Detail()
            Sheet(stringResource(R.string.rv_sub_rename), null, stringResource(R.string.rv_save)) {
                RvRenameField(HARBOR.name) {}
            }
        }
    }

    @Composable
    private fun UserAgent() {
        Box(Modifier.fillMaxSize()) {
            Detail()
            Sheet(stringResource(R.string.rv_sub_user_agent), null, stringResource(R.string.rv_save)) {
                RvUserAgentField("", {})
            }
        }
    }

    /** 活動 under the deep link's question. */
    @Composable
    private fun Import() {
        Box(Modifier.fillMaxSize()) {
            RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {})
            Sheet(stringResource(R.string.rv_import_title, HARBOR.name), stringResource(R.string.rv_import_body, HARBOR.host), stringResource(R.string.rv_import_add))
        }
    }

    @Composable
    private fun Sheet(title: String, body: String?, confirm: String, extra: (@Composable () -> Unit)? = null) {
        RvSheetLayout({ 1f }, {}, title) {
            RvConfirmContent(title, body, confirm, stringResource(R.string.rv_cancel), onConfirm = {}, onDismiss = {}, extra = extra?.let { { it() } })
        }
    }

    private companion object {
        /** A subscription's page is taller than the phone; every other page fits its 915 dp. */
        const val WHOLE_DETAIL = "+h1087dp"
        val HARBOR = RvData.subscriptions.first()

        /** Where a finger held Harbor Cloud's card: a third of the way in, on its row. */
        val MENU_X = 136.dp
        val MENU_Y = 210.dp
        const val IMPORT = "匯入"
        const val CLIPBOARD = "使用剪貼簿的連結"
        const val SAVE = "儲存"
        const val OWN_AGENT = "clash.meta"
        const val TYPED_AGENT = "doona-test/1"
    }
}
