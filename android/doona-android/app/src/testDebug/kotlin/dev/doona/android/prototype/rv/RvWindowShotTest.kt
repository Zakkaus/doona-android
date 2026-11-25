package dev.doona.android.prototype.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.sampleNodesPage
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyScreenSample
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvSettingsScreenSample
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.testing.GestureBar
import dev.doona.android.testing.StatusBar
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The existing pages in windows other than the 412 dp phone, recorded as they are, light and dark, first screen only:
 * 活動, 策略, 設定 and 節點 at 840 dp wide (`proto-rv-wide-activity-*`, `-wide-policy-*`, `-wide-settings-*`,
 * `-wide-nodes-*`), each of them under a display cutout (`-cutout-<page>-*`) and with three-button navigation
 * (`-threebutton-<page>-*`). The bars are the
 * test's stand-ins: a notch phone's 48 dp status bar with a notch drawn in it, and a 48 dp navigation bar with its
 * three buttons; the pages read them as insets, as they read the frame's everywhere else. Under three-button navigation
 * each of 策略, 設定 and 節點, scrolled to its end, is also checked to leave no text under the bar or the tab bar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvWindowShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WIDE)
    fun wideActivityLight() = compose.rvShot("wide-activity", dark = false) { Activity() }

    @Test
    @Config(qualifiers = WIDE)
    fun wideActivityDark() = compose.rvShot("wide-activity", dark = true) { Activity() }

    @Test
    @Config(qualifiers = WIDE)
    fun widePolicyLight() = compose.rvShot("wide-policy", dark = false) { Policy() }

    @Test
    @Config(qualifiers = WIDE)
    fun widePolicyDark() = compose.rvShot("wide-policy", dark = true) { Policy() }

    @Test
    @Config(qualifiers = WIDE)
    fun wideSettingsLight() = compose.rvShot("wide-settings", dark = false) { Settings() }

    @Test
    @Config(qualifiers = WIDE)
    fun wideSettingsDark() = compose.rvShot("wide-settings", dark = true) { Settings() }

    @Test fun cutoutActivityLight() = compose.rvShot("cutout-activity", dark = false) { Cutout { Activity() } }

    @Test fun cutoutActivityDark() = compose.rvShot("cutout-activity", dark = true) { Cutout { Activity() } }

    @Test fun threeButtonActivityLight() = compose.rvShot("threebutton-activity", dark = false, gesture = false) { ThreeButton { Activity() } }

    @Test fun threeButtonActivityDark() = compose.rvShot("threebutton-activity", dark = true, gesture = false) { ThreeButton { Activity() } }

    @Test
    @Config(qualifiers = WIDE)
    fun wideNodesLight() = compose.rvShot("wide-nodes", dark = false) { Nodes() }

    @Test
    @Config(qualifiers = WIDE)
    fun wideNodesDark() = compose.rvShot("wide-nodes", dark = true) { Nodes() }

    @Test fun cutoutPolicyLight() = compose.rvShot("cutout-policy", dark = false) { Cutout { Policy() } }

    @Test fun cutoutPolicyDark() = compose.rvShot("cutout-policy", dark = true) { Cutout { Policy() } }

    @Test fun cutoutSettingsLight() = compose.rvShot("cutout-settings", dark = false) { Cutout { Settings() } }

    @Test fun cutoutSettingsDark() = compose.rvShot("cutout-settings", dark = true) { Cutout { Settings() } }

    @Test fun cutoutNodesLight() = compose.rvShot("cutout-nodes", dark = false) { Cutout { Nodes() } }

    @Test fun cutoutNodesDark() = compose.rvShot("cutout-nodes", dark = true) { Cutout { Nodes() } }

    @Test fun threeButtonPolicyLight() = compose.rvShot("threebutton-policy", dark = false, gesture = false) { ThreeButton { Policy() } }

    @Test fun threeButtonPolicyDark() = compose.rvShot("threebutton-policy", dark = true, gesture = false) { ThreeButton { Policy() } }

    @Test fun threeButtonSettingsLight() = compose.rvShot("threebutton-settings", dark = false, gesture = false) { ThreeButton { Settings() } }

    @Test fun threeButtonSettingsDark() = compose.rvShot("threebutton-settings", dark = true, gesture = false) { ThreeButton { Settings() } }

    @Test fun threeButtonNodesLight() = compose.rvShot("threebutton-nodes", dark = false, gesture = false) { ThreeButton { Nodes() } }

    @Test fun threeButtonNodesDark() = compose.rvShot("threebutton-nodes", dark = true, gesture = false) { ThreeButton { Nodes() } }

    @Test fun policysEndClearsTheThreeButtonBar() = endClearsTheBar { Policy() }

    @Test fun settingsEndClearsTheThreeButtonBar() = endClearsTheBar { Settings() }

    @Test fun nodesEndClearsTheThreeButtonBar() = endClearsTheBar { Nodes() }

    /**
     * Under three-button navigation, scrolled to its end, the [page] leaves no text under the bar, nor under the floating
     * tab bar that stands over it on a tab: the lowest line ends above both.
     */
    private fun endClearsTheBar(page: @Composable () -> Unit) {
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { ThreeButton(page) } }
        compose.rvScrollBy(TO_THE_END)
        val density = compose.density.density
        // The floating tab bar's tabs, which have folded their labels away by the end: the tabs in the window's lower half,
        // a segmented control's (tabs too) having scrolled away above.
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom / density
        val tab = SemanticsMatcher("a tab of the tab bar") { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Tab && node.boundsInRoot.top / density > window / 2
        }
        val bar = window - NAV_BAR.value
        val nav = compose.onAllNodes(tab).fetchSemanticsNodes().minOfOrNull { it.boundsInRoot.top / density } ?: bar
        val text = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and !hasAnyAncestor(tab), useUnmergedTree = true)
        val lowest = text.fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }
        val end = lowest.boundsInRoot.bottom / density
        val line = lowest.config[SemanticsProperties.Text].joinToString()
        assertTrue("the last line ($line) ends at $end dp; the tab bar starts at $nav dp, the navigation bar at $bar dp", end <= minOf(nav, bar))
    }

    @Composable
    private fun Activity() = RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {})

    @Composable
    private fun Policy() = RvPolicyScreenSample(RvPolicyState(mode = RvRouteMode.Rule), onChange = {})

    @Composable
    private fun Settings() = RvSettingsScreenSample(rvSampleSettings(), onChange = {}, onOpenTheme = {})

    @Composable
    private fun Nodes() = RvNodesPage(sampleNodesPage(), onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {})

    /** A notch phone: the status bar is as tall as the notch, which the page reads as its top inset. */
    @Composable
    private fun Cutout(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalFrameInsets provides WindowInsets(top = CUTOUT_BAR, bottom = GestureBar)) { content() }
            Box(
                Modifier.align(Alignment.TopCenter).size(NOTCH_WIDTH, NOTCH_HEIGHT).background(Color.Black, RoundedCornerShape(bottomStart = NOTCH_HEIGHT / 2, bottomEnd = NOTCH_HEIGHT / 2)),
            )
        }
    }

    /** Three-button navigation: a bar of its height at the bottom, with back, home and recents, which the page reads as its bottom inset. */
    @Composable
    private fun ThreeButton(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalFrameInsets provides WindowInsets(top = StatusBar, bottom = NAV_BAR)) { content() }
            val ink = Rv.colors.text
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(NAV_BAR).background(Rv.colors.page)) {
                val mid = size.height / 2
                val half = BUTTON.toPx() / 2
                val line = Stroke(BUTTON_LINE.toPx())
                val at = { share: Float -> size.width * share }
                // Back: a triangle pointing left; home: a ring; recents: a rounded square.
                drawPath(
                    Path().apply {
                        moveTo(at(BACK) + half, mid - half)
                        lineTo(at(BACK) - half, mid)
                        lineTo(at(BACK) + half, mid + half)
                        close()
                    },
                    ink,
                    style = line,
                )
                drawCircle(ink, half, Offset(at(HOME), mid), style = line)
                drawRoundRect(ink, Offset(at(RECENTS) - half, mid - half), Size(half * 2, half * 2), androidx.compose.ui.geometry.CornerRadius(BUTTON_CORNER.toPx()), style = line)
            }
        }
    }

    private companion object {
        /** The 840 dp window of the old shell's `*-wide` goldens, as tall as the phone. */
        const val WIDE = "zh-rTW-w840dp-h915dp-notnight-xhdpi"

        val CUTOUT_BAR = 48.dp
        val NOTCH_WIDTH = 154.dp
        val NOTCH_HEIGHT = 30.dp
        val NAV_BAR = 48.dp
        val BUTTON = 16.dp
        val BUTTON_LINE = 2.dp
        val BUTTON_CORNER = 3.dp
        const val BACK = 0.25f
        const val HOME = 0.5f
        const val RECENTS = 0.75f

        /** Farther than any of these pages runs: the scroll stops at its end. */
        const val TO_THE_END = 10_000f
    }
}
