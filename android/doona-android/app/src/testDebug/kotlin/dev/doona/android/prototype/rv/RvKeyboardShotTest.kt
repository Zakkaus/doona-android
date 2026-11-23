package dev.doona.android.prototype.rv

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.gallery.RV_SAMPLE_LINK
import dev.doona.android.prototype.rv.nodes.RvNodePicker
import dev.doona.android.prototype.rv.subs.RvAddState
import dev.doona.android.prototype.rv.subs.RvSubAdd
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The keyboard over the two pages that take typing, light, dark and fs200: 新增訂閱 with its link field focused
 * (`proto-rv-sub-add-ime-*`) and the pull-down picker's search page with a query typed (`-policy-picker-ime-*`).
 * The test sets the IME inset of a keyboard 300 dp tall on the content, as the system dispatches it when the keyboard
 * rises, so every page that reads `WindowInsets.ime` sees it. The keyboard is the system's own window and is not
 * drawn by the library: a flat panel of its height stands in so the golden shows what it covers (recorded twice, byte-identical).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvKeyboardShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private var host: View? = null

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun addLight() = shot("sub-add-ime", dark = false) { Add() }

    @Test fun addDark() = shot("sub-add-ime", dark = true) { Add() }

    @Test fun addFs200() = shot("sub-add-ime", fontScale = RV_FS200) { Add() }

    @Test fun pickerLight() = shot("policy-picker-ime", dark = false, query = QUERY) { Picker() }

    @Test fun pickerDark() = shot("policy-picker-ime", dark = true, query = QUERY) { Picker() }

    @Test fun pickerFs200() = shot("policy-picker-ime", fontScale = RV_FS200, query = QUERY) { Picker() }

    /** The search page's list ends above the keyboard: its scroll range grows by the keyboard's height less the bar the frame already reserves. */
    @Test
    fun thePickersListEndFollowsTheKeyboard() {
        rvAnimatorScale(0f)
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { WithKeyboard { Picker() } } }
        compose.waitForIdle()
        val before = scrollRange()
        compose.showKeyboard(requireNotNull(host), KEYBOARD)
        val grew = scrollRange() - before
        assertTrue("the range grew by $grew dp for a keyboard of $KEYBOARD", grew >= KEYBOARD.value - NAV_BAR_DP)
    }

    /** The list's scroll range in dp. */
    private fun scrollRange(): Float =
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].maxValue() / compose.density.density

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, query: String? = null, content: @Composable () -> Unit) {
        rvAnimatorScale(0f)
        compose.rvShot(
            name,
            dark,
            before = {
                // The field takes the cursor (the picker's does it by itself), then the keyboard rises over the page.
                if (query == null) onAllNodes(hasSetTextAction())[0].performSemanticsAction(SemanticsActions.RequestFocus)
                showKeyboard(requireNotNull(host), KEYBOARD)
                if (query != null) onNode(hasSetTextAction()).performTextInput(query)
                waitForIdle()
            },
            fontScale = fontScale,
        ) { WithKeyboard(content) }
    }

    @Composable
    private fun WithKeyboard(content: @Composable () -> Unit) {
        host = LocalView.current
        Box(Modifier.fillMaxSize()) {
            content()
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(KEYBOARD).background(Rv.colors.track))
        }
    }

    @Composable
    private fun Add() = RvSubAdd(RvAddState(link = RV_SAMPLE_LINK), {}, onPaste = {}, onImport = {}, onBack = {})

    @Composable
    private fun Picker() = RvNodePicker(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {}, searchPage = true)

    private companion object {
        val KEYBOARD = 300.dp

        /** What the frame's own bottom inset takes from the keyboard's height (a navigation bar's 48 dp at most). */
        const val NAV_BAR_DP = 48f
        const val QUERY = "Tokyo"
    }
}

/** Raises the IME inset to [height] on [host], as the system's dispatch does when the keyboard comes up. */
internal fun ComposeContentTestRule.showKeyboard(host: View, height: Dp) {
    val px = (height.value * density.density).roundToInt()
    val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
        .setVisible(WindowInsetsCompat.Type.ime(), true)
        .build()
    runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
    waitForIdle()
}
