package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import dev.doona.android.prototype.rv.subs.RvSubsActions
import dev.doona.android.prototype.rv.subs.RvSubsPage
import dev.doona.android.prototype.rv.subs.RvSubsState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 活動, 策略 and 訂閱 before the engine is `ready` (`proto-rv-<page>-loading-v1-*`, `-v2-*`): v1 leaves the page body
 * blank under the header and the nav, v2 puts one card holding the busy bar where the first card would be. Each light,
 * dark and fs200; no text wraps, so no fs115. The busy bar runs at animator scale 0, so it rests half full.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLoadingShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun activityV1Light() = shot("activity-loading-v1", dark = false) { Activity(blank = true) }

    @Test fun activityV1Dark() = shot("activity-loading-v1", dark = true) { Activity(blank = true) }

    @Test fun activityV1Fs200() = shot("activity-loading-v1", fontScale = RV_FS200) { Activity(blank = true) }

    @Test fun activityV2Light() = shot("activity-loading-v2", dark = false) { Activity(blank = false) }

    @Test fun activityV2Dark() = shot("activity-loading-v2", dark = true) { Activity(blank = false) }

    @Test fun activityV2Fs200() = shot("activity-loading-v2", fontScale = RV_FS200) { Activity(blank = false) }

    @Test fun policyV1Light() = shot("policy-loading-v1", dark = false) { Policy(blank = true) }

    @Test fun policyV1Dark() = shot("policy-loading-v1", dark = true) { Policy(blank = true) }

    @Test fun policyV1Fs200() = shot("policy-loading-v1", fontScale = RV_FS200) { Policy(blank = true) }

    @Test fun policyV2Light() = shot("policy-loading-v2", dark = false) { Policy(blank = false) }

    @Test fun policyV2Dark() = shot("policy-loading-v2", dark = true) { Policy(blank = false) }

    @Test fun policyV2Fs200() = shot("policy-loading-v2", fontScale = RV_FS200) { Policy(blank = false) }

    @Test fun subsV1Light() = shot("subs-loading-v1", dark = false) { Subs(blank = true) }

    @Test fun subsV1Dark() = shot("subs-loading-v1", dark = true) { Subs(blank = true) }

    @Test fun subsV1Fs200() = shot("subs-loading-v1", fontScale = RV_FS200) { Subs(blank = true) }

    @Test fun subsV2Light() = shot("subs-loading-v2", dark = false) { Subs(blank = false) }

    @Test fun subsV2Dark() = shot("subs-loading-v2", dark = true) { Subs(blank = false) }

    @Test fun subsV2Fs200() = shot("subs-loading-v2", fontScale = RV_FS200) { Subs(blank = false) }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        rvAnimatorScale(0f)
        compose.rvShot(name, dark, fontScale = fontScale, content = content)
    }

    /** The body under v1 (nothing) or v2 (one card with the busy bar), the same on every page. */
    private fun body(blank: Boolean): @Composable () -> Unit = if (blank) ({}) else ({ RvCard { RvBusy(Modifier.padding(RvSpace.rowPadding)) } })

    @Composable
    private fun Activity(blank: Boolean) = RvActivityScreen(rvIdleActivity(), onAction = {}, onConnect = {}, notice = body(blank), noticeOnly = true)

    @Composable
    private fun Policy(blank: Boolean) = RvPolicyScreen(RvPolicyState(), onChange = {}, notice = body(blank), noticeOnly = true)

    @Composable
    private fun Subs(blank: Boolean) = RvSubsPage(RvSubsState(), RvSubsActions(), notice = body(blank), noticeOnly = true)
}
