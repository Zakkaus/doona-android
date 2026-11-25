package dev.doona.android.feature.activity

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvScorePick
import dev.doona.android.feature.nodes.ui.RvScoreChoiceRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RvActivityBannerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every node failing its test is titled as such, and a pick of no node still says no node is available`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        var banner: RvNotice? = null
        val none = RvScorePick(selectedId = null, selectedName = null, settled = false, wait = null, compared = 0, candidates = 0, challengers = emptyList())
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                banner = rvActivityBanner(RvActivityNotice.NoNode) {}
                RvScoreChoiceRow(none, members = emptyList(), line = null)
            }
        }

        assertEquals(res.getString(R.string.rv_latency_all_failed), banner?.title)
        compose.onNodeWithText(res.getString(R.string.rv_no_node)).assertExists()
    }
}
