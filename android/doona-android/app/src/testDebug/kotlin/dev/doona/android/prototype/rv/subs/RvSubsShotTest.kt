package dev.doona.android.prototype.rv.subs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.RvActivityScreen
import dev.doona.android.prototype.rv.RvConfirmContent
import dev.doona.android.prototype.rv.RvMenuPanel
import dev.doona.android.prototype.rv.RvSheetLayout
import dev.doona.android.prototype.rv.RvTextField
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvSampleActivity
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
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
 * User-Agent sheet (`-sub-detail-user-agent-*`); 新增訂閱 with the clipboard's row (`-sub-add-*`), importing, done, and
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
                RvTextField(HARBOR.name, {}, stringResource(R.string.rv_sub_name))
            }
        }
    }

    @Composable
    private fun UserAgent() {
        Box(Modifier.fillMaxSize()) {
            Detail()
            Sheet(stringResource(R.string.rv_sub_user_agent), null, stringResource(R.string.rv_save)) {
                RvTextField("", {}, stringResource(R.string.rv_sub_user_agent))
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
        const val WHOLE_DETAIL = "+h1079dp"
        val HARBOR = RvData.subscriptions.first()

        /** Where a finger held Harbor Cloud's card: a third of the way in, on its row. */
        val MENU_X = 136.dp
        val MENU_Y = 210.dp
        const val IMPORT = "匯入"
        const val CLIPBOARD = "使用剪貼簿的連結"
    }
}
