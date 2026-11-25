package dev.doona.android.prototype.rv.states

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.designsystem.rv.RvConfirmContent
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.localnetwork.LocalNetworkPrompt
import dev.doona.android.feature.localnetwork.text
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvActivityScreen
import dev.doona.android.prototype.rv.RvActivityScreenSample
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import dev.doona.android.prototype.rv.subs.RvSubAdd
import dev.doona.android.prototype.rv.subs.RvSubAddSample
import dev.doona.android.prototype.rv.subs.rvAddSample
import dev.doona.android.prototype.rv.welcome.RvWelcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first run and the states that stop traffic, light and dark: the welcome page whole (`proto-rv-welcome-*`, also
 * fs115, fs200, English and Simplified); 活動
 * with no subscription (`-activity-empty-*`), with every node failed (`-activity-no-node-*`), with the VPN taken
 * (`-activity-vpn-taken-*`), offline (`-activity-offline-*`) and after the core stopped (`-activity-core-stopped-*`), each
 * its first screen, and once after a crash (`-activity-crash-*`, also fs115 and fs200); and the local network prompts
 * over 新增訂閱 with a link on the local network (`-sub-add-lan-{ask,denied}-*`), drawn as their sheet's window draws them
 * once in. Offline and with the VPN taken also as the owner's sample B of item 8 (`-activity-{offline,vpn-taken}-fade-*`,
 * light, dark, fs115, fs200): the page's colour under the nav ([dev.doona.android.designsystem.rv.RvNavFade]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvStatesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun welcomeLight() = shot("welcome", false, whole = true) { RvWelcome(onAdd = {}) }

    @Test fun welcomeDark() = shot("welcome", true, whole = true) { RvWelcome(onAdd = {}) }

    @Test
    @Config(qualifiers = "+h943dp")
    fun welcomeFs115() = shot("welcome", false, whole = true, fontScale = RV_FS115) { RvWelcome(onAdd = {}) }

    @Test
    @Config(qualifiers = "+h1315dp")
    fun welcomeFs200() = shot("welcome", false, whole = true, fontScale = RV_FS200) { RvWelcome(onAdd = {}) }

    @Test
    @Config(qualifiers = "+en-h973dp")
    fun welcomeEnglish() = shot("welcome-en", false, whole = true) { RvWelcome(onAdd = {}) }

    @Test
    @Config(qualifiers = "+zh-rCN")
    fun welcomeSimplified() = shot("welcome-zh-cn", false, whole = true) { RvWelcome(onAdd = {}) }

    @Test fun emptyLight() = notice(RvActivityNotice.NoSubscription, false)

    @Test fun emptyDark() = notice(RvActivityNotice.NoSubscription, true)

    @Test fun noNodeLight() = notice(RvActivityNotice.NoNode, false)

    @Test fun noNodeDark() = notice(RvActivityNotice.NoNode, true)

    @Test fun vpnTakenLight() = notice(RvActivityNotice.VpnTaken, false)

    @Test fun vpnTakenDark() = notice(RvActivityNotice.VpnTaken, true)

    @Test fun offlineLight() = notice(RvActivityNotice.Offline, false)

    @Test fun offlineDark() = notice(RvActivityNotice.Offline, true)

    @Test fun offlineFadeLight() = fade(RvActivityNotice.Offline, false)

    @Test fun offlineFadeDark() = fade(RvActivityNotice.Offline, true)

    @Test fun offlineFadeFs115() = fade(RvActivityNotice.Offline, false, RV_FS115)

    @Test fun offlineFadeFs200() = fade(RvActivityNotice.Offline, false, RV_FS200)

    @Test fun vpnTakenFadeLight() = fade(RvActivityNotice.VpnTaken, false)

    @Test fun vpnTakenFadeDark() = fade(RvActivityNotice.VpnTaken, true)

    @Test fun vpnTakenFadeFs115() = fade(RvActivityNotice.VpnTaken, false, RV_FS115)

    @Test fun vpnTakenFadeFs200() = fade(RvActivityNotice.VpnTaken, false, RV_FS200)

    @Test fun coreStoppedLight() = notice(RvActivityNotice.CoreStopped, false)

    @Test fun coreStoppedDark() = notice(RvActivityNotice.CoreStopped, true)

    @Test fun crashLight() = notice(RvActivityNotice.Crash, false)

    @Test fun crashDark() = notice(RvActivityNotice.Crash, true)

    @Test fun crashFs115() = notice(RvActivityNotice.Crash, false, RV_FS115)

    @Test fun crashFs200() = notice(RvActivityNotice.Crash, false, RV_FS200)

    @Test fun lanAskLight() = shot("sub-add-lan-ask", false) { Lan("lan-ask", LocalNetworkPrompt.Ask) }

    @Test fun lanAskDark() = shot("sub-add-lan-ask", true) { Lan("lan-ask", LocalNetworkPrompt.Ask) }

    @Test fun lanDeniedLight() = shot("sub-add-lan-denied", false) { Lan("lan-denied", LocalNetworkPrompt.Denied) }

    @Test fun lanDeniedDark() = shot("sub-add-lan-denied", true) { Lan("lan-denied", LocalNetworkPrompt.Denied) }

    @Test
    fun theWelcomePageAddsASubscription() {
        var adds = 0
        compose.setContent { Themed { RvWelcome(onAdd = { adds++ }) } }
        compose.onNodeWithText(ADD).performClick()
        assertEquals(1, adds)
    }

    @Test
    fun theEmptyStateStandsInPlaceOfTheCardsAndAdds() {
        var adds = 0
        compose.setContent { Themed { Notice(RvActivityNotice.NoSubscription) { adds++ } } }
        compose.onNodeWithText(TODAY).assertDoesNotExist()
        compose.onNodeWithText(ADD).performClick()
        assertEquals(1, adds)
    }

    @Test
    fun aBannerHeadsTheCardsAndRunsItsAction() {
        var actions = 0
        compose.setContent { Themed { Notice(RvActivityNotice.VpnTaken) { actions++ } } }
        compose.onNodeWithText(TODAY).assertExists()
        compose.onNodeWithText(RECONNECT).performClick()
        assertEquals(1, actions)
    }

    @Test
    fun theCrashNoticeExports() {
        var exports = 0
        compose.setContent { Themed { Notice(RvActivityNotice.Crash) { exports++ } } }
        compose.onNodeWithText(TODAY).assertExists()
        compose.onNodeWithText(EXPORT).performClick()
        assertEquals(1, exports)
    }

    private fun notice(notice: RvActivityNotice, dark: Boolean, fontScale: Float = 1f) = shot("activity-${notice.route}", dark, fontScale = fontScale) { Notice(notice) {} }

    private fun fade(notice: RvActivityNotice, dark: Boolean, fontScale: Float = 1f) =
        shot("activity-${notice.route}-fade", dark, fontScale = fontScale) { Notice(notice, navFade = true) {} }

    private fun shot(name: String, dark: Boolean, whole: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { if (whole) assertWhole() }, fontScale = fontScale, content = content)

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    @Composable
    private fun Notice(notice: RvActivityNotice, navFade: Boolean = false, onAction: () -> Unit) = RvActivityScreenSample(
        rvNoticeActivity(notice),
        onAction = {},
        onConnect = {},
        banner = rvActivityBanner(notice, onAction),
        noSubscription = onAction.takeIf { notice == RvActivityNotice.NoSubscription },
        navFade = navFade,
    )

    /** 新增訂閱 with a link on the local network, under the prompt its 新增 raised. */
    @Composable
    private fun Lan(sample: String, prompt: LocalNetworkPrompt) {
        Box(Modifier.fillMaxSize()) {
            RvSubAddSample(rvAddSample(sample).copy(lan = null), {}, onPaste = {}, onImport = {}, onBack = {})
            val text = prompt.text()
            RvSheetLayout({ 1f }, {}, text.title) {
                RvConfirmContent(text.title, text.body, text.confirm, text.dismiss, onConfirm = {}, onDismiss = {})
            }
        }
    }

    private companion object {
        const val ADD = "新增訂閱"
        const val TODAY = "今日用量"
        const val RECONNECT = "重新連線"
        const val EXPORT = "匯出報告"
    }
}
