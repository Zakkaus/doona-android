package dev.doona.android.prototype.rv.subs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * 訂閱 with the add and remove controls (owner 2026-11-26 「要加要減的按鈕我都要看到」; on the right, 13:53): `subs-edit`
 * a remove control at each subscription's end and 新增訂閱 as the list's last row; `subs-edit-confirm` the approved delete
 * question over it; `subs-edit-failed` the approved banner of a delete the core refused. After the last removal the page
 * is the approved empty state (`proto-rv-subs-empty-*`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSubsEditShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun editLight() = shot("subs-edit", false) { List(STATE) }

    @Test fun editDark() = shot("subs-edit", true) { List(STATE) }

    @Test fun editFs200() = compose.rvShot("subs-edit", false, fontScale = RV_FS200) { List(STATE) }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun editEnLight() = compose.rvShot("subs-edit", false, before = { assertWhole() }, english = true) { List(STATE) }

    @Test fun confirmLight() = shot("subs-edit-confirm", false, whole = false) { Confirm() }

    @Test fun confirmDark() = shot("subs-edit-confirm", true, whole = false) { Confirm() }

    @Test fun failedLight() = shot("subs-edit-failed", false) { List(STATE.copy(deleteFailed = RvSubFailure(HARBOR.id, ErrorCode.WriteFailed))) }

    @Test fun failedDark() = shot("subs-edit-failed", true) { List(STATE.copy(deleteFailed = RvSubFailure(HARBOR.id, ErrorCode.WriteFailed))) }

    @Test
    fun aRemoveControlAsksToDeleteItsSubscription() {
        val deletes = mutableListOf<String?>()
        compose.setContent { Themed { RvSubsPage(STATE, RvSubsActions(delete = { deletes += it }), removable = true) } }
        compose.onNodeWithContentDescription("刪除 ${HARBOR.name}").performClick()
        assertEquals(listOf<String?>(HARBOR.id), deletes)
    }

    @Test
    fun theApprovedPageHasNoRemoveControl() {
        compose.setContent { Themed { RvSubsPage(STATE, RvSubsActions()) } }
        compose.onNodeWithContentDescription("刪除 ${HARBOR.name}").assertDoesNotExist()
    }

    private fun shot(name: String, dark: Boolean, whole: Boolean = true, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { if (whole) assertWhole() }, content = content)

    @Composable
    private fun List(state: RvSubsState) = RvSubsPage(state, RvSubsActions(), removable = true)

    @Composable
    private fun Confirm() {
        Box(Modifier.fillMaxSize()) {
            List(STATE)
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
    }
}
