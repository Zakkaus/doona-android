package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.activity.RvActivityNotice
import dev.doona.android.feature.activity.rvActivityBanner
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.prototype.rv.states.rvNoticeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 活動 after a start failed, each a banner over the cards (`proto-rv-activity-failed-<reason>-*`): the tunnel did not start
 * (`start`), the per-app list resolved to no installed app (`no-apps`), the bundled core speaks another schema
 * (`incompatible`), and the core found no node in Global (`no-nodes`). A tunnel the system revoked is the existing
 * `activity-vpn-taken`. Each light, dark, fs115 and fs200.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvStartFailedShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun startLight() = shot(RvActivityNotice.StartFailed, dark = false)

    @Test fun startDark() = shot(RvActivityNotice.StartFailed, dark = true)

    @Test fun startFs115() = shot(RvActivityNotice.StartFailed, fontScale = RV_FS115)

    @Test fun startFs200() = shot(RvActivityNotice.StartFailed, fontScale = RV_FS200)

    @Test fun noAppsLight() = shot(RvActivityNotice.StartNoApps, dark = false)

    @Test fun noAppsDark() = shot(RvActivityNotice.StartNoApps, dark = true)

    @Test fun noAppsFs115() = shot(RvActivityNotice.StartNoApps, fontScale = RV_FS115)

    @Test fun noAppsFs200() = shot(RvActivityNotice.StartNoApps, fontScale = RV_FS200)

    @Test fun incompatibleLight() = shot(RvActivityNotice.StartIncompatible, dark = false)

    @Test fun incompatibleDark() = shot(RvActivityNotice.StartIncompatible, dark = true)

    @Test fun incompatibleFs115() = shot(RvActivityNotice.StartIncompatible, fontScale = RV_FS115)

    @Test fun incompatibleFs200() = shot(RvActivityNotice.StartIncompatible, fontScale = RV_FS200)

    @Test fun noNodesLight() = shot(RvActivityNotice.StartNoNodes, dark = false)

    @Test fun noNodesDark() = shot(RvActivityNotice.StartNoNodes, dark = true)

    @Test fun noNodesFs115() = shot(RvActivityNotice.StartNoNodes, fontScale = RV_FS115)

    @Test fun noNodesFs200() = shot(RvActivityNotice.StartNoNodes, fontScale = RV_FS200)

    private fun shot(notice: RvActivityNotice, dark: Boolean = false, fontScale: Float = 1f) =
        compose.rvShot("activity-${notice.route}", dark, fontScale = fontScale) { Activity(notice) }

    /** `no_nodes` is the core's answer in Global, so that page says 全域; the others show the sample's 規則. */
    @Composable
    private fun Activity(notice: RvActivityNotice) = RvActivityScreenSample(
        rvNoticeActivity(notice),
        onAction = {},
        onConnect = {},
        mode = if (notice == RvActivityNotice.StartNoNodes) RvRouteMode.Global else RvRouteMode.Rule,
        banner = rvActivityBanner(notice) {},
    )
}
