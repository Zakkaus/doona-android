package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.designsystem.rv.RvConfirmContent
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.feature.localnetwork.LocalNetworkPrompt
import dev.doona.android.feature.localnetwork.text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 活動 while connected, under the local network prompts a probe raises when a node or subscription on the local network
 * is reached: the ask before Android's dialog (`proto-rv-activity-lan-ask-*`) and the way to the app's settings after a
 * no (`-activity-lan-denied-*`); each light, dark, fs115 and fs200, and the ask light in English
 * (`-activity-lan-ask-en-light`). The sheet is the one 新增訂閱 raises, drawn over the page at its resting place as
 * that page's goldens draw it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvActivityLanShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun askLight() = shot("activity-lan-ask", LocalNetworkPrompt.Ask, dark = false)

    @Test fun askDark() = shot("activity-lan-ask", LocalNetworkPrompt.Ask, dark = true)

    @Test fun askFs115() = shot("activity-lan-ask", LocalNetworkPrompt.Ask, fontScale = RV_FS115)

    @Test fun askFs200() = shot("activity-lan-ask", LocalNetworkPrompt.Ask, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun askEn() = shot("activity-lan-ask-en", LocalNetworkPrompt.Ask)

    @Test fun deniedLight() = shot("activity-lan-denied", LocalNetworkPrompt.Denied, dark = false)

    @Test fun deniedDark() = shot("activity-lan-denied", LocalNetworkPrompt.Denied, dark = true)

    @Test fun deniedFs115() = shot("activity-lan-denied", LocalNetworkPrompt.Denied, fontScale = RV_FS115)

    @Test fun deniedFs200() = shot("activity-lan-denied", LocalNetworkPrompt.Denied, fontScale = RV_FS200)

    private fun shot(name: String, prompt: LocalNetworkPrompt, dark: Boolean = false, fontScale: Float = 1f) =
        compose.rvShot(name, dark, fontScale = fontScale) { Over(prompt) }

    @Composable
    private fun Over(prompt: LocalNetworkPrompt) {
        Box(Modifier.fillMaxSize()) {
            RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {})
            val text = prompt.text()
            RvSheetLayout({ 1f }, {}, text.title) {
                RvConfirmContent(text.title, text.body, text.confirm, text.dismiss, onConfirm = {}, onDismiss = {})
            }
        }
    }
}
