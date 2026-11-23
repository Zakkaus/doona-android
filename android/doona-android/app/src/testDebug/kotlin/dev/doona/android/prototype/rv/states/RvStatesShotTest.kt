package dev.doona.android.prototype.rv.states

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.prototype.rv.RvActivityScreen
import dev.doona.android.prototype.rv.RvConfirmContent
import dev.doona.android.prototype.rv.RvSheetLayout
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import dev.doona.android.prototype.rv.subs.RvLanPrompt
import dev.doona.android.prototype.rv.subs.RvSubAdd
import dev.doona.android.prototype.rv.subs.rvAddSample
import dev.doona.android.prototype.rv.subs.text
import dev.doona.android.prototype.rv.welcome.RvWelcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first run and the states that stop traffic, light and dark: the welcome page whole (`proto-rv-welcome-*`); 活動
 * with no subscription (`-activity-empty-*`), with every node failed (`-activity-no-node-*`), with the VPN taken
 * (`-activity-vpn-taken-*`), offline (`-activity-offline-*`) and after the core stopped (`-activity-core-stopped-*`), each
 * its first screen; and the local network prompts over 新增訂閱 with a link on the local network
 * (`-sub-add-lan-{ask,denied}-*`), drawn as their sheet's window draws them once in.
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

    @Test fun emptyLight() = notice(RvActivityNotice.NoSubscription, false)

    @Test fun emptyDark() = notice(RvActivityNotice.NoSubscription, true)

    @Test fun noNodeLight() = notice(RvActivityNotice.NoNode, false)

    @Test fun noNodeDark() = notice(RvActivityNotice.NoNode, true)

    @Test fun vpnTakenLight() = notice(RvActivityNotice.VpnTaken, false)

    @Test fun vpnTakenDark() = notice(RvActivityNotice.VpnTaken, true)

    @Test fun offlineLight() = notice(RvActivityNotice.Offline, false)

    @Test fun offlineDark() = notice(RvActivityNotice.Offline, true)

    @Test fun coreStoppedLight() = notice(RvActivityNotice.CoreStopped, false)

    @Test fun coreStoppedDark() = notice(RvActivityNotice.CoreStopped, true)

    @Test fun lanAskLight() = shot("sub-add-lan-ask", false) { Lan("lan-ask", RvLanPrompt.Ask) }

    @Test fun lanAskDark() = shot("sub-add-lan-ask", true) { Lan("lan-ask", RvLanPrompt.Ask) }

    @Test fun lanDeniedLight() = shot("sub-add-lan-denied", false) { Lan("lan-denied", RvLanPrompt.Denied) }

    @Test fun lanDeniedDark() = shot("sub-add-lan-denied", true) { Lan("lan-denied", RvLanPrompt.Denied) }

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

    private fun notice(notice: RvActivityNotice, dark: Boolean) = shot("activity-${notice.route}", dark) { Notice(notice) {} }

    private fun shot(name: String, dark: Boolean, whole: Boolean = false, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { if (whole) assertWhole() }, content = content)

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    @Composable
    private fun Notice(notice: RvActivityNotice, onAction: () -> Unit) = RvActivityScreen(
        rvNoticeActivity(notice),
        onAction = {},
        onConnect = {},
        notice = { RvActivityNoticeCard(notice, onAction) },
        noticeOnly = notice == RvActivityNotice.NoSubscription,
    )

    /** 新增訂閱 with a link on the local network, under the prompt its 匯入 raised. */
    @Composable
    private fun Lan(sample: String, prompt: RvLanPrompt) {
        Box(Modifier.fillMaxSize()) {
            RvSubAdd(rvAddSample(sample).copy(lan = null), {}, onPaste = {}, onImport = {}, onBack = {})
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
    }
}
