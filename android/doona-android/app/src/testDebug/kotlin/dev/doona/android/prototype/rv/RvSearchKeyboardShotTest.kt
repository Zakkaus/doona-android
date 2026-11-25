package dev.doona.android.prototype.rv

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.feature.nodes.RvNodePicker
import dev.doona.android.feature.perapp.RvPerAppScreen
import dev.doona.android.prototype.rv.connections.RvConnectionsPage
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RvNodePickerSample
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.settings.rvPerAppActions
import dev.doona.android.prototype.rv.settings.rvPerAppSample
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboard over the search fields that [RvKeyboardShotTest] does not draw, as it rises on the empty field: the 節點
 * search page that 活動's and 策略's pills open (`proto-rv-nodes-search-ime-*`), 連線's search (`-connections-search-ime-*`)
 * and 依 App's (`-perapp-search-ime-*`), light, dark and fs200. Then the lists that scroll, scrolled as far as they go
 * with the keyboard still up (`-ime-end-*`, light and dark): the search page, 依 App, and the pull-down picker
 * (`-node-picker-ime-end-*`), whose bar's field is the search page's, so its first screen is the search page's too and
 * only its end can differ. 連線's sample fits its window and does not scroll, so its first screen is its end. The
 * keyboard is [showKeyboard]'s 300 dp IME inset with a flat panel standing in for the system's window, as in
 * [RvKeyboardShotTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSearchKeyboardShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private var host: View? = null

    private var focus: FocusManager? = null

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun nodesLight() = shot("nodes-search-ime", dark = false) { Search() }

    @Test fun nodesDark() = shot("nodes-search-ime", dark = true) { Search() }

    @Test fun nodesFs200() = shot("nodes-search-ime", fontScale = RV_FS200) { Search() }

    @Test fun nodesEndLight() = shot("nodes-search-ime-end", dark = false, end = true) { Search() }

    @Test fun nodesEndDark() = shot("nodes-search-ime-end", dark = true, end = true) { Search() }

    @Test fun pullDownEndLight() = shot("node-picker-ime-end", dark = false, end = true) { PullDown() }

    @Test fun pullDownEndDark() = shot("node-picker-ime-end", dark = true, end = true) { PullDown() }

    @Test fun connectionsLight() = shot("connections-search-ime", dark = false) { Connections() }

    @Test fun connectionsDark() = shot("connections-search-ime", dark = true) { Connections() }

    @Test fun connectionsFs200() = shot("connections-search-ime", fontScale = RV_FS200) { Connections() }

    @Test fun perAppLight() = shot("perapp-search-ime", dark = false) { PerApp() }

    @Test fun perAppDark() = shot("perapp-search-ime", dark = true) { PerApp() }

    @Test fun perAppFs200() = shot("perapp-search-ime", fontScale = RV_FS200) { PerApp() }

    @Test fun perAppEndLight() = shot("perapp-search-ime-end", dark = false, end = true) { PerApp() }

    @Test fun perAppEndDark() = shot("perapp-search-ime-end", dark = true, end = true) { PerApp() }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, end: Boolean = false, content: @Composable () -> Unit) {
        rvAnimatorScale(0f)
        compose.rvShot(
            name,
            dark,
            before = {
                // The field takes the cursor (the search page and 連線 do it by themselves), then the keyboard rises.
                onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus)
                showKeyboard(requireNotNull(host), KEYBOARD)
                if (end) {
                    rvScrollBy(TO_THE_END)
                } else {
                    // The cursor blinks: taking the focus again, as a second tap on the field does, shows it at any time.
                    runOnUiThread { requireNotNull(focus).clearFocus() }
                    onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus)
                }
                waitForIdle()
            },
            fontScale = fontScale,
        ) { WithKeyboard(content) }
    }

    @Composable
    private fun WithKeyboard(content: @Composable () -> Unit) {
        host = LocalView.current
        focus = LocalFocusManager.current
        // A field draws its cursor only in a focused window, which the phone's is while it types. The test's window
        // reports none after a dark capture switches it to night, so it is told it has the focus.
        val window = LocalWindowInfo.current
        val focused = remember(window) {
            object : WindowInfo by window {
                override val isWindowFocused = true
            }
        }
        CompositionLocalProvider(LocalWindowInfo provides focused) {
            Box(Modifier.fillMaxSize()) {
                content()
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(KEYBOARD).background(Rv.colors.track))
            }
        }
    }

    @Composable
    private fun Search() = RvNodePickerSample(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {}, searchPage = true)

    @Composable
    private fun PullDown() = RvNodePickerSample(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {})

    /** 連線 as its pill opens the search: the field empty, every connection still listed. */
    @Composable
    private fun Connections() = RvConnectionsPage(RvPolicyData.connections, query = "")

    /** 依 App as 設定 has it (只有勾選的, the apps listed), its search field tapped. */
    @Composable
    private fun PerApp() = RvPerAppScreen(rvPerAppSample("only"), rvPerAppActions(), onBack = {})

    private companion object {
        val KEYBOARD = 300.dp

        /** Farther than any of these lists runs: the scroll stops at its end. */
        const val TO_THE_END = 10_000f
    }
}
