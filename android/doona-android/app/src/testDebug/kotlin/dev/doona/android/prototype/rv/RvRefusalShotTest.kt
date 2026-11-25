package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.activity.RvActivityNotice
import dev.doona.android.feature.activity.rvActivityBanner
import dev.doona.android.prototype.rv.states.rvNoticeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 活動 after the user refused the system's VPN consent (`proto-rv-activity-consent-denied-*`) and after the notification
 * permission was refused (`-activity-notify-denied-*`). v1 is a banner over the cards with the way back, v2 shows nothing:
 * 活動 as it is idle, so v2 is the same image as `proto-rv-activity-idle-*`. Each light, dark, fs115 and fs200.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvRefusalShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun consentV1Light() = v1(RvActivityNotice.ConsentDenied, dark = false)

    @Test fun consentV1Dark() = v1(RvActivityNotice.ConsentDenied, dark = true)

    @Test fun consentV1Fs115() = v1(RvActivityNotice.ConsentDenied, fontScale = RV_FS115)

    @Test fun consentV1Fs200() = v1(RvActivityNotice.ConsentDenied, fontScale = RV_FS200)

    @Test fun notifyV1Light() = v1(RvActivityNotice.NotifyDenied, dark = false)

    @Test fun notifyV1Dark() = v1(RvActivityNotice.NotifyDenied, dark = true)

    @Test fun notifyV1Fs115() = v1(RvActivityNotice.NotifyDenied, fontScale = RV_FS115)

    @Test fun notifyV1Fs200() = v1(RvActivityNotice.NotifyDenied, fontScale = RV_FS200)

    @Test fun consentV2Light() = v2("consent-denied", dark = false)

    @Test fun consentV2Dark() = v2("consent-denied", dark = true)

    @Test fun consentV2Fs115() = v2("consent-denied", fontScale = RV_FS115)

    @Test fun consentV2Fs200() = v2("consent-denied", fontScale = RV_FS200)

    @Test fun notifyV2Light() = v2("notify-denied", dark = false)

    @Test fun notifyV2Dark() = v2("notify-denied", dark = true)

    @Test fun notifyV2Fs115() = v2("notify-denied", fontScale = RV_FS115)

    @Test fun notifyV2Fs200() = v2("notify-denied", fontScale = RV_FS200)

    private fun v1(notice: RvActivityNotice, dark: Boolean = false, fontScale: Float = 1f) =
        compose.rvShot("activity-${notice.route}-v1", dark, fontScale = fontScale) { Banner(notice) }

    private fun v2(route: String, dark: Boolean = false, fontScale: Float = 1f) =
        compose.rvShot("activity-$route-v2", dark, fontScale = fontScale) { RvActivityScreenSample(rvIdleActivity(), onAction = {}, onConnect = {}) }

    @Composable
    private fun Banner(notice: RvActivityNotice) =
        RvActivityScreenSample(rvNoticeActivity(notice), onAction = {}, onConnect = {}, banner = rvActivityBanner(notice) {})
}
