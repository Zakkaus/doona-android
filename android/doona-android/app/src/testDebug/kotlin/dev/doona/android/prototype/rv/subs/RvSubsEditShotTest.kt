package dev.doona.android.prototype.rv.subs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvConfirmContent
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.subscriptions.RvSubFailure
import dev.doona.android.feature.subscriptions.RvSubsActions
import dev.doona.android.feature.subscriptions.RvSubsPage
import dev.doona.android.feature.subscriptions.RvSubsState
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
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
 * 訂閱 with the add and remove controls in style A (the owner's open question; owner 2026-11-26 「要加要減的按鈕我都要看到」):
 * `subs-a` is the approved page with 編輯 in the bar; `subs-a-editing` the edit mode, a remove disc on each subscription,
 * 新增訂閱 as the list's last row and 完成 at the bottom; `subs-a-confirm` the approved delete question over it;
 * `subs-a-failed` the approved banner of a delete the core refused, over the edit mode. After the last removal the page is
 * the approved empty state (`proto-rv-subs-empty-*`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSubsEditShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun aLight() = shot("subs-a", false) { List(STATE) }

    @Test fun aDark() = shot("subs-a", true) { List(STATE) }

    @Test fun editingLight() = shot("subs-a-editing", false) { List(EDITING) }

    @Test fun editingDark() = shot("subs-a-editing", true) { List(EDITING) }

    @Test fun editingFs200() = compose.rvShot("subs-a-editing", false, fontScale = RV_FS200) { List(EDITING) }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun editingEnLight() = compose.rvShot("subs-a-editing", false, before = { assertWhole() }, english = true) { List(EDITING) }

    @Test fun confirmLight() = shot("subs-a-confirm", false, whole = false) { Confirm() }

    @Test fun confirmDark() = shot("subs-a-confirm", true, whole = false) { Confirm() }

    @Test fun failedLight() = shot("subs-a-failed", false) { List(EDITING.copy(deleteFailed = RvSubFailure(HARBOR.id, ErrorCode.WriteFailed))) }

    @Test fun failedDark() = shot("subs-a-failed", true) { List(EDITING.copy(deleteFailed = RvSubFailure(HARBOR.id, ErrorCode.WriteFailed))) }

    @Test
    fun thePencilEntersEditModeAndADiscAsksToDelete() {
        var state by mutableStateOf(STATE)
        val edits = mutableListOf<Boolean>()
        val deletes = mutableListOf<String?>()
        val actions = RvSubsActions(
            edit = {
                edits += it
                state = state.copy(editing = it)
            },
            delete = { deletes += it },
        )
        compose.setContent { Themed { RvSubsPage(state, actions) } }
        compose.onNodeWithContentDescription("刪除 ${HARBOR.name}").assertDoesNotExist()
        compose.onNodeWithContentDescription("編輯").performClick()
        assertEquals(listOf(true), edits)
        compose.onNodeWithContentDescription("刪除 ${HARBOR.name}").performClick()
        compose.onNodeWithText("完成").performClick()
        assertEquals(listOf<String?>(HARBOR.id), deletes)
        assertEquals(listOf(true, false), edits)
    }

    @Test
    fun theApprovedPageHasNoPencil() {
        compose.setContent { Themed { RvSubsPage(STATE, RvSubsActions()) } }
        compose.onNodeWithContentDescription("編輯").assertDoesNotExist()
    }

    private fun shot(name: String, dark: Boolean, whole: Boolean = true, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { if (whole) assertWhole() }, content = content)

    @Composable
    private fun List(state: RvSubsState) = RvSubsPage(state, RvSubsActions(edit = {}))

    @Composable
    private fun Confirm() {
        Box(Modifier.fillMaxSize()) {
            List(EDITING)
            RvSheetLayout({ 1f }, {}, stringResource(R.string.rv_sub_delete_title, HARBOR.name)) {
                RvConfirmContent(
                    stringResource(R.string.rv_sub_delete_title, HARBOR.name),
                    pluralStringResource(R.plurals.rv_sub_delete_body, HARBOR.nodes, HARBOR.nodes),
                    stringResource(R.string.rv_menu_delete),
                    stringResource(R.string.rv_cancel),
                    onConfirm = {},
                    onDismiss = {},
                    titleKeepWords = false,
                )
            }
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    private companion object {
        val HARBOR = RvData.subscriptions.first()
        val STATE = rvSubsStateSample()
        val EDITING = STATE.copy(editing = true)
    }
}
