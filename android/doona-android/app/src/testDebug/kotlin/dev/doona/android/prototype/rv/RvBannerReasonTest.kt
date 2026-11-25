package dev.doona.android.prototype.rv

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import dev.doona.android.designsystem.rv.RvBanner
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvRowPart
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A notice with a reason sets it under the title, the disc's top on the title's top (LAYOUT-RULES R9); a notice without
 * one is its title alone, with no empty line under it, centred on the disc as a row without a subtitle (R8, D1), at
 * 100% and 200%.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvBannerReasonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aNoticeWithAReasonSetsItUnderTheTitle() = withReason(1f)

    @Test
    fun aNoticeWithAReasonSetsItUnderTheTitleAt200() = withReason(RV_FS200)

    @Test
    fun aNoticeWithoutAReasonIsItsTitleAloneOnTheDisc() = withoutReason(1f)

    @Test
    fun aNoticeWithoutAReasonIsItsTitleAloneOnTheDiscAt200() = withoutReason(RV_FS200)

    private fun withReason(fontScale: Float) {
        compose.rvContent(dark = false, fontScale = fontScale) { RvBanner(RvNotice(RvNoticeKind.Error, TITLE, REASON, ACTION, {})) }
        val title = bounds(TITLE)
        assertEquals("the reason starts where the title ends", title.bottom.value, bounds(REASON).top.value, TOLERANCE)
        assertEquals("the disc's top on the title's top", title.top.value, disc().top.value, TOLERANCE)
    }

    private fun withoutReason(fontScale: Float) {
        compose.rvContent(dark = false, fontScale = fontScale) { RvBanner(RvNotice(RvNoticeKind.Error, TITLE, null, ACTION, {})) }
        compose.onAllNodesWithText(REASON, useUnmergedTree = true).assertCountEquals(0)
        val title = bounds(TITLE)
        val disc = disc()
        val row = compose.onNodeWithTag(RvRowPart.NOTICE_ROW, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val tallest = maxOf(title.bottom - title.top, disc.bottom - disc.top)
        assertEquals("no line under the title: the row is as tall as its tallest part", tallest.value, (row.bottom - row.top).value, TOLERANCE)
        assertEquals("the title centred on the disc", centre(disc), centre(title), TOLERANCE)
    }

    private fun bounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun disc() = compose.onNodeWithTag(RvRowPart.ICON_DISC, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun centre(rect: DpRect) = (rect.top.value + rect.bottom.value) / 2

    private companion object {
        const val TITLE = "無法讀取 App 清單"
        const val REASON = "設定沒有寫入，請再試一次。"
        const val ACTION = "重試"
        const val TOLERANCE = 0.5f
    }
}
