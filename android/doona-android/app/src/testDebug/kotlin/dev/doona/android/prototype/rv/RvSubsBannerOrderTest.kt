package dev.doona.android.prototype.rv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.rvFirstNotice
import dev.doona.android.feature.subscriptions.RvSubsActions
import dev.doona.android.feature.subscriptions.RvSubsPage
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.subs.rvFetchFailedSample
import dev.doona.android.prototype.rv.subs.rvSubsStateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 訂閱's banner place: an add's or delete's banner never hides a failed update, as the notice kinds rank them. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSubsBannerOrderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFailedUpdateStaysOverAnAddsBanner() {
        val sub = RvData.subscriptions.first()
        compose.rvContent(dark = false) {
            RvSubsPage(rvSubsStateSample(failed = rvFetchFailedSample(sub.id)), RvSubsActions(), banner = notice(RvNoticeKind.Info, ADDED))
        }
        val failed = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.rv_sub_failed_title, sub.name)
        compose.onNodeWithText(failed).assertIsDisplayed()
        compose.onNodeWithText(ADDED).assertDoesNotExist()
    }

    @Test
    fun theAddsBannerShowsWhenNothingFailed() {
        compose.rvContent(dark = false) { RvSubsPage(rvSubsStateSample(), RvSubsActions(), banner = notice(RvNoticeKind.Info, ADDED)) }
        compose.onNodeWithText(ADDED).assertIsDisplayed()
    }

    @Test
    fun theMostSeriousKindWinsAndTheFirstOfAKind() {
        val error = notice(RvNoticeKind.Error, "error")
        val second = notice(RvNoticeKind.Error, "second")
        val warning = notice(RvNoticeKind.Warning, "warning")
        val info = notice(RvNoticeKind.Info, "info")
        assertEquals(error, rvFirstNotice(info, warning, error, second))
        assertEquals(warning, rvFirstNotice(null, info, warning))
        assertNull(rvFirstNotice(null, null))
    }

    private fun notice(kind: RvNoticeKind, title: String) = RvNotice(kind, title, null, "OK", {})

    private companion object {
        const val ADDED = "已新增 2 個節點"
    }
}
